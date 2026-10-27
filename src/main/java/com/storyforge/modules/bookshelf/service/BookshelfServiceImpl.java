package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.infra.ai.PromptKit;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.bookshelf.dto.BlueprintRequest;
import com.storyforge.modules.bookshelf.dto.BlueprintResultVO;
import com.storyforge.modules.bookshelf.dto.CharacterDraft;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.NovelBlueprint;
import com.storyforge.modules.bookshelf.dto.NovelCreateRequest;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.NovelEditRequest;
import com.storyforge.modules.bookshelf.dto.SettingDocVO;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterCharacterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEventEntity;
import com.storyforge.modules.bookshelf.entity.SettingDocEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterRelationEntity;
import com.storyforge.modules.bookshelf.entity.EventParticipantEntity;
import com.storyforge.modules.bookshelf.entity.FactionEntity;
import com.storyforge.modules.bookshelf.entity.FactionMemberEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import com.storyforge.modules.bookshelf.entity.TimelineEventEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterAnalysisMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterAttributeMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterInventoryMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.ChangeHistoryMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterCharacterMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterEventMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterRelationMapper;
import com.storyforge.modules.bookshelf.mapper.EventParticipantMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMemberMapper;
import com.storyforge.modules.bookshelf.mapper.ItemMapper;
import com.storyforge.modules.bookshelf.mapper.SettingDocMapper;
import com.storyforge.modules.bookshelf.mapper.TimelineEventMapper;
import com.storyforge.modules.novel.dto.NovelDraft;
import com.storyforge.modules.novel.dto.NovelPatch;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.novel.service.NovelCoverService;
import com.storyforge.modules.novel.service.NovelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书架服务实现。
 *
 * <p>核心流程：用户填必要信息 → {@link #generateBlueprint} 调用 AI 生成结构化蓝图 →
 * 用户在前端预览修改 → {@link #create} 落库为小说 + 设定 + 角色。</p>
 *
 * <p>AI 输出不可信，因此解析阶段做了三重防护：宽松提取 JSON、字段长度截断、缺失项兜底。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookshelfServiceImpl implements BookshelfService {

    /* ---------- 设定分类（顺序即展示顺序） ---------- */
    private static final String CATEGORY_WORLDVIEW = "WORLDVIEW";
    private static final String CATEGORY_BACKGROUND = "BACKGROUND";
    private static final String CATEGORY_CORE_CONFLICT = "CORE_CONFLICT";
    private static final String CATEGORY_HIGHLIGHT = "HIGHLIGHT";
    private static final String CATEGORY_STYLE = "STYLE";

    private static final Map<String, String> CATEGORY_LABELS = createCategoryLabels();

    private static final int MAX_TAGS = 8;
    private static final int MAX_TAG_LENGTH = 24;
    private static final int MAX_CHARACTERS = 12;
    /** 蓝图生成需要更长输出与更长超时（单次生成上千字） */
    private static final int GENERATION_MAX_TOKENS = 4000;
    private static final double GENERATION_TEMPERATURE = 0.85D;
    /** 单次生成（旧接口）与分段任务保持一致：5 分钟超时。 */
    private static final int GENERATION_MIN_TIMEOUT_MS = 300_000;

    private static final String SYSTEM_PROMPT = """
            你是资深网络小说策划，擅长把一个核心创意发展成能直接开写的故事方案。
            你不是在写百科：世界观必须能解释普通人的日常，人物必须能自己制造冲突。
            只输出一个 JSON 对象，不要解释文字，不要使用 markdown 代码块。
            字符串值里绝对不要出现英文双引号（"），需要引用时一律使用「」，也不要出现真实换行。
            """;

    /** 首次解析失败后的重试提示：强调 JSON 合法性与引号规则。 */
    private static final String RETRY_SYSTEM_PROMPT = SYSTEM_PROMPT + """
            上一次的输出不是合法 JSON（多半是字符串内混入了英文双引号），请重新生成。
            这次务必保证：可以被 JSON 解析器直接解析；值内不出现英文双引号与换行；只输出 JSON 对象本身。
            """;

    private static final String OUTPUT_SCHEMA = """
            【输出格式】
            只输出一个 JSON 对象，字段如下，所有字段都必须有值，不要输出 null：
            {
              "title": "书名，不超过 20 字",
              "genre": "题材，2~6 字，如 玄幻 / 都市 / 悬疑",
              "tags": ["3~6 个标签，每个不超过 8 字"],
              "synopsis": "简介，250~400 字：主角是谁 → 想要什么 → 谁挡着他 → 失败会失去什么 → 结尾钩子",
              "worldview": "世界观，600~900 字：核心规则 → 代价与边界 → 社会结构 → 势力格局 → 3 个日常细节 → 当前暗流",
              "background": "背景设定，400~600 字：三个关键历史事件（起因→经过→后果）→ 当前局势 → 一条尚未爆发的压力",
              "coreConflict": "核心冲突，150~250 字：双方目标、底线、升级方式、最终要回答的问题",
              "highlights": ["3~5 条核心看点，每条不超过 20 字，必须是具体桥段或设定"],
              "style": "人称 + 叙述距离 + 语言质感，不超过 40 字",
              "protagonist": {
                "name": "姓名",
                "identity": "身份职位",
                "gender": "性别",
                "ageDesc": "年龄描述",
                "appearance": "外貌特征，60~100 字：一个能被记住的特征 + 当前身体或穿着状态",
                "personality": "性格，80~120 字：主导性格 + 一个矛盾点 + 压力下的反应",
                "motivation": "核心动机，80~120 字：表面想要什么 / 真正缺什么 / 最怕失去什么",
                "goldenFinger": "金手指或独有优势，80~120 字：能力 + 代价 + 限制 + 暴露风险",
                "arc": "成长弧线，80~120 字：初始相信的谎言 → 被什么打破 → 最终选择"
              },
              "characters": [
                {
                  "name": "姓名，不要与主角重复",
                  "rolePos": "SUPPORTING 或 ANTAGONIST",
                  "identity": "身份职位",
                  "gender": "性别",
                  "ageDesc": "年龄描述",
                  "personality": "性格，60~100 字，含一个矛盾点或说话习惯",
                  "motivation": "动机，60~100 字，含欲望与顾忌"
                }
              ]
            }

            【硬性要求】
            1. characters 给出 3~6 个与主线强相关的角色，至少包含 1 个主要对手（ANTAGONIST），对手的动机必须让读者理解。
            2. 所有设定必须与作者给的核心创意一致，不要写成章节大纲，不要堆砌名词。
            3. 世界观要能解释力量从哪来、代价是什么、社会如何运转；至少 3 个普通人日常能被规则影响的细节。
            4. 主角要具体可感：有欲望、有缺陷、有秘密、有对手，避免「天赋异禀、天下无敌」的空话。
            5. 每个字段值都是单行纯文本：不要出现英文双引号、不要换行、不要以 - 或 * 开头；引用他人话语用「」。
            """ + PromptKit.GENRE_GUIDE + PromptKit.ANTI_CLICHE;

    private final SettingDocMapper settingDocMapper;
    private final CharacterMapper characterMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterAnalysisMapper chapterAnalysisMapper;
    private final ChangeHistoryMapper changeHistoryMapper;
    private final ChapterCharacterMapper chapterCharacterMapper;
    private final ChapterEventMapper chapterEventMapper;
    private final CharacterRelationMapper characterRelationMapper;
    private final EventParticipantMapper eventParticipantMapper;
    private final FactionMemberMapper factionMemberMapper;
    private final FactionMapper factionMapper;
    private final TimelineEventMapper timelineEventMapper;
    private final ItemMapper itemMapper;
    private final CharacterAttributeMapper characterAttributeMapper;
    private final CharacterInventoryMapper characterInventoryMapper;
    private final NovelService novelService;
    private final NovelCoverService novelCoverService;
    private final AiSceneEndpointResolver endpointResolver;
    private final ObjectMapper objectMapper;
    private final AiUsageService aiUsageService;

    /* ------------------------------------------------------------------ */
    /* 1. 生成蓝图                                                        */
    /* ------------------------------------------------------------------ */

    @Override
    @Deprecated
    public BlueprintResultVO generateBlueprint(BlueprintRequest request) {
        Long userId = UserContextHolder.requireUserId();
        BlueprintRequest condition = request == null ? new BlueprintRequest() : request;
        if (!hasText(condition.getIdea())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先填写核心创意");
        }

        String prompt = buildPrompt(condition);
        // 旧的一次性整包生成已由 BlueprintTaskService 四段任务取代（前端不再调用）。
        // 场景码拆分后这里沿用第三段（简介与冲突）的端点，仅为兼容保留。
        AiEndpoint base = endpointResolver.resolve(AiScene.BLUEPRINT_CONFLICT);
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), GENERATION_MIN_TIMEOUT_MS),
                base.extraHeaders(),
                GENERATION_TEMPERATURE,
                GENERATION_MAX_TOKENS);

        ChatResult reply;
        try {
            reply = aiUsageService.chat(AiScene.BLUEPRINT_CONFLICT, endpoint, List.of(
                    ChatMessage.system(SYSTEM_PROMPT),
                    ChatMessage.user(prompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String reason = hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败";
            log.warn("[bookshelf] blueprint failed userId={}: {}", userId, ex.toString());
            throw BizException.of(ErrorCode.AI_CALL_FAILED, reason);
        }

        JsonNode root;
        try {
            root = readBlueprintJson(reply.content());
        } catch (BizException parseFailure) {
            // 模型偶发输出非法 JSON：自动重试一次，避免让用户手动重试
            log.warn("[bookshelf] blueprint parse failed, retry once userId={} reason={}", userId, parseFailure.getMessage());
            ChatResult retry;
            try {
                retry = aiUsageService.chat(AiScene.BLUEPRINT_CONFLICT, endpoint, List.of(
                        ChatMessage.system(RETRY_SYSTEM_PROMPT),
                        ChatMessage.user(prompt)));
            } catch (BizException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                String reason = hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败";
                log.warn("[bookshelf] blueprint retry failed userId={}: {}", userId, ex.toString());
                throw BizException.of(ErrorCode.AI_CALL_FAILED, reason);
            }
            root = readBlueprintJson(retry.content());
            reply = retry;
        }

        NovelBlueprint blueprint = toBlueprint(root);
        // 用户显式填写的项优先，避免模型自行改写
        if (hasText(condition.getTitle())) {
            blueprint.setTitle(condition.getTitle().trim());
        }
        if (hasText(condition.getGenre())) {
            blueprint.setGenre(condition.getGenre().trim());
        }
        if (condition.getTags() != null && !condition.getTags().isEmpty()) {
            blueprint.setTags(normalizeTags(condition.getTags()));
        }

        BlueprintResultVO result = new BlueprintResultVO();
        result.setBlueprint(blueprint);
        result.setModel(reply.model());
        result.setLatencyMs(reply.latencyMs());
        log.info("[bookshelf] blueprint generated userId={} model={} latency={}ms",
                userId, reply.model(), reply.latencyMs());
        return result;
    }

    /** 把用户输入拼成提示词。缺省项不写，交给模型推断。 */
    private String buildPrompt(BlueprintRequest request) {
        StringBuilder builder = new StringBuilder();
        builder.append("【作者信息】\n");
        appendLine(builder, "书名", request.getTitle());
        appendLine(builder, "题材", request.getGenre());
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            appendLine(builder, "标签", String.join("、", normalizeTags(request.getTags())));
        }
        appendLine(builder, "核心创意", request.getIdea());
        appendLine(builder, "风格偏好", request.getStyle());
        appendLine(builder, "目标篇幅", request.getTargetLength());
        appendLine(builder, "主角偏好", request.getProtagonistHint());
        builder.append("\n").append(OUTPUT_SCHEMA);
        return builder.toString();
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (hasText(value)) {
            builder.append(label).append("：").append(value.trim()).append("\n");
        }
    }

    /* ------------------------------------------------------------------ */
    /* 2. 创建小说                                                        */
    /* ------------------------------------------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NovelVO create(NovelCreateRequest request) {
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求内容不能为空");
        }
        NovelBlueprint blueprint = request.getBlueprint();

        String title = firstText(request.getTitle(), blueprint == null ? null : blueprint.getTitle());
        if (!hasText(title)) {
            throw BizException.of(ErrorCode.NOVEL_TITLE_EMPTY);
        }
        String genre = firstText(request.getGenre(), blueprint == null ? null : blueprint.getGenre());
        List<String> tags = request.getTags() != null && !request.getTags().isEmpty()
                ? request.getTags()
                : (blueprint == null ? List.of() : blueprint.getTags());
        String intro = firstText(request.getIntro(), blueprint == null ? null : blueprint.getSynopsis());

        NovelVO novel = novelService.createDraft(new NovelDraft(
                title,
                genre,
                tags,
                intro,
                request.getPenName(),
                "MANUAL",
                null));

        String status = request.getStatus();
        if (hasText(status) && !"DRAFT".equalsIgnoreCase(status.trim())) {
            novel = novelService.update(novel.getId(),
                    new NovelPatch(null, null, null, null, null, status.trim().toUpperCase(), null));
        }
        if (blueprint != null) {
            saveProfile(novel.getId(), blueprint);
            // 创作约束是本子后续所有生成的依据，建书时就落库，不必再走一次编辑
            if (hasText(blueprint.getConstraintText())) {
                String constraint = blueprint.getConstraintText().trim();
                novelService.updateConstraint(novel.getId(), constraint);
                novel.setConstraintText(constraint);
            }
        }
        log.info("[bookshelf] novel created id={} userId={} title={} withProfile={}",
                novel.getId(), UserContextHolder.requireUserId(), novel.getTitle(), blueprint != null);
        return novel;
    }

    /* ------------------------------------------------------------------ */
    /* 3. 详情 / 编辑 / 删除                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public NovelDetailVO detail(Long id) {
        NovelVO novel = novelService.detail(id);

        NovelDetailVO detail = new NovelDetailVO();
        detail.setNovel(novel);

        List<SettingDocEntity> docs = settingDocMapper.selectList(
                Wrappers.<SettingDocEntity>lambdaQuery()
                        .eq(SettingDocEntity::getNovelId, id)
                        .orderByAsc(SettingDocEntity::getSort));
        List<SettingDocVO> settings = new ArrayList<>();
        for (SettingDocEntity doc : docs) {
            if (!hasText(doc.getContentMd())) {
                continue;
            }
            SettingDocVO vo = new SettingDocVO();
            vo.setCategory(doc.getCategory());
            vo.setLabel(CATEGORY_LABELS.getOrDefault(doc.getCategory(), doc.getTitle()));
            vo.setContent(doc.getContentMd());
            settings.add(vo);
        }
        detail.setSettings(settings);

        List<CharacterEntity> characters = characterMapper.selectList(
                Wrappers.<CharacterEntity>lambdaQuery()
                        .eq(CharacterEntity::getNovelId, id)
                        .orderByAsc(CharacterEntity::getSortNo)
                        .orderByAsc(CharacterEntity::getId));
        detail.setCharacters(characters.stream().map(this::toCharacterVO).toList());
        return detail;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NovelVO update(Long id, NovelEditRequest request) {
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求内容不能为空");
        }
        NovelVO novel = novelService.update(id, new NovelPatch(
                request.getTitle(),
                request.getPenName(),
                request.getGenre(),
                request.getTags(),
                request.getIntro(),
                request.getStatus(),
                request.getPlannedWords()));
        if (request.getBlueprint() != null) {
            saveProfile(id, request.getBlueprint());
            // 编辑时带上蓝图即整体覆盖：约束给了空串就是「清空」，不给 null 才是不改
            String constraint = request.getBlueprint().getConstraintText();
            if (constraint != null) {
                String text = constraint.trim();
                novelService.updateConstraint(id, text);
                novel.setConstraintText(text);
            }
        }
        return novel;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        // 先删从属数据（设定 / 角色 / 章节 / 时间线 / 物品），再删小说；任一步失败整体回滚
        novelService.detail(id);
        settingDocMapper.delete(Wrappers.<SettingDocEntity>lambdaQuery()
                .eq(SettingDocEntity::getNovelId, id));
        characterRelationMapper.delete(Wrappers.<CharacterRelationEntity>lambdaQuery()
                .eq(CharacterRelationEntity::getNovelId, id));
        characterAttributeMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity::getNovelId, id));
        characterInventoryMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getNovelId, id));
        characterMapper.delete(Wrappers.<CharacterEntity>lambdaQuery()
                .eq(CharacterEntity::getNovelId, id));
        eventParticipantMapper.delete(Wrappers.<EventParticipantEntity>lambdaQuery()
                .eq(EventParticipantEntity::getNovelId, id));
        factionMemberMapper.delete(Wrappers.<FactionMemberEntity>lambdaQuery()
                .eq(FactionMemberEntity::getNovelId, id));
        factionMapper.delete(Wrappers.<FactionEntity>lambdaQuery()
                .eq(FactionEntity::getNovelId, id));
        chapterCharacterMapper.delete(Wrappers.<ChapterCharacterEntity>lambdaQuery()
                .eq(ChapterCharacterEntity::getNovelId, id));
        chapterEventMapper.delete(Wrappers.<ChapterEventEntity>lambdaQuery()
                .eq(ChapterEventEntity::getNovelId, id));
        chapterAnalysisMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.ChapterAnalysisEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.ChapterAnalysisEntity::getNovelId, id));
        changeHistoryMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity::getNovelId, id));
        chapterMapper.delete(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, id));
        timelineEventMapper.delete(Wrappers.<TimelineEventEntity>lambdaQuery()
                .eq(TimelineEventEntity::getNovelId, id));
        itemMapper.delete(Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getNovelId, id));
        // 封面：先删库表记录与图片目录，再删小说本体（顺序反了就拿不到 user_id 对应的目录了）
        novelCoverService.deleteAllForNovel(UserContextHolder.requireUserId(), id);
        novelService.delete(id);
        log.info("[bookshelf] novel deleted id={} userId={}", id, UserContextHolder.requireUserId());
    }

    /* ------------------------------------------------------------------ */
    /* 设定落库                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 整体覆盖一部小说的设定与角色。
     *
     * <p>先逻辑删除旧数据再插入，保证「重新生成」不会残留上一版内容；
     * 空内容不落库，因此用户清空某个分类即等于删除该设定。</p>
     */
    private void saveProfile(Long novelId, NovelBlueprint blueprint) {
        Long userId = UserContextHolder.requireUserId();
        settingDocMapper.delete(Wrappers.<SettingDocEntity>lambdaQuery()
                .eq(SettingDocEntity::getNovelId, novelId));
        // 角色会被整批重建，旧的章节人物关联随之失效，一并清理，避免脏数据
        chapterCharacterMapper.delete(Wrappers.<ChapterCharacterEntity>lambdaQuery()
                .eq(ChapterCharacterEntity::getNovelId, novelId));
        // 角色整批重建：属性与背包挂在旧角色上，必须一起清掉，否则会残留脏数据
        characterAttributeMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity::getNovelId, novelId));
        characterInventoryMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getNovelId, novelId));
        characterMapper.delete(Wrappers.<CharacterEntity>lambdaQuery()
                .eq(CharacterEntity::getNovelId, novelId));

        int sort = 0;
        sort = insertSetting(novelId, userId, CATEGORY_WORLDVIEW, blueprint.getWorldview(), sort);
        sort = insertSetting(novelId, userId, CATEGORY_BACKGROUND, blueprint.getBackground(), sort);
        sort = insertSetting(novelId, userId, CATEGORY_CORE_CONFLICT, blueprint.getCoreConflict(), sort);
        sort = insertSetting(novelId, userId, CATEGORY_HIGHLIGHT,
                joinLines(blueprint.getHighlights()), sort);
        insertSetting(novelId, userId, CATEGORY_STYLE, blueprint.getStyle(), sort);

        int index = 0;
        CharacterDraft protagonist = blueprint.getProtagonist();
        if (protagonist != null) {
            protagonist.setRolePos("PROTAGONIST");
            insertCharacter(novelId, userId, protagonist, index++);
        }
        List<CharacterDraft> others = blueprint.getCharacters();
        if (others != null) {
            for (CharacterDraft item : others) {
                if (item == null || !hasText(item.getName())) {
                    continue;
                }
                if (index >= MAX_CHARACTERS) {
                    break;
                }
                insertCharacter(novelId, userId, item, index++);
            }
        }
    }

    private int insertSetting(Long novelId, Long userId, String category, String content, int sort) {
        String text = content == null ? null : content.trim();
        if (!hasText(text)) {
            return sort;
        }
        SettingDocEntity entity = new SettingDocEntity();
        entity.setUserId(userId);
        entity.setNovelId(novelId);
        entity.setCategory(category);
        entity.setTitle(CATEGORY_LABELS.getOrDefault(category, category));
        entity.setContentMd(text);
        entity.setInjectScope("GLOBAL");
        entity.setSort(sort);
        entity.setDeleted(0);
        entity.setVersion(0);
        settingDocMapper.insert(entity);
        return sort + 1;
    }

    private void insertCharacter(Long novelId, Long userId, CharacterDraft draft, int sort) {
        CharacterEntity entity = new CharacterEntity();
        entity.setUserId(userId);
        entity.setNovelId(novelId);
        entity.setName(truncate(draft.getName(), 64));
        entity.setIdentity(truncate(draft.getIdentity(), 255));
        entity.setGender(truncate(draft.getGender(), 16));
        entity.setAgeDesc(truncate(draft.getAgeDesc(), 32));
        entity.setRolePos(normalizeRole(draft.getRolePos(), "PROTAGONIST".equals(draft.getRolePos())));
        entity.setLifeStatus("ALIVE");
        entity.setProfileJson(writeProfileJson(draft));
        entity.setSortNo(sort);
        entity.setDeleted(0);
        entity.setVersion(0);
        characterMapper.insert(entity);
    }

    /** 角色自由设定序列化；全空时返回 null，避免存一堆空串。 */
    private String writeProfileJson(CharacterDraft draft) {
        Map<String, String> profile = new LinkedHashMap<>();
        putIfText(profile, "appearance", draft.getAppearance());
        putIfText(profile, "personality", draft.getPersonality());
        putIfText(profile, "motivation", draft.getMotivation());
        putIfText(profile, "goldenFinger", draft.getGoldenFinger());
        putIfText(profile, "arc", draft.getArc());
        if (profile.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (Exception ex) {
            log.warn("[bookshelf] serialize profile failed: {}", ex.getMessage());
            return null;
        }
    }

    private void putIfText(Map<String, String> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private CharacterVO toCharacterVO(CharacterEntity entity) {
        CharacterVO vo = new CharacterVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setRolePos(entity.getRolePos());
        vo.setRoleLabel(roleLabel(entity.getRolePos()));
        vo.setGender(entity.getGender());
        vo.setAgeDesc(entity.getAgeDesc());
        vo.setIdentity(entity.getIdentity());

        Map<String, String> profile = readProfileJson(entity.getProfileJson());
        vo.setAppearance(profile.get("appearance"));
        vo.setPersonality(profile.get("personality"));
        vo.setMotivation(profile.get("motivation"));
        vo.setGoldenFinger(profile.get("goldenFinger"));
        vo.setArc(profile.get("arc"));
        return vo;
    }

    private Map<String, String> readProfileJson(String raw) {
        if (!hasText(raw)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception ex) {
            log.warn("[bookshelf] parse profile failed: {}", ex.getMessage());
            return Map.of();
        }
    }

    /* ------------------------------------------------------------------ */
    /* AI 输出解析                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * 宽松解析模型输出：剥离 markdown 围栏与前后废话，先严格解析，
     * 失败再走 {@link LenientJson#repair} 修复（值内英文双引号、裸换行、尾逗号等）。
     */
    private JsonNode readBlueprintJson(String raw) {
        String candidate = LenientJson.extractObject(raw);
        if (candidate.isEmpty()) {
            log.warn("[bookshelf] 模型未返回 JSON 对象，原始输出：{}", abbreviate(raw, 1200));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型没有返回结构化设定，请重试");
        }

        String strictMessage = null;
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception ex) {
            strictMessage = ex.getMessage();
        }
        try {
            JsonNode repaired = objectMapper.readTree(LenientJson.repair(candidate));
            log.info("[bookshelf] JSON 修复后解析成功，原始错误：{}", strictMessage);
            return repaired;
        } catch (Exception ex) {
            log.warn("[bookshelf] parse blueprint failed: {} | repaired: {}", strictMessage, ex.getMessage());
            log.warn("[bookshelf] 原始输出片段：{}", abbreviate(candidate, 1200));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的设定无法解析，请重试");
        }
    }

    private NovelBlueprint toBlueprint(JsonNode root) {
        NovelBlueprint blueprint = new NovelBlueprint();
        blueprint.setTitle(truncate(textOf(root, "title"), 100));
        blueprint.setGenre(truncate(textOf(root, "genre"), 64));
        blueprint.setTags(normalizeTags(textList(root, "tags")));
        blueprint.setSynopsis(truncate(textOf(root, "synopsis"), 4000));
        blueprint.setWorldview(truncate(textOf(root, "worldview"), 8000));
        blueprint.setBackground(truncate(textOf(root, "background"), 6000));
        blueprint.setCoreConflict(truncate(textOf(root, "coreConflict"), 3000));
        blueprint.setHighlights(normalizeTags(textList(root, "highlights")));
        blueprint.setStyle(truncate(textOf(root, "style"), 200));

        JsonNode protagonistNode = root.path("protagonist");
        if (protagonistNode.isObject()) {
            blueprint.setProtagonist(readCharacter(protagonistNode, true));
        }

        List<CharacterDraft> characters = new ArrayList<>();
        JsonNode listNode = root.path("characters");
        if (!listNode.isArray()) {
            listNode = root.path("supportingCharacters");
        }
        if (listNode.isArray()) {
            listNode.forEach(node -> {
                if (node.isObject() && characters.size() < MAX_CHARACTERS) {
                    CharacterDraft draft = readCharacter(node, false);
                    if (hasText(draft.getName())) {
                        characters.add(draft);
                    }
                }
            });
        }
        blueprint.setCharacters(characters);

        boolean contentMissing = !hasText(blueprint.getSynopsis())
                && !hasText(blueprint.getWorldview())
                && !hasText(blueprint.getBackground());
        if (contentMissing) {
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的设定缺少关键内容，请重试");
        }
        if (!hasText(blueprint.getTitle())) {
            blueprint.setTitle("未命名小说");
        }
        return blueprint;
    }

    private CharacterDraft readCharacter(JsonNode node, boolean protagonist) {
        CharacterDraft draft = new CharacterDraft();
        draft.setName(truncate(textOf(node, "name"), 64));
        draft.setIdentity(truncate(textOf(node, "identity"), 255));
        draft.setGender(truncate(textOf(node, "gender"), 16));
        draft.setAgeDesc(truncate(textOf(node, "ageDesc"), 32));
        draft.setAppearance(truncate(textOf(node, "appearance"), 2000));
        draft.setPersonality(truncate(textOf(node, "personality"), 2000));
        draft.setMotivation(truncate(textOf(node, "motivation"), 2000));
        draft.setGoldenFinger(truncate(textOf(node, "goldenFinger"), 2000));
        draft.setArc(truncate(textOf(node, "arc"), 2000));
        draft.setRolePos(protagonist ? "PROTAGONIST" : normalizeRole(textOf(node, "rolePos"), false));
        return draft;
    }

    private String normalizeRole(String raw, boolean protagonist) {
        if (protagonist) {
            return "PROTAGONIST";
        }
        String value = raw == null ? "" : raw.trim().toUpperCase();
        if (value.contains("PROTAG") || value.contains("主角")) {
            return "PROTAGONIST";
        }
        if (value.contains("ANTAG") || value.contains("反派") || value.contains("对手") || value.contains("BOSS")) {
            return "ANTAGONIST";
        }
        if (value.contains("MINOR") || value.contains("龙套") || value.contains("路人")) {
            return "MINOR";
        }
        return "SUPPORTING";
    }

    private String roleLabel(String rolePos) {
        if (rolePos == null) {
            return "角色";
        }
        return switch (rolePos) {
            case "PROTAGONIST" -> "主角";
            case "ANTAGONIST" -> "反派";
            case "MINOR" -> "龙套";
            default -> "配角";
        };
    }

    private String textOf(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private List<String> textList(JsonNode node, String field) {
        JsonNode value = node.path(field);
        List<String> items = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(item -> {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    items.add(text);
                }
            });
        } else if (value.isTextual()) {
            for (String part : value.asText("").split("[\\n,，、;；]")) {
                String text = part.trim();
                if (!text.isEmpty()) {
                    items.add(text);
                }
            }
        }
        return items;
    }

    private List<String> normalizeTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String item : raw) {
            if (!hasText(item)) {
                continue;
            }
            String tag = item.trim().replaceAll("^[#\\-*\\s]+", "");
            if (tag.isEmpty() || result.contains(tag)) {
                continue;
            }
            result.add(truncate(tag, MAX_TAG_LENGTH));
            if (result.size() >= MAX_TAGS) {
                break;
            }
        }
        return result;
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                               */
    /* ------------------------------------------------------------------ */

    private static Map<String, String> createCategoryLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(CATEGORY_WORLDVIEW, "世界观");
        labels.put(CATEGORY_BACKGROUND, "背景设定");
        labels.put(CATEGORY_CORE_CONFLICT, "核心冲突");
        labels.put(CATEGORY_HIGHLIGHT, "核心看点");
        labels.put(CATEGORY_STYLE, "文风基调");
        return labels;
    }

    private String joinLines(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (String item : items) {
            if (!hasText(item)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n");
            }
            builder.append(item.trim());
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private String firstText(String first, String second) {
        if (hasText(first)) {
            return first.trim();
        }
        return hasText(second) ? second.trim() : null;
    }

    private String truncate(String value, int max) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 日志用：截断过长的模型原始输出，避免刷屏。 */
    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "...(" + text.length() + " chars)";
    }
}
