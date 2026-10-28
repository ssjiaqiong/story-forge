package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.util.WordCounts;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisVO;
import com.storyforge.modules.bookshelf.dto.ChapterReviseRequest;
import com.storyforge.modules.bookshelf.dto.ChapterReviseVO;
import com.storyforge.modules.bookshelf.entity.ChapterAnalysisEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterAnalysisMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 按分析修改：把审稿意见变成一份可对比的新稿。
 *
 * <p>只生成、不落库。改稿是要人来看的，直接覆盖会让作者失去否决权，
 * 所以这里返回原文与新稿，保存与重新分析由前端在对比窗口确认后触发。</p>
 *
 * <p>门槛只有一条：<b>本章必须已经分析过</b>。没有审稿单就没有「按分析修改」，
 * 但仍可以走正文生成或手改；至于章节顺序，这里不做限制——改旧章不会影响
 * 新章的事实抽取，改完重新分析那一章即可。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterReviseService {

    /** 改写比生成慢：给足 10 分钟，避免长章被网关超时截断 */
    private static final int TIMEOUT_MS = 600_000;
    private static final int DEFAULT_TARGET_WORDS = 2500;
    private static final int MIN_TARGET_WORDS = 300;
    private static final int MAX_TARGET_WORDS = 6000;
    private static final int MAX_NOTES = 8;
    private static final int MAX_NOTE_LENGTH = 60;
    private static final double TEMPERATURE = 0.6D;

    private static final String RETRY_SUFFIX = """

            【重试】上一次输出不是合法 JSON 或正文为空。请重新输出，且必须满足：
            只输出一个 JSON 对象；content 是完整正文；字符串值内不出现英文双引号与真实换行；
            不要 markdown 代码围栏；字段名与示例一致。
            """;

    private final ChapterMapper chapterMapper;
    private final ChapterAnalysisMapper analysisMapper;
    private final NovelMapper novelMapper;
    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ChapterRevisePromptBuilder promptBuilder;
    private final ObjectMapper objectMapper;
    private final com.storyforge.modules.autowrite.support.AutoWriteGuard autoWriteGuard;

    public ChapterReviseVO revise(Long chapterId, ChapterReviseRequest request) {
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw BizException.of(ErrorCode.CHAPTER_NOT_FOUND);
        }
        autoWriteGuard.assertEditable(chapter.getNovelId());
        if (!hasText(chapter.getContent())) {
            throw BizException.of(ErrorCode.CHAPTER_CONTENT_EMPTY);
        }

        ChapterAnalysisEntity latest = latestAnalysis(chapterId);
        ChapterAnalysisVO analysis = latest == null ? null : parseAnalysis(latest);
        if (latest == null) {
            throw BizException.of(ErrorCode.CHAPTER_NOT_ANALYZED);
        }

        NovelEntity novel = novelMapper.selectById(chapter.getNovelId());
        String novelTitle = novel == null ? "" : novel.getTitle();
        String genre = novel == null ? "" : novel.getGenre();

        int wordCountBefore = WordCounts.countChinese(chapter.getContent());
        int targetWords = resolveTargetWords(request, latest);
        String intensity = normalizeIntensity(request == null ? null : request.getIntensity());

        String system = promptBuilder.system(novelTitle, chapter.getChapterNo(), targetWords);
        String userPrompt = promptBuilder.prompt(chapter, novelTitle, genre, analysis,
                request == null ? List.of() : request.getItems(),
                request == null ? null : request.getInstruction(),
                intensity, targetWords, wordCountBefore);

        AiEndpoint endpoint = endpoint(AiScene.CHAPTER_REVISE, targetWords);
        long startedAt = System.currentTimeMillis();
        ChatResult reply = chat(endpoint, system, userPrompt);
        Draft draft = parse(reply.content());
        if (draft == null) {
            log.warn("[revise] parse failed, retrying chapterId={}: {}", chapterId, brief(reply.content()));
            ChatResult retry = chat(endpoint, system + RETRY_SUFFIX, userPrompt);
            draft = parse(retry.content());
            if (draft == null) {
                throw BizException.of(ErrorCode.CHAPTER_REVISE_EMPTY);
            }
            reply = retry;
        }

        String revised = clean(draft.content(), chapter.getChapterNo(), chapter.getTitle());
        if (!hasText(revised)) {
            throw BizException.of(ErrorCode.CHAPTER_REVISE_EMPTY);
        }

        int wordCountAfter = WordCounts.countChinese(revised);
        log.info("[revise] chapterId={} words {} -> {} notes={} model={}",
                chapterId, wordCountBefore, wordCountAfter, draft.notes().size(), reply.model());

        return new ChapterReviseVO(chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                chapter.getContent(), revised, draft.notes(),
                wordCountBefore, wordCountAfter, intensity, reply.model(),
                System.currentTimeMillis() - startedAt);
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                               */
    /* ------------------------------------------------------------------ */

    private ChapterAnalysisEntity latestAnalysis(Long chapterId) {
        List<ChapterAnalysisEntity> rows = analysisMapper.selectList(
                Wrappers.<ChapterAnalysisEntity>lambdaQuery()
                        .eq(ChapterAnalysisEntity::getChapterId, chapterId)
                        .orderByDesc(ChapterAnalysisEntity::getCreatedAt)
                        .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 报告 JSON 解析失败不算致命：只是提示词里少两行背景 */
    private ChapterAnalysisVO parseAnalysis(ChapterAnalysisEntity row) {
        if (!hasText(row.getResultJson())) {
            return null;
        }
        try {
            return objectMapper.readValue(row.getResultJson(), ChapterAnalysisVO.class);
        } catch (Exception ex) {
            log.warn("[revise] parse analysis failed id={}: {}", row.getId(), ex.getMessage());
            return null;
        }
    }

    private int resolveTargetWords(ChapterReviseRequest request, ChapterAnalysisEntity analysis) {
        Integer value = request == null ? null : request.getTargetWords();
        if (value == null || value <= 0) {
            value = analysis.getTargetWords();
        }
        if (value == null || value <= 0) {
            value = DEFAULT_TARGET_WORDS;
        }
        return Math.min(MAX_TARGET_WORDS, Math.max(MIN_TARGET_WORDS, value));
    }

    private String normalizeIntensity(String intensity) {
        if (intensity == null) {
            return "MEDIUM";
        }
        return switch (intensity.trim().toUpperCase()) {
            case "LIGHT" -> "LIGHT";
            case "HEAVY" -> "HEAVY";
            default -> "MEDIUM";
        };
    }

    private AiEndpoint endpoint(AiScene scene, int targetWords) {
        AiEndpoint base = endpointResolver.resolve(scene);
        // 中文正文大致 1 字 1~1.5 token，再留出 JSON 结构的余量
        int maxTokens = Math.min(16000, Math.max(4000, targetWords * 3));
        return new AiEndpoint(base.baseUrl(), base.apiKey(), base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS), base.extraHeaders(), TEMPERATURE, maxTokens);
    }

    private ChatResult chat(AiEndpoint endpoint, String systemPrompt, String userPrompt) {
        try {
            return aiUsageService.chat(AiScene.CHAPTER_REVISE, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String reason = hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败";
            log.warn("[revise] chat failed: {}", ex.toString());
            throw BizException.of(ErrorCode.AI_CALL_FAILED, reason);
        }
    }

    private Draft parse(String raw) {
        String json = LenientJson.extractObject(raw);
        if (!hasText(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(LenientJson.repair(json));
            if (node == null || !node.isObject()) {
                return null;
            }
            String content = node.path("content").asText("");
            if (!hasText(content)) {
                return null;
            }
            List<String> notes = new ArrayList<>();
            JsonNode notesNode = node.path("notes");
            if (notesNode.isArray()) {
                for (JsonNode item : notesNode) {
                    String note = item == null ? "" : item.asText("");
                    if (hasText(note)) {
                        notes.add(truncate(note.trim(), MAX_NOTE_LENGTH));
                    }
                    if (notes.size() >= MAX_NOTES) {
                        break;
                    }
                }
            }
            return new Draft(content, notes);
        } catch (Exception ex) {
            log.warn("[revise] json parse error: {}", ex.getMessage());
            return null;
        }
    }

    /** 去掉代码围栏、模型自加的章节标题与「（完）」 */
    private String clean(String raw, Integer chapterNo, String title) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            text = firstLineEnd > 0 ? text.substring(firstLineEnd + 1) : text;
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
            text = text.trim();
        }
        String[] lines = text.split("\n", 2);
        if (lines.length == 2) {
            String head = lines[0].trim();
            boolean isTitle = head.matches("^第\\s*\\d+\\s*章.*") || (!title.isEmpty() && head.equals(title));
            if (isTitle && head.length() <= 80) {
                text = lines[1].trim();
            }
        }
        text = text.replaceAll("\\s*[（(]完[)）]\\s*$", "");
        return text.trim();
    }

    private String brief(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…";
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 解析结果：正文 + 改动说明 */
    private record Draft(String content, List<String> notes) {
    }
}
