package com.storyforge.modules.bookshelf.service;

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
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.ai.service.PromptTemplateService;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.MasterOutlineGenerateRequest;
import com.storyforge.modules.bookshelf.dto.MasterOutlineRequest;
import com.storyforge.modules.bookshelf.dto.MasterOutlineVO;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.SettingDocVO;
import com.storyforge.modules.bookshelf.dto.TimelineVO;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.MasterOutlineEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.MasterOutlineMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 总纲服务：一段粗粒度的剧情走向，用来指导章节大纲生成。
 *
 * <p>总纲只保存标题与一段文字描述，不关联人物 / 势力 / 物品；一条总纲可被多条章节大纲引用。
 * 章节大纲生成时若指定了总纲，会把它作为「不得脱离」的范围硬约束注入提示词。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MasterOutlineService {

    private static final int MAX_TOKENS = 6000;
    private static final int TIMEOUT_MS = 300_000;
    private static final int MAX_COUNT = 12;
    /** 资料充足且还没排章节时，一次覆盖全书的总纲条数 */
    private static final int FULL_COVER_COUNT = 5;
    /** 资料不足时只生成一部分 */
    private static final int PARTIAL_COUNT = 2;
    /** 每多少章对应一条总纲（已有章节时用于估算全书规模） */
    private static final int CHAPTERS_PER_OUTLINE = 5;
    private static final int MAX_CHARACTERS_IN_PROMPT = 20;
    private static final int MAX_EVENTS_IN_PROMPT = 20;
    private static final int MAX_TEXT_IN_PROMPT = 600;

    private final MasterOutlineMapper masterOutlineMapper;
    private final ChapterMapper chapterMapper;
    private final BookshelfService bookshelfService;
    private final NovelAssetService assetService;
    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;
    private final com.storyforge.modules.autowrite.support.AutoWriteGuard autoWriteGuard;

    /* ------------------------------------------------------------------ */
    /* 查询与维护                                                          */
    /* ------------------------------------------------------------------ */

    public List<MasterOutlineVO> list(Long novelId) {
        Map<Long, Integer> counts = chapterCounts(novelId);
        return entities(novelId).stream()
                .map(entity -> toVO(entity, counts.getOrDefault(entity.getId(), 0)))
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public MasterOutlineVO create(Long novelId, MasterOutlineRequest request) {
        if (request == null || !hasText(request.getTitle())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请填写总纲标题");
        }
        autoWriteGuard.assertEditable(novelId);
        MasterOutlineEntity entity = new MasterOutlineEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(novelId);
        entity.setTitle(request.getTitle().trim());
        entity.setContent(trimToNull(request.getContent()));
        entity.setSortNo(request.getSortNo() == null ? nextSortNo(novelId) : request.getSortNo());
        entity.setDeleted(0);
        entity.setVersion(0);
        masterOutlineMapper.insert(entity);
        return toVO(entity, 0);
    }

    @Transactional(rollbackFor = Exception.class)
    public MasterOutlineVO update(Long id, MasterOutlineRequest request) {
        MasterOutlineEntity entity = require(id);
        autoWriteGuard.assertEditable(entity.getNovelId());
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求内容不能为空");
        }
        if (request.getTitle() != null) {
            if (!hasText(request.getTitle())) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "总纲标题不能为空");
            }
            entity.setTitle(request.getTitle().trim());
        }
        if (request.getContent() != null) {
            // 空串而非 null：updateById 会跳过 null，导致「清空」失效
            entity.setContent(request.getContent().trim());
        }
        if (request.getSortNo() != null) {
            entity.setSortNo(request.getSortNo());
        }
        masterOutlineMapper.updateById(entity);
        return toVO(entity, chapterCounts(entity.getNovelId()).getOrDefault(id, 0));
    }

    /** 删除总纲，并把引用它的章节大纲解除关联（不会删除章节）。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        MasterOutlineEntity entity = require(id);
        autoWriteGuard.assertEditable(entity.getNovelId());
        chapterMapper.update(null, Wrappers.<ChapterEntity>lambdaUpdate()
                .set(ChapterEntity::getMasterOutlineId, null)
                .eq(ChapterEntity::getMasterOutlineId, id));
        masterOutlineMapper.deleteById(id);
        log.info("[master-outline] deleted id={} novelId={}", id, entity.getNovelId());
    }

    public MasterOutlineEntity require(Long id) {
        MasterOutlineEntity entity = id == null ? null : masterOutlineMapper.selectById(id);
        if (entity == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "总纲不存在");
        }
        return entity;
    }

    /* ------------------------------------------------------------------ */
    /* AI 生成                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * AI 生成总纲并追加落库。
     *
     * <p>条数由资料充足程度决定：世界观 / 背景 / 核心冲突齐备且角色不少于 3 个时，
     * 视为资料充足，按全书规模生成完整的总纲；否则只生成一部分，等设定补齐后再扩写。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public List<MasterOutlineVO> generate(Long novelId, MasterOutlineGenerateRequest request) {
        autoWriteGuard.assertEditable(novelId);
        NovelDetailVO detail = bookshelfService.detail(novelId);
        List<MasterOutlineEntity> existing = entities(novelId);
        List<TimelineVO> events = assetService.listTimeline(novelId);
        // 全书已有章节数（不是「已关联总纲的章节数」），用于估算全书总纲规模
        Long totalChapters = chapterMapper.selectCount(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId));
        int chapterCount = totalChapters == null ? 0 : totalChapters.intValue();
        // 「已写章节」只算有正文的章节：拆书生成的书章节已排满、正文为空，
        // 不能当成「已写完」，否则总纲会跳到这些大纲之后另起炉灶。
        Long writtenChapters = chapterMapper.selectCount(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .gt(ChapterEntity::getWordCount, 0));
        int writtenCount = writtenChapters == null ? 0 : writtenChapters.intValue();

        int count = decideCount(detail, existing.size(), chapterCount,
                request == null ? null : request.getCount());
        String requirement = request == null || !hasText(request.getPrompt())
                ? "" : request.getPrompt().trim();

        AiEndpoint base = endpointResolver.resolve(AiScene.MASTER_OUTLINE);
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS),
                base.extraHeaders(),
                0.85D,
                MAX_TOKENS);

        Map<String, String> vars = new java.util.LinkedHashMap<>();
        var novel = detail.getNovel();
        vars.put("novelTitle", novel == null || !hasText(novel.getTitle()) ? "这本书" : novel.getTitle());
        vars.put("genre", novel == null || !hasText(novel.getGenre()) ? "通用" : novel.getGenre());
        vars.put("count", String.valueOf(count));
        vars.put("coverage", writtenCount > 0
                ? "在此基础上继续往后规划，不要重复已写章节" : "全书主线");
        vars.put("authorRequirement", requirement);
        PromptVars.putNovel(vars, novel, MAX_TEXT_IN_PROMPT);
        PromptVars.putSettings(vars, detail, MAX_TEXT_IN_PROMPT, MAX_TEXT_IN_PROMPT,
                MAX_TEXT_IN_PROMPT, MAX_TEXT_IN_PROMPT, MAX_TEXT_IN_PROMPT);
        vars.put("constraints", PromptVars.constraint(detail, 1200));
        String system = promptTemplateService.system(AiScene.MASTER_OUTLINE, PromptVars.clean(vars));
        if (!hasText(system)) {
            system = "你是资深小说总策划，把整本书的剧情走向拆成若干条总纲：每条一段文字，只写剧情推进，不写人物档案。只输出 JSON。";
        }

        String userPrompt = buildPrompt(detail, existing, events, chapterCount, writtenCount, count,
                requirement, promptTemplateService.usedVariables(AiScene.MASTER_OUTLINE));
        JsonNode root = parse(chat(endpoint, system, userPrompt).content());

        JsonNode list = root.path("outlines");
        if (!list.isArray() || list.isEmpty()) {
            // 兼容模型直接返回单个对象的情况
            if (root.hasNonNull("title") || root.hasNonNull("content")) {
                list = objectMapper.createArrayNode().add(root);
            } else {
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型没有返回可用的总纲，请重试");
            }
        }

        int sortNo = existing.stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max().orElse(0);
        Set<String> titles = new LinkedHashSet<>();
        for (MasterOutlineEntity item : existing) {
            if (hasText(item.getTitle())) {
                titles.add(item.getTitle().trim());
            }
        }

        List<MasterOutlineVO> created = new ArrayList<>();
        for (JsonNode node : list) {
            String content = textOf(node, "content");
            String title = textOf(node, "title");
            if (!hasText(content)) {
                continue;
            }
            if (!hasText(title)) {
                title = abbreviate(content, 16);
            }
            if (!titles.add(title)) {
                // 与已有总纲重名，跳过，避免重复生成同一段
                continue;
            }
            MasterOutlineEntity entity = new MasterOutlineEntity();
            entity.setUserId(UserContextHolder.requireUserId());
            entity.setNovelId(novelId);
            entity.setTitle(truncate(title, 200));
            entity.setContent(content.trim());
            entity.setSortNo(++sortNo);
            entity.setDeleted(0);
            entity.setVersion(0);
            masterOutlineMapper.insert(entity);
            created.add(toVO(entity, 0));
        }
        if (created.isEmpty()) {
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的总纲都与已有内容重复，请调整要求后重试");
        }
        log.info("[master-outline] generated novelId={} count={} rich={}", novelId, created.size(),
                isRich(detail));
        return created;
    }

    /** 资料是否充足：世界观 / 背景 / 核心冲突齐备，且至少 3 个角色。 */
    private boolean isRich(NovelDetailVO detail) {
        if (detail == null) {
            return false;
        }
        Set<String> filled = new LinkedHashSet<>();
        for (SettingDocVO doc : detail.getSettings() == null ? List.<SettingDocVO>of() : detail.getSettings()) {
            if (doc != null && hasText(doc.getContent())) {
                filled.add(doc.getCategory());
            }
        }
        boolean settingsReady = filled.contains("WORLDVIEW")
                && filled.contains("BACKGROUND")
                && filled.contains("CORE_CONFLICT");
        int characters = detail.getCharacters() == null ? 0 : detail.getCharacters().size();
        return settingsReady && characters >= 3;
    }

    /** 决定本次生成条数：作者指定优先；否则资料充足按全书规模，资料不足只生成一部分。 */
    private int decideCount(NovelDetailVO detail, int existingCount, int chapterCount, Integer requested) {
        if (requested != null) {
            return Math.min(MAX_COUNT, Math.max(1, requested));
        }
        if (!isRich(detail)) {
            return PARTIAL_COUNT;
        }
        int total = chapterCount <= 0
                ? FULL_COVER_COUNT
                : Math.round(chapterCount / (float) CHAPTERS_PER_OUTLINE) + 1;
        total = Math.min(MAX_COUNT, Math.max(3, total));
        // 已生成的部分不重复覆盖：只补足剩余条数；已经覆盖全书时继续往后推进一段
        int remaining = total - existingCount;
        return Math.max(1, Math.min(remaining > 0 ? remaining : PARTIAL_COUNT, MAX_COUNT));
    }

    private String buildPrompt(NovelDetailVO detail,
                               List<MasterOutlineEntity> existing,
                               List<TimelineVO> events,
                               int chapterCount,
                               int writtenCount,
                               int count,
                               String requirement,
                               java.util.Set<String> used) {
        StringBuilder builder = new StringBuilder();
        var novel = detail.getNovel();
        builder.append("【作品】《").append(novel == null || !hasText(novel.getTitle())
                ? "未命名" : novel.getTitle()).append("》");
        if (novel != null && hasText(novel.getGenre())) {
            builder.append("｜题材：").append(novel.getGenre());
        }
        builder.append("\n");
        if (novel != null && hasText(novel.getIntro())) {
            builder.append("【简介】").append(truncate(novel.getIntro(), MAX_TEXT_IN_PROMPT)).append("\n");
        }

        StringBuilder settingsBlock = new StringBuilder();
        for (SettingDocVO doc : detail.getSettings() == null ? List.<SettingDocVO>of() : detail.getSettings()) {
            if (doc == null || !hasText(doc.getContent())) {
                continue;
            }
            if (!PromptVars.emit(used, PromptVars.variableOf(doc.getCategory()))) {
                // 这一类设定模板已经引用过了，不再重复
                continue;
            }
            settingsBlock.append("- ").append(hasText(doc.getLabel()) ? doc.getLabel() : doc.getCategory())
                    .append("：").append(truncate(doc.getContent(), MAX_TEXT_IN_PROMPT)).append("\n");
        }
        if (!settingsBlock.isEmpty()) {
            builder.append("\n【已定设定】\n").append(settingsBlock);
        }

        List<CharacterVO> characters = detail.getCharacters() == null ? List.of() : detail.getCharacters();
        if (!characters.isEmpty()) {
            builder.append("\n【关键人物】\n");
            int taken = 0;
            for (CharacterVO person : characters) {
                if (taken++ >= MAX_CHARACTERS_IN_PROMPT) {
                    break;
                }
                builder.append("- ").append(person.getName());
                if (hasText(person.getIdentity())) {
                    builder.append("：").append(truncate(person.getIdentity(), 80));
                }
                if (hasText(person.getMotivation())) {
                    builder.append("｜动机：").append(truncate(person.getMotivation(), 80));
                }
                builder.append("\n");
            }
        }

        if (events != null && !events.isEmpty()) {
            builder.append("\n【可用事件】\n");
            int taken = 0;
            for (TimelineVO event : events) {
                if (taken++ >= MAX_EVENTS_IN_PROMPT) {
                    break;
                }
                builder.append("- ").append(event.title());
                if (hasText(event.description())) {
                    builder.append("：").append(truncate(event.description(), 60));
                }
                builder.append("\n");
            }
        }

        if (!existing.isEmpty()) {
            builder.append("\n【已有总纲】（不要重复这些内容，接着往后规划）\n");
            for (MasterOutlineEntity item : existing) {
                builder.append("- ").append(item.getTitle()).append("\n");
            }
        }
        if (writtenCount > 0) {
            builder.append("\n【已写章节】共 ").append(writtenCount).append(" 章，新总纲要与其后剧情衔接。\n");
        } else if (chapterCount > 0) {
            // 只有章节大纲、没有正文（例如拆书生成的新书）：这套大纲已经排满全书，
            // 总纲要与它同源、覆盖全书走向，而不是接到它后面另起炉灶
            builder.append("\n【已有章节大纲】共 ").append(chapterCount).append(" 章，已覆盖全书走向：\n");
            Long outlineNovelId = novel == null ? null : novel.getId();
            if (outlineNovelId != null) {
                for (ChapterEntity chapter : outlineChapterTitles(outlineNovelId)) {
                    if (!hasText(chapter.getTitle())) {
                        continue;
                    }
                    builder.append("- 第").append(chapter.getChapterNo() == null ? "?" : chapter.getChapterNo())
                            .append("章 ").append(truncate(chapter.getTitle(), 40)).append("\n");
                }
            }
            builder.append("请把这套章节大纲归并成更有概括力的总纲，与上面的走向保持一致，不要重写或另起一条主线。\n");
        }

        PromptVars.appendConstraint(builder, detail, used, 1200);
        if (PromptVars.emit(used, "authorRequirement")) {
            builder.append("\n【本次要求】\n");
            builder.append(hasText(requirement) ? requirement
                    : "按现有设定把故事主线拆成有因果递进的总纲，覆盖开局到结局方向。").append("\n");
        }
        builder.append("\n请输出 ").append(count).append(" 条总纲。");
        return builder.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                            */
    /* ------------------------------------------------------------------ */

    private List<MasterOutlineEntity> entities(Long novelId) {
        return masterOutlineMapper.selectList(Wrappers.<MasterOutlineEntity>lambdaQuery()
                .eq(MasterOutlineEntity::getNovelId, novelId)
                .orderByAsc(MasterOutlineEntity::getSortNo)
                .orderByAsc(MasterOutlineEntity::getId));
    }

    /** 只有章节大纲、没有正文时，取前 40 章标题帮模型对齐走向。 */
    private List<ChapterEntity> outlineChapterTitles(Long novelId) {
        return chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .select(ChapterEntity::getChapterNo, ChapterEntity::getTitle)
                .eq(ChapterEntity::getNovelId, novelId)
                .orderByAsc(ChapterEntity::getChapterNo)
                .last("limit 40"));
    }

    /** 统计每条总纲被多少条章节大纲引用。 */
    private Map<Long, Integer> chapterCounts(Long novelId) {
        List<ChapterEntity> chapters = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .select(ChapterEntity::getId, ChapterEntity::getMasterOutlineId)
                .eq(ChapterEntity::getNovelId, novelId)
                .isNotNull(ChapterEntity::getMasterOutlineId));
        Map<Long, Integer> counts = new HashMap<>();
        for (ChapterEntity chapter : chapters) {
            Long key = chapter.getMasterOutlineId();
            if (key != null) {
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    private int nextSortNo(Long novelId) {
        return entities(novelId).stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max().orElse(0) + 1;
    }

    private MasterOutlineVO toVO(MasterOutlineEntity entity, int chapterCount) {
        return new MasterOutlineVO(entity.getId(), entity.getSortNo(), entity.getTitle(),
                entity.getContent(), chapterCount, entity.getUpdatedAt());
    }

    private ChatResult chat(AiEndpoint endpoint, String systemPrompt, String userPrompt) {
        try {
            return aiUsageService.chat(AiScene.MASTER_OUTLINE, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("[master-outline] chat failed: {}", ex.toString());
            throw BizException.of(ErrorCode.AI_CALL_FAILED,
                    hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败");
        }
    }

    private JsonNode parse(String content) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[master-outline] 模型未返回 JSON：{}", abbreviate(content, 200));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型没有返回结构化总纲，请重试");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[master-outline] parse failed: {} | raw={}",
                        strictFailure.getMessage(), abbreviate(candidate, 200));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的总纲无法解析，请重试");
            }
        }
    }

    private String textOf(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String abbreviate(String value, int max) {
        return truncate(value == null ? "" : value.replaceAll("\\s+", " "), max);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
