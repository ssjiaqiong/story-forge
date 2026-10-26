package com.storyforge.modules.inspiration.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.inspiration.dto.CreateNovelRequest;
import com.storyforge.modules.inspiration.dto.CreateNovelResult;
import com.storyforge.modules.inspiration.dto.MessageVO;
import com.storyforge.modules.inspiration.dto.SendMessageResult;
import com.storyforge.modules.inspiration.dto.SessionVO;
import com.storyforge.modules.inspiration.entity.InspirationMessageEntity;
import com.storyforge.modules.inspiration.entity.InspirationSessionEntity;
import com.storyforge.modules.inspiration.entity.InspirationSkillEntity;
import com.storyforge.modules.inspiration.mapper.InspirationMessageMapper;
import com.storyforge.modules.inspiration.mapper.InspirationSessionMapper;
import com.storyforge.modules.novel.dto.NovelDraft;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.novel.service.NovelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 灵感模式服务实现。
 *
 * <p>核心不变量：</p>
 * <ol>
 *   <li>每个用户只有一条会话（库上部分唯一索引兜底），首次访问自动创建；</li>
 *   <li>上下文 = 会话内 {@code status = OK} 的消息，按时间升序，超长时保留最近的若干条；</li>
 *   <li>AI 调用失败不丢用户输入：失败落成一条 {@code FAILED} 助手消息，且不进入后续上下文；</li>
 *   <li>一键创建小说以会话内容为唯一依据，抽取失败时按会话内容兜底，保证一键必有结果。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InspirationServiceImpl implements InspirationService {

    /** 参与上下文的最大消息条数 */
    private static final int CONTEXT_MAX_MESSAGES = 40;
    /** 参与上下文的最大字符数（粗略等同 token 预算，避免超长请求） */
    private static final int CONTEXT_MAX_CHARS = 24_000;
    /** 会话标题最多取前多少字 */
    private static final int TITLE_MAX_LENGTH = 40;
    /** 抽取小说要素时送入模型的对话上限 */
    private static final int TRANSCRIPT_MAX_CHARS = 14_000;

    /** 小说要素抽取提示词：只输出 JSON，便于稳定解析 */
    private static final String EXTRACT_PROMPT = """
            你是小说要素抽取器。输入是作者与小说策划助手的对话记录。
            你的任务是从对话中提炼「已经确定或可以合理推断」的小说要素，不是重新创作一个故事。

            【抽取原则】
            1. 作者明确说过的内容优先，尤其是书名、主角身份、核心冲突、篇幅。
            2. 作者否定过的方向不要写回来；对话里出现过的专有名词保持一致。
            3. 信息不足时可以合理推断，但推断必须与已有内容自洽，不要引入全新的设定体系。
            4. 简介要写成一个能吸引读者的故事梗概，不是要素罗列。

            【输出格式】
            只输出一个 JSON 对象，不要输出任何解释文字，不要使用 markdown 代码块：
            {
              "title": "书名，不超过 30 字；优先选对话里出现过的书名，没有就起一个符合题材的",
              "genre": "题材，2~6 字，如 玄幻 / 都市 / 悬疑 / 科幻",
              "tags": ["3~5 个具体标签，如 升级流、群像、旧书修复"],
              "synopsis": "180~300 字：主角是谁 → 想要什么 → 被什么阻碍 → 失败会失去什么 → 主线走向，结尾留钩子",
              "protagonist": "主角一句话设定：身份 + 欲望 + 一个弱点",
              "estimatedChapters": 120
            }

            【硬性要求】
            - 所有字段都必须有值，不要留空、不要输出 null。
            - estimatedChapters 按对话里的篇幅与节奏推断，范围 30~600。
            - 所有字段值内不要出现英文双引号与真实换行，需要引用时用「」。
            """;

    private final InspirationSessionMapper sessionMapper;
    private final InspirationMessageMapper messageMapper;
    private final InspirationSkillService skillService;
    private final AiSceneEndpointResolver endpointResolver;
    private final NovelService novelService;
    private final ObjectMapper objectMapper;
    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;
    private final com.storyforge.modules.ai.service.AiUsageService aiUsageService;

    @Override
    public SessionVO session() {
        Long userId = UserContextHolder.requireUserId();
        InspirationSessionEntity session = getOrCreateSession(userId);
        InspirationSkillEntity skill = skillService.resolveForUser(userId, session.getSkillCode());
        return toSessionVO(session, listMessages(session.getId()), skill);
    }

    @Override
    public SendMessageResult send(String rawContent) {
        Long userId = UserContextHolder.requireUserId();
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "消息内容不能为空");
        }
        if (content.length() > 8000) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "单条消息最长 8000 字");
        }

        InspirationSessionEntity session = getOrCreateSession(userId);
        InspirationSkillEntity skill = skillService.resolveForUser(userId, session.getSkillCode());

        InspirationMessageEntity userMessage = saveMessage(session, "USER", content, "OK", null, null, null, null);

        ChatResult reply = null;
        String error = null;
        try {
            AiEndpoint endpoint = endpointResolver.resolve(AiScene.INSPIRATION_CHAT);
            reply = aiUsageService.chat(AiScene.INSPIRATION_CHAT, endpoint, buildContext(session, skill));
        } catch (BizException ex) {
            error = ex.getMessage();
            log.warn("[inspiration] chat rejected userId={}: {}", userId, error);
        } catch (RuntimeException ex) {
            error = ex.getMessage() == null ? "AI 调用失败" : ex.getMessage();
            log.warn("[inspiration] chat failed userId={}: {}", userId, ex.toString());
        }

        InspirationMessageEntity assistantMessage;
        if (reply != null) {
            assistantMessage = saveMessage(session, "ASSISTANT", reply.content(), "OK",
                    reply.model(), reply.promptTokens(), reply.completionTokens(), null);
        } else {
            assistantMessage = saveMessage(session, "ASSISTANT", "", "FAILED",
                    null, null, null, truncate(error, 480));
        }

        String title = session.getTitle();
        if (!hasText(title)) {
            title = deriveTitle(content);
        }
        updateSessionStats(session, title);

        SendMessageResult result = new SendMessageResult();
        result.setUserMessage(toMessageVO(userMessage));
        result.setAssistantMessage(toMessageVO(assistantMessage));
        result.setErrorMessage(error);
        result.setSession(toSessionVO(reloadSession(session.getId()), List.of(), skill));
        return result;
    }

    @Override
    public SessionVO switchSkill(String skillCode) {
        Long userId = UserContextHolder.requireUserId();
        InspirationSkillEntity skill = skillService.requireByCode(userId, skillCode);
        InspirationSessionEntity session = getOrCreateSession(userId);

        InspirationSessionEntity patch = new InspirationSessionEntity();
        patch.setId(session.getId());
        patch.setSkillCode(skill.getCode());
        patch.setVersion(session.getVersion());
        sessionMapper.updateById(patch);
        log.info("[inspiration] skill switched userId={} code={}", userId, skill.getCode());

        InspirationSessionEntity fresh = reloadSession(session.getId());
        return toSessionVO(fresh, listMessages(fresh.getId()), skill);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearContext() {
        Long userId = UserContextHolder.requireUserId();
        InspirationSessionEntity session = getOrCreateSession(userId);
        messageMapper.delete(Wrappers.<InspirationMessageEntity>lambdaQuery()
                .eq(InspirationMessageEntity::getSessionId, session.getId()));
        sessionMapper.update(null, Wrappers.<InspirationSessionEntity>lambdaUpdate()
                .set(InspirationSessionEntity::getTitle, null)
                .set(InspirationSessionEntity::getMessageCount, 0)
                .set(InspirationSessionEntity::getLastMessageAt, null)
                .set(InspirationSessionEntity::getUpdatedAt, LocalDateTime.now())
                .eq(InspirationSessionEntity::getId, session.getId()));
        log.info("[inspiration] context cleared userId={} sessionId={}", userId, session.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSession() {
        Long userId = UserContextHolder.requireUserId();
        InspirationSessionEntity session = findSession(userId);
        if (session == null) {
            return;
        }
        messageMapper.delete(Wrappers.<InspirationMessageEntity>lambdaQuery()
                .eq(InspirationMessageEntity::getSessionId, session.getId()));
        sessionMapper.deleteById(session.getId());
        log.info("[inspiration] session deleted userId={} sessionId={}", userId, session.getId());
    }

    @Override
    public CreateNovelResult createNovel(CreateNovelRequest request) {
        Long userId = UserContextHolder.requireUserId();
        InspirationSessionEntity session = getOrCreateSession(userId);
        List<InspirationMessageEntity> messages = listMessages(session.getId());
        boolean hasUserContent = messages.stream()
                .anyMatch(item -> "USER".equals(item.getRole()) && "OK".equals(item.getStatus()));
        if (!hasUserContent) {
            throw BizException.of(ErrorCode.INSPIRATION_EMPTY);
        }

        ExtractedNovel extracted = null;
        String extraction = "AI";
        String note = null;
        try {
            AiEndpoint base = endpointResolver.resolve(AiScene.NOVEL_EXTRACT);
            // 抽取任务要稳定输出：压低温度、限制输出长度
            AiEndpoint endpoint = new AiEndpoint(base.baseUrl(), base.apiKey(), base.model(),
                    base.timeoutMs(), base.extraHeaders(), 0.2D, 1600);
            ChatResult result = aiUsageService.chat(AiScene.NOVEL_EXTRACT, endpoint, List.of(
                    ChatMessage.system(extractSystemPrompt()),
                    ChatMessage.user("以下是对话记录：\n\n" + buildTranscript(messages))));
            extracted = parseExtraction(result.content());
            if (extracted == null) {
                note = "模型返回的内容无法解析为结构化要素，已按会话内容生成";
            }
        } catch (BizException ex) {
            note = ex.getMessage();
        } catch (RuntimeException ex) {
            note = "AI 抽取失败：" + (ex.getMessage() == null ? "未知原因" : ex.getMessage());
            log.warn("[inspiration] novel extraction failed userId={}: {}", userId, ex.toString());
        }
        if (extracted == null) {
            extraction = "FALLBACK";
            extracted = fallbackExtraction(session, messages);
        }

        CreateNovelRequest condition = request == null ? new CreateNovelRequest() : request;
        NovelDraft draft = new NovelDraft(
                hasText(condition.getTitle()) ? condition.getTitle() : extracted.title(),
                hasText(condition.getGenre()) ? condition.getGenre() : extracted.genre(),
                extracted.tags(),
                extracted.intro(),
                null,
                "INSPIRATION",
                session.getId());
        NovelVO novel = novelService.createDraft(draft);

        CreateNovelResult result = new CreateNovelResult();
        result.setNovel(novel);
        result.setExtraction(extraction);
        result.setNote(note);
        log.info("[inspiration] novel created userId={} novelId={} extraction={}",
                userId, novel.getId(), extraction);
        return result;
    }

    /* ------------------------------------------------------------------ */

    /** 取当前会话，没有则创建。并发首次进入由唯一索引兜底。 */
    private InspirationSessionEntity getOrCreateSession(Long userId) {
        InspirationSessionEntity existing = findSession(userId);
        if (existing != null) {
            skillService.ensureBuiltin(userId);
            return existing;
        }
        InspirationSkillEntity builtin = skillService.ensureBuiltin(userId);

        InspirationSessionEntity entity = new InspirationSessionEntity();
        entity.setUserId(userId);
        entity.setSkillCode(builtin.getCode());
        entity.setMessageCount(0);
        entity.setDeleted(0);
        entity.setVersion(0);
        try {
            sessionMapper.insert(entity);
            log.info("[inspiration] session created userId={} id={}", userId, entity.getId());
            return entity;
        } catch (DuplicateKeyException ex) {
            InspirationSessionEntity concurrent = findSession(userId);
            if (concurrent != null) {
                return concurrent;
            }
            throw ex;
        }
    }

    private InspirationSessionEntity findSession(Long userId) {
        return sessionMapper.selectOne(Wrappers.<InspirationSessionEntity>lambdaQuery()
                .eq(InspirationSessionEntity::getUserId, userId)
                .orderByDesc(InspirationSessionEntity::getCreatedAt)
                .last("limit 1"));
    }

    private InspirationSessionEntity reloadSession(Long id) {
        InspirationSessionEntity entity = sessionMapper.selectById(id);
        if (entity == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "灵感会话不存在");
        }
        return entity;
    }

    private List<InspirationMessageEntity> listMessages(Long sessionId) {
        return messageMapper.selectList(Wrappers.<InspirationMessageEntity>lambdaQuery()
                .eq(InspirationMessageEntity::getSessionId, sessionId)
                .orderByAsc(InspirationMessageEntity::getId));
    }

    private InspirationMessageEntity saveMessage(InspirationSessionEntity session,
                                                 String role,
                                                 String content,
                                                 String status,
                                                 String model,
                                                 Integer promptTokens,
                                                 Integer completionTokens,
                                                 String errorMessage) {
        InspirationMessageEntity entity = new InspirationMessageEntity();
        entity.setUserId(session.getUserId());
        entity.setSessionId(session.getId());
        entity.setRole(role);
        entity.setContent(content);
        entity.setStatus(status);
        entity.setModel(model);
        entity.setPromptTokens(promptTokens);
        entity.setCompletionTokens(completionTokens);
        entity.setErrorMessage(errorMessage);
        entity.setDeleted(0);
        entity.setVersion(0);
        messageMapper.insert(entity);
        return entity;
    }

    /** 刷新会话的消息条数与最后消息时间（单次 update，避免同一请求内版本冲突）。 */
    private void updateSessionStats(InspirationSessionEntity session, String title) {
        long okCount = messageMapper.selectCount(Wrappers.<InspirationMessageEntity>lambdaQuery()
                .eq(InspirationMessageEntity::getSessionId, session.getId())
                .eq(InspirationMessageEntity::getStatus, "OK"));

        InspirationSessionEntity patch = new InspirationSessionEntity();
        patch.setId(session.getId());
        patch.setVersion(session.getVersion());
        patch.setMessageCount((int) okCount);
        patch.setLastMessageAt(LocalDateTime.now());
        if (hasText(title)) {
            patch.setTitle(truncate(title, TITLE_MAX_LENGTH));
        }
        sessionMapper.updateById(patch);
    }

    /**
     * 要素抽取的 system 提示词：优先用用户在「提示词模板」里定制的指令段，
     * 未定制时回退内置常量（注册表不可用的异常路径也回退到这里）。
     */
    private String extractSystemPrompt() {
        String rendered = promptTemplateService.system(AiScene.NOVEL_EXTRACT, Map.of());
        return hasText(rendered) ? rendered : EXTRACT_PROMPT;
    }

    /**
     * 组装上下文：system 提示词 + 最近的若干条历史消息。
     *
     * <p>从最新一条往前累加，超出条数或字符预算即停止，保证「最近的对话优先」。</p>
     */
    private List<ChatMessage> buildContext(InspirationSessionEntity session, InspirationSkillEntity skill) {
        List<ChatMessage> payload = new ArrayList<>();
        if (hasText(skill.getSystemPrompt())) {
            payload.add(ChatMessage.system(skill.getSystemPrompt()));
        }

        List<InspirationMessageEntity> history = listMessages(session.getId());
        List<InspirationMessageEntity> picked = new ArrayList<>();
        int chars = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            InspirationMessageEntity item = history.get(i);
            if (!"OK".equals(item.getStatus())) {
                continue;
            }
            if (!"USER".equals(item.getRole()) && !"ASSISTANT".equals(item.getRole())) {
                continue;
            }
            String text = item.getContent() == null ? "" : item.getContent();
            if (picked.size() >= CONTEXT_MAX_MESSAGES) {
                break;
            }
            if (!picked.isEmpty() && chars + text.length() > CONTEXT_MAX_CHARS) {
                break;
            }
            picked.add(item);
            chars += text.length();
        }
        if (picked.isEmpty()) {
            return payload;
        }
        Collections.reverse(picked);
        for (InspirationMessageEntity item : picked) {
            payload.add(new ChatMessage(
                    "USER".equals(item.getRole()) ? "user" : "assistant",
                    item.getContent() == null ? "" : item.getContent()));
        }
        return payload;
    }

    /** 把对话整理成抽取用的纯文本记录。 */
    private String buildTranscript(List<InspirationMessageEntity> messages) {
        StringBuilder builder = new StringBuilder();
        for (InspirationMessageEntity item : messages) {
            if (!"OK".equals(item.getStatus())) {
                continue;
            }
            if (!"USER".equals(item.getRole()) && !"ASSISTANT".equals(item.getRole())) {
                continue;
            }
            String text = item.getContent() == null ? "" : item.getContent().trim();
            if (text.isEmpty()) {
                continue;
            }
            builder.append("USER".equals(item.getRole()) ? "作者：" : "策划：")
                    .append(text)
                    .append("\n\n");
        }
        String transcript = builder.toString().trim();
        if (transcript.length() <= TRANSCRIPT_MAX_CHARS) {
            return transcript;
        }
        int head = 5_000;
        int tail = TRANSCRIPT_MAX_CHARS - head;
        return transcript.substring(0, head)
                + "\n\n……（中间内容省略）……\n\n"
                + transcript.substring(transcript.length() - tail);
    }

    /** 宽松解析：兼容模型输出带 ```json 围栏、前后多话或值内混入英文双引号的情况。 */
    private ExtractedNovel parseExtraction(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        String candidate = LenientJson.extractObject(raw);
        if (candidate.isEmpty()) {
            return null;
        }
        JsonNode root = null;
        try {
            root = objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                root = objectMapper.readTree(LenientJson.repair(candidate));
                log.info("[inspiration] JSON 修复后解析成功，原始错误：{}", strictFailure.getMessage());
            } catch (Exception ex) {
                log.warn("[inspiration] parse extraction failed: {} | repaired: {}",
                        strictFailure.getMessage(), ex.getMessage());
            }
        }
        if (root == null) {
            return null;
        }
        String title = textOf(root, "title");
        String genre = textOf(root, "genre");
        String synopsis = textOf(root, "synopsis");
        if (!hasText(synopsis)) {
            synopsis = textOf(root, "intro");
        }
        List<String> tags = new ArrayList<>();
        JsonNode tagNode = root.path("tags");
        if (tagNode.isArray()) {
            tagNode.forEach(node -> {
                String tag = node.asText("").trim();
                if (!tag.isEmpty()) {
                    tags.add(tag);
                }
            });
        }
        if (!hasText(title) && !hasText(synopsis)) {
            return null;
        }
        return new ExtractedNovel(title, genre, tags, synopsis);
    }

    /** AI 不可用或输出不可解析时的兜底：用会话标题做书名，最后一段回复做简介。 */
    private ExtractedNovel fallbackExtraction(InspirationSessionEntity session, List<InspirationMessageEntity> messages) {
        String title = hasText(session.getTitle()) ? session.getTitle() : "未命名小说";
        String intro = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            InspirationMessageEntity item = messages.get(i);
            if ("ASSISTANT".equals(item.getRole()) && "OK".equals(item.getStatus()) && hasText(item.getContent())) {
                intro = truncate(item.getContent().trim(), 1200);
                break;
            }
        }
        if (!hasText(intro)) {
            StringBuilder builder = new StringBuilder();
            for (InspirationMessageEntity item : messages) {
                if ("USER".equals(item.getRole()) && hasText(item.getContent())) {
                    builder.append(item.getContent().trim()).append("\n");
                }
            }
            intro = truncate(builder.toString().trim(), 1200);
        }
        return new ExtractedNovel(title, null, List.of(), intro);
    }

    private SessionVO toSessionVO(InspirationSessionEntity session,
                                  List<InspirationMessageEntity> messages,
                                  InspirationSkillEntity skill) {
        SessionVO vo = new SessionVO();
        vo.setId(session.getId());
        vo.setTitle(session.getTitle());
        vo.setSkillCode(skill == null ? session.getSkillCode() : skill.getCode());
        vo.setSkillName(skill == null ? null : skill.getName());
        vo.setMessageCount(session.getMessageCount());
        vo.setLastMessageAt(session.getLastMessageAt());
        vo.setCreatedAt(session.getCreatedAt());
        if (messages != null && !messages.isEmpty()) {
            vo.setMessages(messages.stream().map(this::toMessageVO).toList());
        }
        return vo;
    }

    private MessageVO toMessageVO(InspirationMessageEntity entity) {
        MessageVO vo = new MessageVO();
        vo.setId(entity.getId());
        vo.setRole(entity.getRole());
        vo.setContent(entity.getContent());
        vo.setStatus(entity.getStatus());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setModel(entity.getModel());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /** 会话标题：取首条消息前若干字符，压掉换行。 */
    private String deriveTitle(String content) {
        String flat = content.replaceAll("\\s+", " ").trim();
        return truncate(flat, TITLE_MAX_LENGTH);
    }

    private String textOf(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 抽取到的小说要素。 */
    private record ExtractedNovel(String title, String genre, List<String> tags, String intro) {
    }
}
