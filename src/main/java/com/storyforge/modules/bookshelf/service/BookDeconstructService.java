package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.api.PageQuery;
import com.storyforge.common.api.PageResult;
import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.infra.text.NovelTextParser;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.bookshelf.dto.CharacterDraft;
import com.storyforge.modules.bookshelf.dto.DeconstructResult;
import com.storyforge.modules.bookshelf.dto.DeconstructSummaryVO;
import com.storyforge.modules.bookshelf.dto.DeconstructTaskVO;
import com.storyforge.modules.bookshelf.entity.BookDigestEntity;
import com.storyforge.modules.bookshelf.entity.ChapterCharacterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEventEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterRelationEntity;
import com.storyforge.modules.bookshelf.entity.EventParticipantEntity;
import com.storyforge.modules.bookshelf.entity.FactionEntity;
import com.storyforge.modules.bookshelf.entity.FactionMemberEntity;
import com.storyforge.modules.bookshelf.entity.SettingDocEntity;
import com.storyforge.modules.bookshelf.entity.TimelineEventEntity;
import com.storyforge.modules.bookshelf.mapper.BookDigestMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterCharacterMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterEventMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterRelationMapper;
import com.storyforge.modules.bookshelf.mapper.EventParticipantMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMemberMapper;
import com.storyforge.modules.bookshelf.mapper.SettingDocMapper;
import com.storyforge.modules.bookshelf.mapper.TimelineEventMapper;
import com.storyforge.modules.novel.dto.NovelDraft;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import com.storyforge.modules.novel.service.NovelService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 拆书：上传一部已完成的小说，AI 拆出主线脉络，再按改编幅度重建一部新作（不含正文）。
 *
 * <p>流程六步：</p>
 * <ol>
 *   <li>通读：原文按章节切成 7000~9000 字的段，逐段做事实提炼（原文超长时头/中/尾抽样）。</li>
 *   <li>主线：把分段纪要汇成「一句话内核 + 分幕 + 关键转折 + 人物功能表 + 结局」。</li>
 *   <li>设定：按改编程度与背景方向重建书名、世界观、背景、冲突、看点、文风。</li>
 *   <li>人物：彻底脱敏地重建人物、关系与势力，原文姓名进入禁用清单。</li>
 *   <li>大纲：重排事件池与章节大纲（只写标题与摘要，不写正文）。</li>
 *   <li>整理：清洗引用、校验人物/事件关联并落库。</li>
 * </ol>
 *
 * <p>任务进度写库（{@code t_book_digest}），前端轮询读库；服务重启后残留的 RUNNING 任务
 * 由 {@link #markInterrupted()} 统一标记为失败。原文本体不落库，只留文件名与字数。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookDeconstructService {

    /** 步骤总数，与前端步骤条一致。 */
    public static final int STEP_TOTAL = 6;

    /** 单次 AI 调用超时：5 分钟。 */
    private static final int TIMEOUT_MS = 300_000;

    private static final int MIN_CHARS = 3_000;
    private static final int MAX_CHARS = 5_000_000;
    private static final int MIN_CHAPTER_TARGET = 8;
    private static final int MAX_CHAPTER_TARGET = 24;
    private static final int DEFAULT_CHAPTER_TARGET = 16;
    private static final int MAX_CHARACTERS = 16;
    private static final int MAX_RELATIONS = 20;
    private static final int MAX_FACTIONS = 6;
    private static final int MAX_EVENTS = 20;

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";

    private static final String CATEGORY_WORLDVIEW = "WORLDVIEW";
    private static final String CATEGORY_BACKGROUND = "BACKGROUND";
    private static final String CATEGORY_CORE_CONFLICT = "CORE_CONFLICT";
    private static final String CATEGORY_HIGHLIGHT = "HIGHLIGHT";
    private static final String CATEGORY_STYLE = "STYLE";

    private static final Map<String, String> LEVEL_LABELS = new LinkedHashMap<>();

    private static final Set<String> RELATION_TYPES = Set.of(
            "MASTER_APPRENTICE", "FAMILY", "SPOUSE", "LOVER", "FRIEND",
            "ALLY", "ENEMY", "RIVAL", "COLLEAGUE", "OTHER");

    private static final Set<String> FACTION_STATUS = Set.of("ACTIVE", "DORMANT", "DESTROYED", "DISBANDED");
    private static final Set<String> EVENT_STATUS = Set.of("UNUSED", "ONGOING", "DONE", "ABANDONED");

    static {
        LEVEL_LABELS.put("LIGHT", "轻度");
        LEVEL_LABELS.put("MEDIUM", "中度");
        LEVEL_LABELS.put("HEAVY", "重度");
        LEVEL_LABELS.put("REBUILD", "完全重构");
    }

    private final BookDigestMapper digestMapper;
    private final NovelService novelService;
    private final NovelMapper novelMapper;
    private final SettingDocMapper settingDocMapper;
    private final CharacterMapper characterMapper;
    private final CharacterRelationMapper characterRelationMapper;
    private final FactionMapper factionMapper;
    private final FactionMemberMapper factionMemberMapper;
    private final TimelineEventMapper timelineEventMapper;
    private final EventParticipantMapper eventParticipantMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterCharacterMapper chapterCharacterMapper;
    private final ChapterEventMapper chapterEventMapper;
    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;

    private final AtomicInteger threadSeq = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "book-digest-" + threadSeq.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 服务重启后把残留的 RUNNING 任务标记为失败，避免前端一直转圈。 */
    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        try {
            int rows = digestMapper.update(null, Wrappers.<BookDigestEntity>lambdaUpdate()
                    .eq(BookDigestEntity::getStatus, STATUS_RUNNING)
                    .set(BookDigestEntity::getStatus, STATUS_FAILED)
                    .set(BookDigestEntity::getStepLabel, "分析中断")
                    .set(BookDigestEntity::getErrorMessage, "服务重启导致分析中断，请重新拆书")
                    .set(BookDigestEntity::getUpdatedAt, LocalDateTime.now()));
            if (rows > 0) {
                log.info("[deconstruct] marked {} interrupted task(s) as FAILED", rows);
            }
        } catch (RuntimeException ex) {
            log.warn("[deconstruct] cleanup interrupted tasks failed: {}", ex.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 上传与任务                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * 接收上传文件并启动拆书任务。
     *
     * @param file                纯文本文件（txt / md）
     * @param adaptationLevel     LIGHT / MEDIUM / HEAVY / REBUILD
     * @param backgroundDirection 背景改编方向，可空
     * @param chapterTarget       目标章节数 8~24，空则 16
     */
    public DeconstructTaskVO start(MultipartFile file, String adaptationLevel,
                                   String backgroundDirection, Integer chapterTarget) {
        Long userId = UserContextHolder.requireUserId();
        UserContext context = UserContextHolder.require();
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先选择要拆解的小说文件");
        }
        String fileName = trimToNull(file.getOriginalFilename());
        if (fileName != null && !isTextFile(fileName)) {
            throw BizException.of(ErrorCode.DECONSTRUCT_FILE_INVALID);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "读取上传文件失败");
        }
        NovelTextParser.Digest digest = NovelTextParser.parse(NovelTextParser.decode(bytes));
        if (digest.chars() < MIN_CHARS) {
            throw BizException.of(ErrorCode.DECONSTRUCT_TEXT_TOO_SHORT);
        }
        if (digest.chars() > MAX_CHARS) {
            throw BizException.of(ErrorCode.DECONSTRUCT_TEXT_TOO_LONG);
        }

        String level = normalizeLevel(adaptationLevel);
        int target = chapterTarget == null ? DEFAULT_CHAPTER_TARGET
                : Math.min(MAX_CHAPTER_TARGET, Math.max(MIN_CHAPTER_TARGET, chapterTarget));
        String direction = truncate(trimToNull(backgroundDirection), 500);

        // 端点解析放在调用线程：没配 AI 立刻报 40002，不必等任务跑起来
        endpointResolver.resolve(AiScene.DECONSTRUCT);

        BookDigestEntity entity = new BookDigestEntity();
        entity.setUserId(userId);
        entity.setSourceName(truncate(fileName == null ? "未命名文本" : fileName, 200));
        entity.setSourceChars(digest.chars());
        entity.setSourceChapters(digest.chapters());
        entity.setAdaptationLevel(level);
        entity.setBackgroundDirection(direction);
        entity.setChapterTarget(target);
        entity.setStatus(STATUS_RUNNING);
        entity.setPercent(2);
        entity.setStepNo(0);
        entity.setStepTotal(STEP_TOTAL);
        entity.setStepLabel("正在准备");
        entity.setDeleted(0);
        entity.setVersion(0);
        digestMapper.insert(entity);

        DeconstructPromptBuilder.Meta meta = new DeconstructPromptBuilder.Meta(
                entity.getSourceName(), digest.chars(), digest.chapters(), level,
                LEVEL_LABELS.getOrDefault(level, "中度"), direction, target,
                digest.segments().size(), digest.sampled());

        String taskId = String.valueOf(entity.getId());
        executor.submit(() -> run(taskId, entity.getId(), context, digest, meta));
        log.info("[deconstruct] started taskId={} userId={} file={} chars={} level={} target={} segments={}",
                taskId, userId, entity.getSourceName(), digest.chars(), level, target, digest.segments().size());
        return toVO(entity);
    }

    /** 查询进度与结果；仅本人可见（数据隔离插件自动追加 user_id）。 */
    public DeconstructTaskVO status(String taskId) {
        return toVO(require(taskId));
    }

    /** 拆书记录列表。 */
    public PageResult<DeconstructSummaryVO> list(PageQuery query) {
        PageQuery safe = query == null ? new PageQuery() : query;
        Page<BookDigestEntity> page = new Page<>(safe.normalizedPage(), safe.normalizedSize());
        IPage<BookDigestEntity> result = digestMapper.selectPage(page,
                Wrappers.<BookDigestEntity>lambdaQuery()
                        .select(BookDigestEntity::getId,
                                BookDigestEntity::getSourceName,
                                BookDigestEntity::getSourceChars,
                                BookDigestEntity::getAdaptationLevel,
                                BookDigestEntity::getChapterTarget,
                                BookDigestEntity::getStatus,
                                BookDigestEntity::getResultTitle,
                                BookDigestEntity::getGeneratedNovelId,
                                BookDigestEntity::getCreatedAt)
                        .orderByDesc(BookDigestEntity::getCreatedAt));
        List<DeconstructSummaryVO> records = result.getRecords().stream().map(this::toSummary).toList();
        return PageResult.of(records, result.getTotal(), safe.normalizedPage(), safe.normalizedSize());
    }

    /** 删除拆书记录（只删分析记录，已生成的小说保留）。 */
    public void delete(String taskId) {
        BookDigestEntity entity = require(taskId);
        digestMapper.deleteById(entity.getId());
        log.info("[deconstruct] deleted taskId={} userId={}", taskId, entity.getUserId());
    }

    /* ------------------------------------------------------------------ */
    /* 流水线                                                             */
    /* ------------------------------------------------------------------ */

    private void run(String taskId, Long id, UserContext context,
                     NovelTextParser.Digest digest, DeconstructPromptBuilder.Meta meta) {
        UserContextHolder.set(context);
        long startedAt = System.currentTimeMillis();
        try {
            AiEndpoint base = endpointResolver.resolve(AiScene.DECONSTRUCT);

            // 1. 逐段通读
            List<JsonNode> notes = new ArrayList<>();
            List<NovelTextParser.Segment> segments = digest.segments();
            for (int i = 0; i < segments.size(); i++) {
                NovelTextParser.Segment segment = segments.get(i);
                int percent = 8 + (int) Math.round(28.0 * i / Math.max(1, segments.size()));
                notes.add(call(base, DeconstructPromptBuilder.SYSTEM,
                        DeconstructPromptBuilder.digestPrompt(segment, meta),
                        2200, 0.55D, id, 1,
                        "正在通读第 " + segment.index() + "/" + segment.total() + " 段", percent));
            }

            String digestText = renderNotes(notes);
            String bannedNames = collectNames(notes);

            // 2. 主线脉络
            JsonNode spineNode = call(base, DeconstructPromptBuilder.SYSTEM,
                    DeconstructPromptBuilder.spinePrompt(digestText, meta),
                    2600, 0.6D, id, 2, "正在梳理主线脉络", 44);
            DeconstructResult.Spine spine = readSpine(spineNode);

            // 3. 世界观与背景
            JsonNode settingNode = call(base, DeconstructPromptBuilder.SYSTEM,
                    DeconstructPromptBuilder.settingPrompt(renderSpine(spine), digestText, bannedNames, meta),
                    4000, 0.88D, id, 3, "正在重构世界观与背景", 58);

            // 4. 人物 / 关系 / 势力
            JsonNode characterNode = call(base, DeconstructPromptBuilder.SYSTEM,
                    DeconstructPromptBuilder.characterPrompt(renderSetting(settingNode), renderSpine(spine), bannedNames, meta),
                    5200, 0.88D, id, 4, "正在重塑人物与势力", 74);

            // 5. 事件与章节大纲
            JsonNode outlineNode = call(base, DeconstructPromptBuilder.SYSTEM,
                    DeconstructPromptBuilder.outlinePrompt(
                            renderSetting(settingNode) + "\n\n" + renderCharacters(characterNode), meta),
                    9000, 0.88D, id, 5, "正在重排章节大纲与事件", 90);

            // 6. 组装与清洗
            progress(id, 6, "正在整理改编结果", 96, null);
            DeconstructResult result = assemble(settingNode, spine, characterNode, outlineNode, meta);
            result.setModel(base.model());
            result.setLatencyMs(System.currentTimeMillis() - startedAt);

            String json;
            try {
                json = objectMapper.writeValueAsString(result);
            } catch (Exception ex) {
                throw BizException.of(ErrorCode.INTERNAL_ERROR, "序列化拆书结果失败");
            }
            finish(id, json, result.getTitle());
            log.info("[deconstruct] succeeded taskId={} userId={} title={} chapters={} events={} latency={}ms",
                    taskId, context.userId(), result.getTitle(), result.getChapters().size(),
                    result.getEvents().size(), result.getLatencyMs());
        } catch (BizException ex) {
            fail(id, ex.getMessage());
            log.warn("[deconstruct] failed taskId={} userId={}: {}", taskId, context.userId(), ex.getMessage());
        } catch (RuntimeException ex) {
            fail(id, hasText(ex.getMessage()) ? ex.getMessage() : "拆书失败，请重试");
            log.warn("[deconstruct] failed taskId={} userId={}: {}", taskId, context.userId(), ex.toString());
        } finally {
            UserContextHolder.clear();
        }
    }

    /** 单次调用：解析失败（50002）时用更严格的提示词重试一次。 */
    private JsonNode call(AiEndpoint base, String systemPrompt, String userPrompt, int maxTokens,
                          double temperature, Long id, int stepNo, String label, int percent) {
        progress(id, stepNo, label, percent, null);
        try {
            return parse(chat(base, systemPrompt, userPrompt, maxTokens, temperature).content(), label);
        } catch (BizException ex) {
            if (ex.getErrorCode() != ErrorCode.AI_PARSE_FAILED) {
                throw ex;
            }
            log.warn("[deconstruct] parse failed at {}, retrying taskId={}", label, id);
            progress(id, stepNo, label, Math.min(percent + 2, 98), "上一段返回格式不合法，正在重新生成");
            return parse(chat(base, systemPrompt + DeconstructPromptBuilder.RETRY_SUFFIX,
                    userPrompt, maxTokens, temperature).content(), label);
        }
    }

    private ChatResult chat(AiEndpoint base, String systemPrompt, String userPrompt,
                            int maxTokens, double temperature) {
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS),
                base.extraHeaders(),
                temperature,
                maxTokens);
        try {
            return aiUsageService.chat(AiScene.DECONSTRUCT, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw BizException.of(ErrorCode.AI_CALL_FAILED,
                    hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败");
        }
    }

    private JsonNode parse(String content, String stage) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[deconstruct] stage {} returned no JSON: {}", stage, abbreviate(content, 400));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, stage + "时模型没有返回结构化内容，请重试");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[deconstruct] stage {} parse failed: {} | raw: {}",
                        stage, strictFailure.getMessage(), abbreviate(candidate, 400));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, stage + "时模型返回的内容无法解析，请重试");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 组装与清洗                                                         */
    /* ------------------------------------------------------------------ */

    private DeconstructResult assemble(JsonNode setting, DeconstructResult.Spine spine,
                                       JsonNode characterNode, JsonNode outlineNode,
                                       DeconstructPromptBuilder.Meta meta) {
        DeconstructResult result = new DeconstructResult();
        result.setTitle(truncate(textOf(setting, "title"), 200));
        result.setGenre(truncate(textOf(setting, "genre"), 64));
        result.setTags(textList(setting, "tags"));
        result.setSynopsis(truncate(textOf(setting, "synopsis"), 4000));
        result.setWorldview(truncate(textOf(setting, "worldview"), 6000));
        result.setBackground(truncate(textOf(setting, "background"), 6000));
        result.setCoreConflict(truncate(textOf(setting, "coreConflict"), 4000));
        result.setHighlights(textList(setting, "highlights"));
        result.setStyle(truncate(textOf(setting, "style"), 200));
        result.setSpine(spine);

        result.setProtagonist(readCharacter(characterNode.path("protagonist"), true));
        result.setCharacters(readCharacters(characterNode.path("characters")));
        result.setRelations(readRelations(characterNode.path("relations")));
        result.setFactions(readFactions(characterNode.path("factions")));

        result.setEvents(readEvents(outlineNode.path("events")));
        result.setChapters(readChapters(outlineNode.path("chapters"), meta.chapterTarget()));

        result.setAdaptationLevel(meta.level());
        result.setAdaptationLabel(meta.levelLabel());
        result.setBackgroundDirection(meta.direction());
        result.setSourceChars(meta.chars());
        result.setSourceChapters(meta.chapters());
        result.setSampled(meta.sampled());

        normalize(result, meta);
        return result;
    }

    /**
     * 清洗：去重、校验引用、限制数量，保证落库时不会出现孤儿关联。
     */
    private void normalize(DeconstructResult result, DeconstructPromptBuilder.Meta meta) {
        result.setTags(normalizeList(result.getTags(), 8, 24));
        result.setHighlights(normalizeList(result.getHighlights(), 5, 40));

        LinkedHashMap<String, CharacterDraft> byName = new LinkedHashMap<>();
        CharacterDraft protagonist = result.getProtagonist();
        if (protagonist != null && hasText(protagonist.getName())) {
            protagonist.setName(protagonist.getName().trim());
            protagonist.setRolePos("PROTAGONIST");
            byName.put(protagonist.getName(), protagonist);
        }
        List<CharacterDraft> others = new ArrayList<>();
        for (CharacterDraft item : result.getCharacters()) {
            if (item == null || !hasText(item.getName())) {
                continue;
            }
            String name = item.getName().trim();
            if (byName.size() >= MAX_CHARACTERS) {
                break;
            }
            if (byName.containsKey(name)) {
                continue;
            }
            item.setName(name);
            item.setRolePos(normalizeRole(item.getRolePos()));
            byName.put(name, item);
            others.add(item);
        }
        // 没有主角时把第一个角色提上来，保证工作台里一定有主角
        if (protagonist == null && !others.isEmpty()) {
            CharacterDraft first = others.remove(0);
            first.setRolePos("PROTAGONIST");
            result.setProtagonist(first);
        }
        result.setCharacters(others);
        Set<String> names = new HashSet<>(byName.keySet());

        // 关系：两端必须在人物表里，且按无序去重
        List<DeconstructResult.Relation> relations = new ArrayList<>();
        Set<String> relationKeys = new HashSet<>();
        for (DeconstructResult.Relation relation : result.getRelations()) {
            if (relation == null) {
                continue;
            }
            String from = trimToNull(relation.getFrom());
            String to = trimToNull(relation.getTo());
            if (from == null || to == null || from.equals(to)
                    || !names.contains(from) || !names.contains(to)) {
                continue;
            }
            String type = normalizeRelationType(relation.getType());
            String key = (from.compareTo(to) <= 0 ? from + "|" + to : to + "|" + from) + "#" + type;
            if (!relationKeys.add(key)) {
                continue;
            }
            relation.setFrom(from);
            relation.setTo(to);
            relation.setType(type);
            relation.setDescription(truncate(relation.getDescription(), 255));
            relations.add(relation);
            if (relations.size() >= MAX_RELATIONS) {
                break;
            }
        }
        result.setRelations(relations);

        // 势力：成员只保留人物表里有的
        List<DeconstructResult.Faction> factions = new ArrayList<>();
        Set<String> factionNames = new LinkedHashSet<>();
        for (DeconstructResult.Faction faction : result.getFactions()) {
            if (faction == null || !hasText(faction.getName())) {
                continue;
            }
            String name = faction.getName().trim();
            if (!factionNames.add(name)) {
                continue;
            }
            faction.setName(truncate(name, 100));
            faction.setCategory(truncate(trimToNull(faction.getCategory()), 32));
            faction.setStatus(normalizeFactionStatus(faction.getStatus()));
            faction.setScope(truncate(faction.getScope(), 500));
            faction.setRemark(truncate(faction.getRemark(), 2000));
            List<DeconstructResult.Member> members = new ArrayList<>();
            Set<String> memberKeys = new HashSet<>();
            for (DeconstructResult.Member member : faction.getMembers()) {
                if (member == null) {
                    continue;
                }
                String character = trimToNull(member.getCharacter());
                if (character == null || !names.contains(character) || !memberKeys.add(character)) {
                    continue;
                }
                member.setCharacter(character);
                member.setPosition(truncate(trimToNull(member.getPosition()), 100));
                members.add(member);
            }
            faction.setMembers(members);
            factions.add(faction);
            if (factions.size() >= MAX_FACTIONS) {
                break;
            }
        }
        result.setFactions(factions);

        // 事件：标题去重，状态归一，参与角色校验
        List<DeconstructResult.Event> events = new ArrayList<>();
        Set<String> eventTitles = new LinkedHashSet<>();
        for (DeconstructResult.Event event : result.getEvents()) {
            if (event == null || !hasText(event.getTitle())) {
                continue;
            }
            String title = event.getTitle().trim();
            if (!eventTitles.add(title)) {
                continue;
            }
            event.setTitle(truncate(title, 200));
            event.setEventTime(truncate(trimToNull(event.getEventTime()), 64));
            event.setDescription(truncate(event.getDescription(), 2000));
            event.setStatus(normalizeEventStatus(event.getStatus()));
            List<String> participants = new ArrayList<>();
            for (String name : event.getParticipants()) {
                if (hasText(name) && names.contains(name.trim()) && !participants.contains(name.trim())) {
                    participants.add(name.trim());
                }
            }
            event.setParticipants(participants);
            events.add(event);
            if (events.size() >= MAX_EVENTS) {
                break;
            }
        }
        result.setEvents(events);

        // 章节：排序、编号补齐、引用校验、按目标章数截断
        List<DeconstructResult.Chapter> chapters = new ArrayList<>();
        Set<String> chapterTitles = new HashSet<>();
        for (DeconstructResult.Chapter chapter : result.getChapters()) {
            if (chapter == null || !hasText(chapter.getTitle())) {
                continue;
            }
            String title = chapter.getTitle().trim();
            if (!chapterTitles.add(title)) {
                continue;
            }
            chapters.add(chapter);
        }
        chapters.sort((a, b) -> {
            int left = a.getChapterNo() == null ? Integer.MAX_VALUE : a.getChapterNo();
            int right = b.getChapterNo() == null ? Integer.MAX_VALUE : b.getChapterNo();
            return Integer.compare(left, right);
        });
        if (chapters.size() > meta.chapterTarget()) {
            chapters = new ArrayList<>(chapters.subList(0, meta.chapterTarget()));
        }
        int lastVolume = 1;
        for (int i = 0; i < chapters.size(); i++) {
            DeconstructResult.Chapter chapter = chapters.get(i);
            chapter.setChapterNo(i + 1);
            chapter.setVolumeNo(chapter.getVolumeNo() == null || chapter.getVolumeNo() < 1
                    ? lastVolume : chapter.getVolumeNo());
            lastVolume = chapter.getVolumeNo();
            chapter.setVolumeTitle(truncate(trimToNull(chapter.getVolumeTitle()), 120));
            chapter.setTitle(truncate(chapter.getTitle().trim(), 200));
            chapter.setSummary(truncate(chapter.getSummary(), 3000));
            List<String> cast = new ArrayList<>();
            for (String name : chapter.getCharacters()) {
                if (hasText(name) && names.contains(name.trim()) && !cast.contains(name.trim())) {
                    cast.add(name.trim());
                }
            }
            chapter.setCharacters(cast);
            List<String> linked = new ArrayList<>();
            for (String title : chapter.getEvents()) {
                if (hasText(title) && eventTitles.contains(title.trim()) && !linked.contains(title.trim())) {
                    linked.add(title.trim());
                }
            }
            chapter.setEvents(linked);
        }
        result.setChapters(chapters);

        if (!hasText(result.getTitle())) {
            result.setTitle("未命名改编");
        }
        if (!hasText(result.getWorldview()) && !hasText(result.getBackground())
                && !hasText(result.getSynopsis())) {
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的改编设定缺少关键内容，请重试");
        }
    }

    /* ------------------------------------------------------------------ */
    /* 保存为新小说                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * 把拆书结果保存成一本新小说：设定、人物、关系、势力、事件、章节大纲一次性落库。
     *
     * <p>幂等：同一条记录重复调用直接返回已创建的小说 ID。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public Long save(String taskId) {
        BookDigestEntity entity = require(taskId);
        if (entity.getGeneratedNovelId() != null) {
            return entity.getGeneratedNovelId();
        }
        if (!STATUS_SUCCEEDED.equals(entity.getStatus()) || !hasText(entity.getResultJson())) {
            throw BizException.of(ErrorCode.DECONSTRUCT_NOT_READY);
        }
        DeconstructResult result = readResult(entity);
        Long userId = UserContextHolder.requireUserId();

        NovelVO novel = novelService.createDraft(new NovelDraft(
                result.getTitle(),
                result.getGenre(),
                result.getTags(),
                result.getSynopsis(),
                null,
                "DECONSTRUCT",
                null));
        Long novelId = novel.getId();

        saveSettings(novelId, userId, result);
        Map<String, Long> characterIds = saveCharacters(novelId, userId, result);
        saveRelations(novelId, userId, result, characterIds);
        saveFactions(novelId, userId, result, characterIds);
        Map<String, Long> eventIds = saveEvents(novelId, userId, result, characterIds);
        saveChapters(novelId, userId, result, characterIds, eventIds);

        novelMapper.update(null, Wrappers.<NovelEntity>lambdaUpdate()
                .eq(NovelEntity::getId, novelId)
                .set(NovelEntity::getChapterCount, result.getChapters().size())
                .set(NovelEntity::getWordCount, 0));

        digestMapper.update(null, Wrappers.<BookDigestEntity>lambdaUpdate()
                .eq(BookDigestEntity::getId, entity.getId())
                .set(BookDigestEntity::getGeneratedNovelId, novelId)
                .set(BookDigestEntity::getUpdatedAt, LocalDateTime.now()));

        log.info("[deconstruct] saved taskId={} novelId={} title={} characters={} chapters={} events={}",
                taskId, novelId, novel.getTitle(), characterIds.size(),
                result.getChapters().size(), eventIds.size());
        return novelId;
    }

    private void saveSettings(Long novelId, Long userId, DeconstructResult result) {
        int sort = 0;
        sort = insertSetting(novelId, userId, CATEGORY_WORLDVIEW, "世界观", result.getWorldview(), sort);
        sort = insertSetting(novelId, userId, CATEGORY_BACKGROUND, "背景设定", result.getBackground(), sort);
        sort = insertSetting(novelId, userId, CATEGORY_CORE_CONFLICT, "核心冲突", result.getCoreConflict(), sort);
        sort = insertSetting(novelId, userId, CATEGORY_HIGHLIGHT, "核心看点", joinLines(result.getHighlights()), sort);
        insertSetting(novelId, userId, CATEGORY_STYLE, "文风基调", result.getStyle(), sort);
    }

    private int insertSetting(Long novelId, Long userId, String category, String label, String content, int sort) {
        if (!hasText(content)) {
            return sort;
        }
        SettingDocEntity entity = new SettingDocEntity();
        entity.setUserId(userId);
        entity.setNovelId(novelId);
        entity.setCategory(category);
        entity.setTitle(label);
        entity.setContentMd(content.trim());
        entity.setInjectScope("GLOBAL");
        entity.setSort(sort);
        entity.setDeleted(0);
        entity.setVersion(0);
        settingDocMapper.insert(entity);
        return sort + 1;
    }

    private Map<String, Long> saveCharacters(Long novelId, Long userId, DeconstructResult result) {
        Map<String, Long> ids = new LinkedHashMap<>();
        List<CharacterDraft> all = new ArrayList<>();
        if (result.getProtagonist() != null) {
            all.add(result.getProtagonist());
        }
        all.addAll(result.getCharacters());

        int index = 0;
        for (CharacterDraft draft : all) {
            if (draft == null || !hasText(draft.getName())) {
                continue;
            }
            CharacterEntity entity = new CharacterEntity();
            entity.setUserId(userId);
            entity.setNovelId(novelId);
            entity.setName(truncate(draft.getName().trim(), 64));
            entity.setIdentity(truncate(draft.getIdentity(), 255));
            entity.setGender(truncate(draft.getGender(), 16));
            entity.setAgeDesc(truncate(draft.getAgeDesc(), 32));
            entity.setRolePos(normalizeRole(draft.getRolePos()));
            entity.setLifeStatus("ALIVE");
            entity.setProfileJson(writeProfile(draft));
            entity.setSortNo(index++);
            entity.setDeleted(0);
            entity.setVersion(0);
            characterMapper.insert(entity);
            ids.put(draft.getName().trim(), entity.getId());
        }
        return ids;
    }

    private void saveRelations(Long novelId, Long userId, DeconstructResult result, Map<String, Long> characterIds) {
        int sort = 0;
        Set<String> seen = new HashSet<>();
        for (DeconstructResult.Relation relation : result.getRelations()) {
            Long from = characterIds.get(relation.getFrom());
            Long to = characterIds.get(relation.getTo());
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            String type = normalizeRelationType(relation.getType());
            long low = Math.min(from, to);
            long high = Math.max(from, to);
            if (!seen.add(low + "|" + high + "#" + type)) {
                continue;
            }
            CharacterRelationEntity entity = new CharacterRelationEntity();
            entity.setUserId(userId);
            entity.setNovelId(novelId);
            entity.setFromCharacterId(low);
            entity.setToCharacterId(high);
            entity.setRelationType(type);
            entity.setDescription(relation.getDescription() == null ? "" : relation.getDescription().trim());
            entity.setSortNo(sort++);
            entity.setDeleted(0);
            entity.setVersion(0);
            characterRelationMapper.insert(entity);
        }
    }

    private void saveFactions(Long novelId, Long userId, DeconstructResult result, Map<String, Long> characterIds) {
        int factionSort = 0;
        for (DeconstructResult.Faction faction : result.getFactions()) {
            FactionEntity entity = new FactionEntity();
            entity.setUserId(userId);
            entity.setNovelId(novelId);
            entity.setName(truncate(faction.getName(), 100));
            entity.setCategory(truncate(faction.getCategory(), 32));
            entity.setRemark(truncate(faction.getRemark(), 2000));
            entity.setScope(truncate(faction.getScope(), 500));
            entity.setStatus(normalizeFactionStatus(faction.getStatus()));
            entity.setSortNo(factionSort++);
            entity.setDeleted(0);
            entity.setVersion(0);
            factionMapper.insert(entity);

            int memberSort = 0;
            for (DeconstructResult.Member member : faction.getMembers()) {
                Long characterId = characterIds.get(member.getCharacter());
                if (characterId == null) {
                    continue;
                }
                FactionMemberEntity row = new FactionMemberEntity();
                row.setUserId(userId);
                row.setNovelId(novelId);
                row.setFactionId(entity.getId());
                row.setCharacterId(characterId);
                row.setPosition(truncate(member.getPosition(), 100));
                row.setSortNo(memberSort++);
                row.setDeleted(0);
                row.setVersion(0);
                factionMemberMapper.insert(row);
            }
        }
    }

    private Map<String, Long> saveEvents(Long novelId, Long userId, DeconstructResult result,
                                         Map<String, Long> characterIds) {
        Map<String, Long> ids = new LinkedHashMap<>();
        int sort = 0;
        for (DeconstructResult.Event event : result.getEvents()) {
            TimelineEventEntity entity = new TimelineEventEntity();
            entity.setUserId(userId);
            entity.setNovelId(novelId);
            entity.setEventTime(truncate(event.getEventTime(), 64));
            entity.setTitle(truncate(event.getTitle(), 200));
            entity.setDescription(truncate(event.getDescription(), 2000));
            entity.setStatus(normalizeEventStatus(event.getStatus()));
            entity.setSortNo(sort++);
            entity.setDeleted(0);
            entity.setVersion(0);
            timelineEventMapper.insert(entity);
            ids.put(event.getTitle(), entity.getId());

            int participantSort = 0;
            for (String name : event.getParticipants()) {
                Long characterId = characterIds.get(name);
                if (characterId == null) {
                    continue;
                }
                EventParticipantEntity row = new EventParticipantEntity();
                row.setUserId(userId);
                row.setNovelId(novelId);
                row.setEventId(entity.getId());
                row.setCharacterId(characterId);
                row.setSortNo(participantSort++);
                row.setDeleted(0);
                row.setVersion(0);
                eventParticipantMapper.insert(row);
            }
        }
        return ids;
    }

    private void saveChapters(Long novelId, Long userId, DeconstructResult result,
                              Map<String, Long> characterIds, Map<String, Long> eventIds) {
        for (DeconstructResult.Chapter chapter : result.getChapters()) {
            ChapterEntity entity = new ChapterEntity();
            entity.setUserId(userId);
            entity.setNovelId(novelId);
            entity.setVolumeNo(chapter.getVolumeNo());
            entity.setVolumeTitle(chapter.getVolumeTitle());
            entity.setChapterNo(chapter.getChapterNo());
            entity.setTitle(chapter.getTitle());
            entity.setSummary(chapter.getSummary());
            entity.setContent(null);
            entity.setWordCount(0);
            entity.setStatus("DRAFT");
            entity.setSortNo(chapter.getVolumeNo() * 1000 + chapter.getChapterNo());
            entity.setDeleted(0);
            entity.setVersion(0);
            chapterMapper.insert(entity);

            int sort = 0;
            for (String name : chapter.getCharacters()) {
                Long characterId = characterIds.get(name);
                if (characterId == null) {
                    continue;
                }
                ChapterCharacterEntity row = new ChapterCharacterEntity();
                row.setUserId(userId);
                row.setNovelId(novelId);
                row.setChapterId(entity.getId());
                row.setCharacterId(characterId);
                row.setSortNo(sort++);
                row.setDeleted(0);
                row.setVersion(0);
                chapterCharacterMapper.insert(row);
            }

            sort = 0;
            for (String title : chapter.getEvents()) {
                Long eventId = eventIds.get(title);
                if (eventId == null) {
                    continue;
                }
                ChapterEventEntity row = new ChapterEventEntity();
                row.setUserId(userId);
                row.setNovelId(novelId);
                row.setChapterId(entity.getId());
                row.setEventId(eventId);
                row.setSortNo(sort++);
                row.setDeleted(0);
                row.setVersion(0);
                chapterEventMapper.insert(row);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 读取 AI 输出                                                       */
    /* ------------------------------------------------------------------ */

    private DeconstructResult.Spine readSpine(JsonNode node) {
        DeconstructResult.Spine spine = new DeconstructResult.Spine();
        spine.setLogline(truncate(textOf(node, "logline"), 500));
        spine.setTheme(truncate(textOf(node, "theme"), 500));
        spine.setMainline(truncate(textOf(node, "mainline"), 4000));
        spine.setEnding(truncate(textOf(node, "ending"), 2000));
        spine.setTurns(textList(node, "turns"));
        List<DeconstructResult.Act> acts = new ArrayList<>();
        for (JsonNode item : node.path("acts")) {
            DeconstructResult.Act act = new DeconstructResult.Act();
            act.setName(truncate(textOf(item, "name"), 64));
            act.setRange(truncate(textOf(item, "range"), 64));
            act.setContent(truncate(textOf(item, "content"), 2000));
            if (hasText(act.getContent())) {
                acts.add(act);
            }
        }
        spine.setActs(acts);
        List<DeconstructResult.Function> functions = new ArrayList<>();
        for (JsonNode item : node.path("characterFunctions")) {
            DeconstructResult.Function function = new DeconstructResult.Function();
            function.setFunction(truncate(textOf(item, "function"), 64));
            function.setOriginalRole(truncate(textOf(item, "originalRole"), 128));
            function.setRole(truncate(textOf(item, "role"), 500));
            if (hasText(function.getFunction()) || hasText(function.getOriginalRole())) {
                functions.add(function);
            }
        }
        spine.setCharacterFunctions(functions);
        return spine;
    }

    private CharacterDraft readCharacter(JsonNode node, boolean protagonist) {
        if (node == null || !node.isObject()) {
            return null;
        }
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
        draft.setRolePos(protagonist ? "PROTAGONIST" : normalizeRole(textOf(node, "rolePos")));
        return hasText(draft.getName()) ? draft : null;
    }

    private List<CharacterDraft> readCharacters(JsonNode node) {
        List<CharacterDraft> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            CharacterDraft draft = readCharacter(item, false);
            if (draft != null) {
                list.add(draft);
            }
        }
        return list;
    }

    private List<DeconstructResult.Relation> readRelations(JsonNode node) {
        List<DeconstructResult.Relation> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            DeconstructResult.Relation relation = new DeconstructResult.Relation();
            relation.setFrom(truncate(textOf(item, "from"), 64));
            relation.setTo(truncate(textOf(item, "to"), 64));
            relation.setType(textOf(item, "type"));
            relation.setDescription(truncate(textOf(item, "description"), 255));
            if (hasText(relation.getFrom()) && hasText(relation.getTo())) {
                list.add(relation);
            }
        }
        return list;
    }

    private List<DeconstructResult.Faction> readFactions(JsonNode node) {
        List<DeconstructResult.Faction> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            DeconstructResult.Faction faction = new DeconstructResult.Faction();
            faction.setName(truncate(textOf(item, "name"), 100));
            faction.setCategory(truncate(textOf(item, "category"), 32));
            faction.setStatus(normalizeFactionStatus(textOf(item, "status")));
            faction.setScope(truncate(textOf(item, "scope"), 500));
            faction.setRemark(truncate(textOf(item, "remark"), 2000));
            List<DeconstructResult.Member> members = new ArrayList<>();
            for (JsonNode memberNode : item.path("members")) {
                DeconstructResult.Member member = new DeconstructResult.Member();
                member.setCharacter(truncate(textOf(memberNode, "character"), 64));
                member.setPosition(truncate(textOf(memberNode, "position"), 100));
                if (hasText(member.getCharacter())) {
                    members.add(member);
                }
            }
            faction.setMembers(members);
            if (hasText(faction.getName())) {
                list.add(faction);
            }
        }
        return list;
    }

    private List<DeconstructResult.Event> readEvents(JsonNode node) {
        List<DeconstructResult.Event> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            DeconstructResult.Event event = new DeconstructResult.Event();
            event.setTitle(truncate(textOf(item, "title"), 200));
            event.setEventTime(truncate(textOf(item, "eventTime"), 64));
            event.setDescription(truncate(textOf(item, "description"), 2000));
            event.setStatus(normalizeEventStatus(textOf(item, "status")));
            event.setParticipants(textList(item, "participants"));
            if (hasText(event.getTitle())) {
                list.add(event);
            }
        }
        return list;
    }

    private List<DeconstructResult.Chapter> readChapters(JsonNode node, int target) {
        List<DeconstructResult.Chapter> list = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return list;
        }
        for (JsonNode item : node) {
            if (list.size() >= target) {
                break;
            }
            DeconstructResult.Chapter chapter = new DeconstructResult.Chapter();
            chapter.setVolumeNo(item.path("volumeNo").isNumber() ? item.path("volumeNo").asInt() : null);
            chapter.setVolumeTitle(truncate(textOf(item, "volumeTitle"), 120));
            chapter.setChapterNo(item.path("chapterNo").isNumber() ? item.path("chapterNo").asInt() : null);
            chapter.setTitle(truncate(textOf(item, "title"), 200));
            chapter.setSummary(truncate(textOf(item, "summary"), 3000));
            chapter.setCharacters(textList(item, "characters"));
            chapter.setEvents(textList(item, "events"));
            if (hasText(chapter.getTitle())) {
                list.add(chapter);
            }
        }
        return list;
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                               */
    /* ------------------------------------------------------------------ */

    private void progress(Long id, int stepNo, String label, int percent, String message) {
        try {
            var update = Wrappers.<BookDigestEntity>lambdaUpdate()
                    .eq(BookDigestEntity::getId, id)
                    .set(BookDigestEntity::getStepNo, stepNo)
                    .set(BookDigestEntity::getStepLabel, truncate(label, 64))
                    .set(BookDigestEntity::getPercent, Math.min(percent, 99))
                    .set(BookDigestEntity::getUpdatedAt, LocalDateTime.now());
            if (message != null) {
                update.set(BookDigestEntity::getErrorMessage, truncate(message, 500));
            }
            digestMapper.update(null, update);
        } catch (RuntimeException ex) {
            log.warn("[deconstruct] progress update failed id={}: {}", id, ex.getMessage());
        }
    }

    private void finish(Long id, String json, String title) {
        digestMapper.update(null, Wrappers.<BookDigestEntity>lambdaUpdate()
                .eq(BookDigestEntity::getId, id)
                .set(BookDigestEntity::getStatus, STATUS_SUCCEEDED)
                .set(BookDigestEntity::getPercent, 100)
                .set(BookDigestEntity::getStepNo, STEP_TOTAL)
                .set(BookDigestEntity::getStepLabel, "改编完成")
                .set(BookDigestEntity::getResultTitle, truncate(title, 200))
                .set(BookDigestEntity::getResultJson, json)
                .set(BookDigestEntity::getErrorMessage, null)
                .set(BookDigestEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void fail(Long id, String message) {
        digestMapper.update(null, Wrappers.<BookDigestEntity>lambdaUpdate()
                .eq(BookDigestEntity::getId, id)
                .set(BookDigestEntity::getStatus, STATUS_FAILED)
                .set(BookDigestEntity::getStepLabel, "分析失败")
                .set(BookDigestEntity::getErrorMessage, truncate(message, 500))
                .set(BookDigestEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private BookDigestEntity require(String taskId) {
        Long id = parseId(taskId);
        BookDigestEntity entity = digestMapper.selectOne(Wrappers.<BookDigestEntity>lambdaQuery()
                .eq(BookDigestEntity::getId, id));
        if (entity == null) {
            throw BizException.of(ErrorCode.DECONSTRUCT_NOT_FOUND);
        }
        return entity;
    }

    private Long parseId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            throw BizException.of(ErrorCode.DECONSTRUCT_NOT_FOUND);
        }
        try {
            return Long.parseLong(taskId.trim());
        } catch (NumberFormatException ex) {
            throw BizException.of(ErrorCode.DECONSTRUCT_NOT_FOUND);
        }
    }

    private DeconstructResult readResult(BookDigestEntity entity) {
        if (!hasText(entity.getResultJson())) {
            throw BizException.of(ErrorCode.DECONSTRUCT_NOT_READY);
        }
        try {
            return objectMapper.readValue(entity.getResultJson(), DeconstructResult.class);
        } catch (Exception ex) {
            log.warn("[deconstruct] parse result failed id={}: {}", entity.getId(), ex.getMessage());
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "拆书结果已损坏，请重新拆解");
        }
    }

    private DeconstructTaskVO toVO(BookDigestEntity entity) {
        DeconstructTaskVO vo = new DeconstructTaskVO();
        vo.setTaskId(String.valueOf(entity.getId()));
        vo.setStatus(entity.getStatus());
        vo.setPercent(entity.getPercent());
        vo.setStepNo(entity.getStepNo());
        vo.setStepTotal(entity.getStepTotal());
        vo.setStepLabel(entity.getStepLabel());
        vo.setMessage(entity.getErrorMessage());
        vo.setSourceName(entity.getSourceName());
        vo.setSourceChars(entity.getSourceChars());
        vo.setSourceChapters(entity.getSourceChapters());
        vo.setAdaptationLevel(entity.getAdaptationLevel());
        vo.setBackgroundDirection(entity.getBackgroundDirection());
        vo.setChapterTarget(entity.getChapterTarget());
        vo.setGeneratedNovelId(entity.getGeneratedNovelId());
        if (STATUS_SUCCEEDED.equals(entity.getStatus()) && hasText(entity.getResultJson())) {
            try {
                vo.setResult(objectMapper.readValue(entity.getResultJson(), DeconstructResult.class));
            } catch (Exception ex) {
                log.warn("[deconstruct] parse result failed id={}: {}", entity.getId(), ex.getMessage());
            }
        }
        return vo;
    }

    private DeconstructSummaryVO toSummary(BookDigestEntity entity) {
        DeconstructSummaryVO vo = new DeconstructSummaryVO();
        vo.setTaskId(String.valueOf(entity.getId()));
        vo.setSourceName(entity.getSourceName());
        vo.setSourceChars(entity.getSourceChars());
        vo.setAdaptationLevel(entity.getAdaptationLevel());
        vo.setAdaptationLabel(LEVEL_LABELS.getOrDefault(entity.getAdaptationLevel(), "中度"));
        vo.setChapterTarget(entity.getChapterTarget());
        vo.setStatus(entity.getStatus());
        vo.setTitle(entity.getResultTitle());
        vo.setGeneratedNovelId(entity.getGeneratedNovelId());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }

    /** 把分段提炼结果渲染成可读材料，控制总长避免把下一段模型撑爆。 */
    private String renderNotes(List<JsonNode> notes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            JsonNode node = notes.get(i);
            builder.append("[第 ").append(i + 1).append(" 段]\n")
                    .append("梗概：").append(abbreviate(textOf(node, "summary"), 620)).append("\n");
            List<String> characters = new ArrayList<>();
            for (JsonNode item : node.path("characters")) {
                String name = textOf(item, "name");
                if (!hasText(name)) {
                    continue;
                }
                String role = textOf(item, "role");
                String traits = textOf(item, "traits");
                characters.add(name + "（" + abbreviate(role == null ? "" : role, 40)
                        + (hasText(traits) ? "，" + abbreviate(traits, 40) : "") + "）");
            }
            if (!characters.isEmpty()) {
                builder.append("人物：").append(String.join("、", characters)).append("\n");
            }
            List<String> events = textList(node, "events");
            if (!events.isEmpty()) {
                builder.append("事件：").append(String.join("；", events)).append("\n");
            }
            List<String> settings = textList(node, "settings");
            if (!settings.isEmpty()) {
                builder.append("专名：").append(String.join("、", settings)).append("\n");
            }
            builder.append("\n");
        }
        String text = builder.toString().trim();
        return text.length() <= 14000 ? text : text.substring(0, 14000) + "\n…（纪要过长，已截断）";
    }

    /** 原文人名与专有名词，作为新作的禁用清单。 */
    private String collectNames(List<JsonNode> notes) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        LinkedHashSet<String> settings = new LinkedHashSet<>();
        for (JsonNode node : notes) {
            for (JsonNode item : node.path("characters")) {
                String name = textOf(item, "name");
                if (hasText(name) && name.length() <= 20) {
                    names.add(name);
                }
            }
            for (String item : textList(node, "settings")) {
                if (item.length() <= 24) {
                    settings.add(item);
                }
            }
        }
        StringBuilder builder = new StringBuilder();
        if (!names.isEmpty()) {
            builder.append("人名：").append(String.join("、", limit(names, 60))).append("\n");
        }
        if (!settings.isEmpty()) {
            builder.append("专名：").append(String.join("、", limit(settings, 60)));
        }
        return builder.toString().trim();
    }

    private List<String> limit(LinkedHashSet<String> source, int max) {
        List<String> list = new ArrayList<>();
        for (String item : source) {
            if (list.size() >= max) {
                break;
            }
            list.add(item);
        }
        return list;
    }

    private String renderSpine(DeconstructResult.Spine spine) {
        StringBuilder builder = new StringBuilder();
        builder.append("一句话内核：").append(nullToEmpty(spine.getLogline())).append("\n")
                .append("主题命题：").append(nullToEmpty(spine.getTheme())).append("\n")
                .append("主线走向：").append(nullToEmpty(spine.getMainline())).append("\n");
        if (!spine.getActs().isEmpty()) {
            builder.append("分幕：\n");
            for (DeconstructResult.Act act : spine.getActs()) {
                builder.append("- ").append(nullToEmpty(act.getName()))
                        .append("（").append(nullToEmpty(act.getRange())).append("）：")
                        .append(nullToEmpty(act.getContent())).append("\n");
            }
        }
        if (!spine.getTurns().isEmpty()) {
            builder.append("关键转折：\n");
            for (String turn : spine.getTurns()) {
                builder.append("- ").append(turn).append("\n");
            }
        }
        if (!spine.getCharacterFunctions().isEmpty()) {
            builder.append("人物功能：\n");
            for (DeconstructResult.Function function : spine.getCharacterFunctions()) {
                builder.append("- ").append(nullToEmpty(function.getFunction()))
                        .append("｜").append(nullToEmpty(function.getOriginalRole()))
                        .append("：").append(nullToEmpty(function.getRole())).append("\n");
            }
        }
        builder.append("结局：").append(nullToEmpty(spine.getEnding()));
        return builder.toString();
    }

    private String renderSetting(JsonNode node) {
        return "书名：" + nullToEmpty(textOf(node, "title")) + "\n"
                + "题材：" + nullToEmpty(textOf(node, "genre")) + "\n"
                + "标签：" + String.join("、", textList(node, "tags")) + "\n"
                + "简介：" + nullToEmpty(textOf(node, "synopsis")) + "\n"
                + "世界观：" + nullToEmpty(textOf(node, "worldview")) + "\n"
                + "背景：" + nullToEmpty(textOf(node, "background")) + "\n"
                + "核心冲突：" + nullToEmpty(textOf(node, "coreConflict")) + "\n"
                + "看点：" + String.join("；", textList(node, "highlights")) + "\n"
                + "文风：" + nullToEmpty(textOf(node, "style"));
    }

    private String renderCharacters(JsonNode node) {
        StringBuilder builder = new StringBuilder("人物表：\n");
        JsonNode protagonist = node.path("protagonist");
        if (protagonist.isObject()) {
            builder.append("- ").append(nullToEmpty(textOf(protagonist, "name")))
                    .append("（主角，").append(nullToEmpty(textOf(protagonist, "identity"))).append("）：")
                    .append(abbreviate(textOf(protagonist, "personality"), 120)).append("\n");
        }
        for (JsonNode item : node.path("characters")) {
            builder.append("- ").append(nullToEmpty(textOf(item, "name")))
                    .append("（").append(nullToEmpty(textOf(item, "rolePos"))).append("，")
                    .append(nullToEmpty(textOf(item, "identity"))).append("）：")
                    .append(abbreviate(textOf(item, "personality"), 100)).append("\n");
        }
        builder.append("\n关系：\n");
        for (JsonNode item : node.path("relations")) {
            builder.append("- ").append(nullToEmpty(textOf(item, "from"))).append(" — ")
                    .append(nullToEmpty(textOf(item, "to"))).append("（")
                    .append(nullToEmpty(textOf(item, "type"))).append("）\n");
        }
        builder.append("\n势力：\n");
        for (JsonNode item : node.path("factions")) {
            builder.append("- ").append(nullToEmpty(textOf(item, "name"))).append("（")
                    .append(nullToEmpty(textOf(item, "category"))).append("，")
                    .append(nullToEmpty(textOf(item, "status"))).append("）：")
                    .append(abbreviate(textOf(item, "remark"), 100)).append("\n");
        }
        return builder.toString();
    }

    private String writeProfile(CharacterDraft draft) {
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
            log.warn("[deconstruct] serialize profile failed: {}", ex.getMessage());
            return null;
        }
    }

    private void putIfText(Map<String, String> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String normalizeLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return "MEDIUM";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return LEVEL_LABELS.containsKey(value) ? value : "MEDIUM";
    }

    private String normalizeRole(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.contains("PROTAG") || value.contains("主角")) {
            return "PROTAGONIST";
        }
        if (value.contains("ANTAG") || value.contains("反派") || value.contains("对手")) {
            return "ANTAGONIST";
        }
        if (value.contains("MINOR") || value.contains("龙套")) {
            return "MINOR";
        }
        return "SUPPORTING";
    }

    private String normalizeRelationType(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (RELATION_TYPES.contains(value)) {
            return value;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.contains("师") && (text.contains("徒") || text.contains("弟子") || text.contains("父"))) {
            return "MASTER_APPRENTICE";
        }
        if (text.contains("亲") || text.contains("父") || text.contains("母") || text.contains("兄")
                || text.contains("弟") || text.contains("姐") || text.contains("妹") || text.contains("族")) {
            return "FAMILY";
        }
        if (text.contains("夫") || text.contains("妻") || text.contains("婚")) {
            return "SPOUSE";
        }
        if (text.contains("恋") || text.contains("爱") || text.contains("情")) {
            return "LOVER";
        }
        if (text.contains("友") || text.contains("朋")) {
            return "FRIEND";
        }
        if (text.contains("盟") || text.contains("合作") || text.contains("同伙") || text.contains("上下")) {
            return "ALLY";
        }
        if (text.contains("仇") || text.contains("敌")) {
            return "ENEMY";
        }
        if (text.contains("对手") || text.contains("竞争") || text.contains("宿敌")) {
            return "RIVAL";
        }
        if (text.contains("同门") || text.contains("同事") || text.contains("同僚")) {
            return "COLLEAGUE";
        }
        return "OTHER";
    }

    private String normalizeFactionStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (FACTION_STATUS.contains(value)) {
            return value;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.contains("蛰") || text.contains("潜伏") || text.contains("休眠")) {
            return "DORMANT";
        }
        if (text.contains("覆灭") || text.contains("毁") || text.contains("亡")) {
            return "DESTROYED";
        }
        if (text.contains("解散") || text.contains("解体")) {
            return "DISBANDED";
        }
        return "ACTIVE";
    }

    private String normalizeEventStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (EVENT_STATUS.contains(value)) {
            return value;
        }
        String text = raw == null ? "" : raw.trim();
        if (text.contains("进行") || text.contains("当前") || text.contains("触发")) {
            return "ONGOING";
        }
        if (text.contains("完成") || text.contains("已发生")) {
            return "DONE";
        }
        if (text.contains("废") || text.contains("弃") || text.contains("取消")) {
            return "ABANDONED";
        }
        return "UNUSED";
    }

    private List<String> normalizeList(List<String> raw, int max, int itemMaxLength) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String item : raw) {
            if (!hasText(item)) {
                continue;
            }
            String value = item.trim().replaceAll("^[#\\-*\\s]+", "");
            if (value.isEmpty() || result.contains(value)) {
                continue;
            }
            result.add(truncate(value, itemMaxLength));
            if (result.size() >= max) {
                break;
            }
        }
        return result;
    }

    private String textOf(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    private List<String> textList(JsonNode node, String field) {
        List<String> items = new ArrayList<>();
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return items;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    items.add(text);
                }
            }
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

    private boolean isTextFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return true;
        }
        String ext = lower.substring(dot + 1);
        return "txt".equals(ext) || "text".equals(ext) || "md".equals(ext) || "markdown".equals(ext);
    }

    private String joinLines(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return String.join("\n", items);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String value, int max) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
