package com.storyforge.modules.bookshelf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.infra.ai.PromptKit;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.bookshelf.dto.ChapterRequest;
import com.storyforge.modules.bookshelf.dto.ChapterVO;
import com.storyforge.modules.bookshelf.dto.CharacterRelationVO;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.FactionVO;
import com.storyforge.modules.bookshelf.dto.ItemVO;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.OutlineGenerateRequest;
import com.storyforge.modules.bookshelf.dto.OutlineGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.SettingDocVO;
import com.storyforge.modules.bookshelf.dto.TimelineRequest;
import com.storyforge.modules.bookshelf.dto.TimelineVO;
import com.storyforge.modules.bookshelf.entity.MasterOutlineEntity;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.search.dto.SearchBundleVO;
import com.storyforge.modules.search.service.SearchPromptFormatter;
import com.storyforge.modules.search.service.WebSearchOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 生成大纲：按作品世界观、背景、角色档案与事件池，一次产出多章
 * 「标题 + 摘要 + 参与人物 + 关联事件」并落库。
 *
 * <p>输入侧固定四块上下文：①世界观与背景（决定规则、时代、地理）；②角色档案
 * （决定谁出场、动机与关系）；③事件池（AI 已生成的事件，可被章节认领）；
 * ④已有章节（避免重复写）。输出侧要求每章点名参与人物与关联事件，服务端按
 * 姓名 / 标题映射成 ID 存入 {@code t_chapter_character}、{@code t_chapter_event}，
 * 后续写正文时据此取人物、物品与势力上下文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiOutlineService {

    private static final int TIMEOUT_MS = 300_000;
    private static final int MAX_TOKENS = 8000;
    private static final int MAX_CHAPTERS = 20;
    private static final int MAX_EXISTING_DETAIL = 12;
    private static final int MAX_EXISTING_TITLES = 60;
    private static final int MAX_CHARACTERS_IN_PROMPT = 30;
    private static final int MAX_RELATIONS_IN_PROMPT = 40;
    private static final int MAX_EVENTS_IN_PROMPT = 40;
    private static final int MAX_PARTICIPANTS_PER_CHAPTER = 8;
    private static final int MAX_EVENTS_PER_CHAPTER = 4;
    /** 每个章节维度允许新建的事件上限（人物 / 物品 / 势力见 NovelArchiveBootstrapService）。 */
    private static final int MAX_NEW_EVENTS_PER_CHAPTER = 2;
    private static final int MAX_NEW_EVENTS_TOTAL = 8;

    private static final String SYSTEM_PROMPT = """
            你是经验丰富的小说编剧兼大纲策划，擅长把设定拆成有因果、有钩子、可直接开写的章节。
            你不是在写故事梗概，而是在给每一章安排「谁在什么处境下、想做什么、被什么挡住、付出什么代价」。
            你必须尊重既有世界观与人物动机：人物的能力有边界，势力有规矩，事件有前因。
            你的输出必须严格遵循给定 JSON 结构：只输出 JSON 对象，不要解释文字，不要使用 markdown 代码块。
            所有字段值都是单行纯文本：不要出现英文双引号与换行，需要引用时用「」。
            """;

    private static final String RETRY_SUFFIX = """
            上一次输出不是合法 JSON（多半是字符串里混入了英文双引号），这次务必只输出可被 JSON 解析器直接读取的对象。
            """;

    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;
    private final BookshelfService bookshelfService;
    private final NovelAssetService assetService;
    private final CharacterAssetService characterAssetService;
    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;
    private final WebSearchOrchestrator webSearchOrchestrator;
    private final MasterOutlineService masterOutlineService;
    private final FactionService factionService;
    private final NovelArchiveBootstrapService archiveBootstrapService;
    private final com.storyforge.modules.autowrite.support.AutoWriteGuard autoWriteGuard;

    @Transactional(rollbackFor = Exception.class)
    public OutlineGenerateResultVO generate(Long novelId, OutlineGenerateRequest request) {
        autoWriteGuard.assertEditable(novelId);
        NovelDetailVO detail = bookshelfService.detail(novelId);
        List<ChapterVO> existing = assetService.listChapters(novelId);
        // 「未使用」事件排在最前面：模型从上往下读，先看到可用的，认领率更高
        List<TimelineVO> events = assetService.listTimeline(novelId).stream()
                .sorted(java.util.Comparator.comparingInt(event -> isUnusedEvent(event) ? 0 : 1))
                .toList();
        List<CharacterRelationVO> relations = assetService.listRelations(novelId);
        String requirement = request == null || request.getPrompt() == null
                ? "" : request.getPrompt().trim();
        if (requirement.isEmpty()) {
            requirement = "按现有设定自然推进故事，安排有冲突、有转折、结尾留钩子的章节。";
        }
        int count = request == null || request.getChapterCount() == null
                ? 5 : Math.min(MAX_CHAPTERS, Math.max(1, request.getChapterCount()));

        AiEndpoint base = endpointResolver.resolve(AiScene.OUTLINE_GEN);
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS),
                base.extraHeaders(),
                0.85D,
                MAX_TOKENS);

        long startedAt = System.currentTimeMillis();
        boolean noUnusedEvent = events.stream().noneMatch(this::isUnusedEvent);
        SearchBundleVO search = search(novelId, detail, requirement, request, endpoint, noUnusedEvent);
        List<CharacterVO> forced = resolveForced(detail.getCharacters(), request);
        MasterOutlineEntity masterOutline = resolveMasterOutline(novelId, request);
        String userPrompt = buildPrompt(detail, existing, events, relations, forced,
                requirement, count, request, search, masterOutline,
                promptTemplateService.usedVariables(AiScene.OUTLINE_GEN));
        java.util.Map<String, String> promptVars = new java.util.LinkedHashMap<>();
        var novelInfo = detail.getNovel();
        promptVars.put("novelTitle", novelInfo == null ? "这本书" : novelInfo.getTitle());
        promptVars.put("genre", novelInfo == null || !hasText(novelInfo.getGenre())
                ? "通用" : novelInfo.getGenre());
        promptVars.put("chapterCount", String.valueOf(count));
        promptVars.put("authorRequirement", requirement);
        PromptVars.putNovel(promptVars, novelInfo, 800);
        PromptVars.putSettings(promptVars, detail, 1600, 1200, 600, 500, 300);
        promptVars.put("constraints", PromptVars.constraint(detail, 1200));
        String system = promptTemplateService.system(AiScene.OUTLINE_GEN, PromptVars.clean(promptVars));
        if (!hasText(system)) {
            system = SYSTEM_PROMPT;
        }
        ChatResult reply = chat(endpoint, system, userPrompt);
        JsonNode root;
        try {
            root = parse(reply.content());
        } catch (BizException ex) {
            if (ex.getErrorCode() != ErrorCode.AI_PARSE_FAILED) {
                throw ex;
            }
            log.warn("[ai-outline] parse failed, retry once novelId={}: {}", novelId, ex.getMessage());
            reply = chat(endpoint, system + RETRY_SUFFIX, userPrompt);
            root = parse(reply.content());
        }

        return persist(novelId, existing, detail.getCharacters(), events, forced, request, root, reply.model(),
                System.currentTimeMillis() - startedAt, search, masterOutline,
                existingNames(assetService.listItems(novelId).stream().map(ItemVO::name).toList()),
                existingNames(factionService.list(novelId).stream().map(FactionVO::name).toList()));
    }

    /** 档案里已有的名字：补建时用来去重，避免同一个人物 / 道具 / 组织建两份。 */
    private Set<String> existingNames(List<String> names) {
        Set<String> result = new LinkedHashSet<>();
        for (String name : names) {
            if (hasText(name)) {
                result.add(name.trim());
            }
        }
        return result;
    }

    /** 解析本批要关联的总纲：可以不指定，指定时必须是本书的总纲。 */
    private MasterOutlineEntity resolveMasterOutline(Long novelId, OutlineGenerateRequest request) {
        if (request == null || request.getMasterOutlineId() == null || request.getMasterOutlineId() <= 0) {
            return null;
        }
        MasterOutlineEntity entity = masterOutlineService.require(request.getMasterOutlineId());
        if (!novelId.equals(entity.getNovelId())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "总纲不存在或不属于这本书");
        }
        return entity;
    }

    /** 作者指定的必出场人物：按 ID 反查角色，越权 / 已删除的 ID 直接忽略。 */
    private List<CharacterVO> resolveForced(List<CharacterVO> characters, OutlineGenerateRequest request) {
        if (request == null || request.getParticipantIds() == null || request.getParticipantIds().isEmpty()) {
            return List.of();
        }
        Map<Long, CharacterVO> byId = new LinkedHashMap<>();
        for (CharacterVO person : characters == null ? List.<CharacterVO>of() : characters) {
            byId.put(person.getId(), person);
        }
        List<CharacterVO> result = new ArrayList<>();
        for (Long id : request.getParticipantIds()) {
            CharacterVO person = id == null ? null : byId.get(id);
            if (person != null && !result.contains(person)) {
                result.add(person);
            }
            if (result.size() >= MAX_PARTICIPANTS_PER_CHAPTER) {
                break;
            }
        }
        return result;
    }

    /* ------------------------------------------------------------------ */
    /* 落库                                                               */
    /* ------------------------------------------------------------------ */

    private OutlineGenerateResultVO persist(Long novelId,
                                            List<ChapterVO> existing,
                                            List<CharacterVO> characters,
                                            List<TimelineVO> events,
                                            List<CharacterVO> forced,
                                            OutlineGenerateRequest request,
                                            JsonNode root,
                                            String model,
                                            long latencyMs,
                                            SearchBundleVO search,
                                            MasterOutlineEntity masterOutline,
                                            Set<String> existingItemNames,
                                            Set<String> existingFactionNames) {
        int count = request != null && request.getChapterCount() != null
                ? Math.min(MAX_CHAPTERS, Math.max(1, request.getChapterCount()))
                : Math.min(MAX_CHAPTERS, Math.max(1, root.path("chapters").size()));

        int volumeNo = request != null && request.getVolumeNo() != null
                ? request.getVolumeNo()
                : existing.isEmpty() ? 1 : existing.get(existing.size() - 1).volumeNo();
        String volumeTitle = request != null && hasText(request.getVolumeTitle())
                ? request.getVolumeTitle().trim()
                : existing.stream()
                .filter(item -> volumeNo == (item.volumeNo() == null ? 1 : item.volumeNo()))
                .map(ChapterVO::volumeTitle)
                .filter(this::hasText)
                .findFirst().orElse(null);
        int chapterNo = request != null && request.getStartChapterNo() != null
                ? request.getStartChapterNo()
                : existing.stream().mapToInt(item -> item.chapterNo() == null ? 0 : item.chapterNo())
                .max().orElse(0) + 1;

        Set<String> titles = new LinkedHashSet<>();
        for (ChapterVO item : existing) {
            if (item.title() != null) {
                titles.add(item.title().trim());
            }
        }

        Map<String, Long> characterIds = new LinkedHashMap<>();
        for (CharacterVO person : characters == null ? List.<CharacterVO>of() : characters) {
            if (hasText(person.getName())) {
                characterIds.put(person.getName().trim(), person.getId());
            }
        }
        Map<String, Long> eventIds = new LinkedHashMap<>();
        for (TimelineVO event : events) {
            if (hasText(event.title())) {
                eventIds.put(normalizeKey(event.title()), event.id());
            }
        }

        // 先落「初始化档案」：模型在大纲阶段就点名的人物 / 物品 / 势力，落库后本章就能直接引用
        boolean hasProtagonist = characters != null
                && characters.stream().anyMatch(person -> "PROTAGONIST".equals(person.getRolePos()));
        List<CharacterVO> newCharacters = archiveBootstrapService.createCharacters(novelId,
                root.path("newCharacters"), characterIds, hasProtagonist);
        List<ItemVO> newItems = archiveBootstrapService.createItems(novelId,
                root.path("newItems"), existingItemNames);
        List<FactionVO> newFactions = archiveBootstrapService.createFactions(novelId,
                root.path("newFactions"), existingFactionNames);

        List<ChapterVO> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<TimelineVO> newEvents = new ArrayList<>();
        Set<Long> claimedEventIds = new LinkedHashSet<>();
        JsonNode chapters = root.path("chapters");
        int index = 0;
        if (chapters.isArray()) {
            for (JsonNode node : chapters) {
                if (created.size() >= count) {
                    break;
                }
                index++;
                if (!node.isObject()) {
                    continue;
                }
                String title = truncate(textOf(node, "title"), 200);
                String summary = truncate(textOf(node, "summary"), 1200);
                if (!hasText(title)) {
                    continue;
                }
                if (!titles.add(title)) {
                    skipped.add(title);
                    continue;
                }

                ChapterRequest save = new ChapterRequest();
                save.setVolumeNo(volumeNo);
                save.setVolumeTitle(volumeTitle);
                save.setChapterNo(chapterNo);
                save.setTitle(title);
                save.setSummary(summary);
                save.setStatus("DRAFT");
                save.setParticipantIds(mergeParticipants(forced, node, characterIds));
                List<Long> chapterEventIds = claimPoolEvents(node, eventIds, claimedEventIds);
                chapterEventIds.addAll(createChapterEvents(novelId, node.path("newEvents"), eventIds, claimedEventIds,
                        newEvents, chapterEventIds.size()));
                save.setEventIds(chapterEventIds);
                if (masterOutline != null) {
                    save.setMasterOutlineId(masterOutline.getId());
                }
                created.add(assetService.createChapter(novelId, save));
                chapterNo++;
            }
            if (index == 0) {
                skipped.add("空大纲");
            }
        }

        if (created.isEmpty()) {
            throw BizException.of(ErrorCode.OUTLINE_GENERATE_EMPTY);
        }
        log.info("[ai-outline] generated novelId={} chapters={} links={} skipped={} volume={} model={} "
                        + "newCharacters={} newItems={} newFactions={} newEvents={} search={}({})",
                novelId, created.size(),
                created.stream().mapToInt(item -> item.participants().size() + item.events().size()).sum(),
                skipped.size(), volumeNo, model,
                newCharacters.size(), newItems.size(), newFactions.size(), newEvents.size(),
                search != null && search.used(), search == null ? null : search.provider());
        return new OutlineGenerateResultVO(created, skipped, model, latencyMs, search,
                newCharacters, newItems, newFactions, newEvents);
    }

    /** 指定人物优先，再补模型给出的人物；总数不超过单章上限。 */
    private List<Long> mergeParticipants(List<CharacterVO> forced, JsonNode node, Map<String, Long> characterIds) {
        List<Long> ids = new ArrayList<>();
        for (CharacterVO person : forced) {
            if (person.getId() != null && !ids.contains(person.getId())) {
                ids.add(person.getId());
            }
        }
        for (Long id : mapParticipants(node, characterIds)) {
            if (!ids.contains(id) && ids.size() < MAX_PARTICIPANTS_PER_CHAPTER) {
                ids.add(id);
            }
        }
        return ids.size() > MAX_PARTICIPANTS_PER_CHAPTER
                ? new ArrayList<>(ids.subList(0, MAX_PARTICIPANTS_PER_CHAPTER))
                : ids;
    }

    /** 人物按姓名映射；不在角色档案里的名字直接丢弃，避免造出幽灵角色。 */
    private List<Long> mapParticipants(JsonNode node, Map<String, Long> characterIds) {
        List<Long> ids = new ArrayList<>();
        for (String name : textList(node, "characters")) {
            Long id = characterIds.get(name.trim());
            if (id != null && !ids.contains(id)) {
                ids.add(id);
                if (ids.size() >= MAX_PARTICIPANTS_PER_CHAPTER) {
                    break;
                }
            }
        }
        return ids;
    }

    /**
     * 认领事件池：按标题映射（忽略空白与标点差异），且同一批里一个事件只给一章，
     * 避免「同一个事件被三章同时认领」写起来重复。
     */
    private List<Long> claimPoolEvents(JsonNode node, Map<String, Long> eventIds, Set<Long> claimed) {
        List<Long> ids = new ArrayList<>();
        for (String title : textList(node, "events")) {
            Long id = eventIds.get(normalizeKey(title));
            if (id != null && !claimed.contains(id) && !ids.contains(id)) {
                ids.add(id);
                claimed.add(id);
                if (ids.size() >= MAX_EVENTS_PER_CHAPTER) {
                    break;
                }
            }
        }
        return ids;
    }

    /**
     * 事件池里没有合适事件时模型给的新事件：落库成「未使用」事件并直接归本章，
     * 后续章节也能从事件池里认领它。
     *
     * @param existingInChapter 本章已经认领的事件数量，用于控制单章上限
     */
    private List<Long> createChapterEvents(Long novelId,
                                           JsonNode nodes,
                                           Map<String, Long> eventIds,
                                           Set<Long> claimed,
                                           List<TimelineVO> created,
                                           int existingInChapter) {
        List<Long> ids = new ArrayList<>();
        if (nodes == null || !nodes.isArray()) {
            return ids;
        }
        int inChapter = existingInChapter;
        for (JsonNode node : nodes) {
            if (inChapter >= MAX_EVENTS_PER_CHAPTER || created.size() >= MAX_NEW_EVENTS_TOTAL) {
                break;
            }
            if (!node.isObject()) {
                continue;
            }
            String title = truncate(textOf(node, "title"), 200);
            if (!hasText(title)) {
                continue;
            }
            String key = normalizeKey(title);
            Long known = eventIds.get(key);
            if (known != null) {
                // 模型把事件池里的事件又写进 newEvents：直接认领，不重复建
                if (!claimed.contains(known) && !ids.contains(known)) {
                    ids.add(known);
                    claimed.add(known);
                    inChapter++;
                }
                continue;
            }
            String source = textOf(node, "source");
            TimelineRequest save = new TimelineRequest();
            save.setTitle(title);
            save.setEventTime(truncate(textOf(node, "eventTime"), 64));
            save.setDescription(truncate(textOf(node, "description"), 4000));
            save.setStatus("UNUSED");
            TimelineVO vo = assetService.createTimeline(novelId, save);
            eventIds.put(key, vo.id());
            claimed.add(vo.id());
            created.add(vo);
            ids.add(vo.id());
            inChapter++;
            log.debug("[ai-outline] new event novelId={} title={} source={}", novelId, title, source);
        }
        return ids;
    }

    /* ------------------------------------------------------------------ */
    /* 提示词                                                             */
    /* ------------------------------------------------------------------ */

    private String buildPrompt(NovelDetailVO detail,
                               List<ChapterVO> existing,
                               List<TimelineVO> events,
                               List<CharacterRelationVO> relations,
                               List<CharacterVO> forced,
                               String requirement,
                               int count,
                               OutlineGenerateRequest request,
                               SearchBundleVO search,
                               MasterOutlineEntity masterOutline,
                               java.util.Set<String> used) {
        NovelVO novel = detail.getNovel();
        StringBuilder builder = new StringBuilder();
        if (masterOutline != null) {
            // 关联总纲时放在最前面：这是本批章节不得越出的剧情范围
            builder.append("【本批总纲】（硬约束：本章节大纲只能写这一段剧情，不得脱离或提前跳到后面）\n");
            builder.append("总纲标题：").append(masterOutline.getTitle()).append("\n");
            builder.append("总纲内容：").append(truncate(masterOutline.getContent(), 1200)).append("\n");
            builder.append("要求：本批每章都在这条总纲的范围内推进，按顺序拆解它的开局局面 → 主要矛盾 → 关键转折 → 结果；")
                    .append("不要引入总纲之外的重大转折，也不要提前写完总纲之后的内容。\n\n");
        }
        builder.append("【作品信息】\n");
        builder.append("书名：").append(novel.getTitle()).append("\n");
        if (hasText(novel.getGenre())) {
            builder.append("题材：").append(novel.getGenre()).append("\n");
        }
        if (novel.getTags() != null && !novel.getTags().isEmpty()) {
            builder.append("标签：").append(String.join("、", novel.getTags())).append("\n");
        }
        if (hasText(novel.getIntro())) {
            builder.append("简介：").append(truncate(novel.getIntro(), 800)).append("\n");
        }

        boolean anySetting = PromptVars.emit(used, "worldview") || PromptVars.emit(used, "background")
                || PromptVars.emit(used, "coreConflict") || PromptVars.emit(used, "highlights")
                || PromptVars.emit(used, "style");
        if (anySetting) {
            builder.append("\n【世界观与背景】（不可违背的硬设定：规则、代价、时代、地理、组织）\n");
            if (PromptVars.emit(used, "worldview")) {
                appendSetting(builder, detail, "WORLDVIEW", "世界观", 1600);
            }
            if (PromptVars.emit(used, "background")) {
                appendSetting(builder, detail, "BACKGROUND", "背景", 1200);
            }
            if (PromptVars.emit(used, "coreConflict")) {
                appendSetting(builder, detail, "CORE_CONFLICT", "核心冲突", 600);
            }
            if (PromptVars.emit(used, "highlights")) {
                appendSetting(builder, detail, "HIGHLIGHT", "核心看点", 500);
            }
            if (PromptVars.emit(used, "style")) {
                appendSetting(builder, detail, "STYLE", "文风基调", 300);
            }
        }

        builder.append("\n【角色档案】（本章人物必须从这个名单里选，姓名逐字一致）\n");
        List<CharacterVO> characters = detail.getCharacters() == null ? List.of() : detail.getCharacters();
        // 属性摘要：帮助模型判断"这个人能做什么、缺多少钱"，不参与人物姓名映射
        java.util.Map<Long, java.util.List<com.storyforge.modules.bookshelf.dto.AttributeVO>> attributes =
                characterAssetService.attributesByCharacter(novel.getId());
        if (characters.isEmpty()) {
            builder.append("（暂无角色档案。请在 newCharacters 里补建 1 个主角 + 2~4 个关键配角，"
                    + "补建后本章可以直接用这些姓名）\n");
        } else {
            int limit = Math.min(characters.size(), MAX_CHARACTERS_IN_PROMPT);
            for (int i = 0; i < limit; i++) {
                CharacterVO person = characters.get(i);
                builder.append("- ").append(person.getName())
                        .append("（").append(person.getRoleLabel() == null ? "角色" : person.getRoleLabel()).append("）");
                List<String> traits = new ArrayList<>();
                if (hasText(person.getIdentity())) {
                    traits.add("身份 " + truncate(person.getIdentity(), 60));
                }
                if (hasText(person.getGender())) {
                    traits.add(person.getGender());
                }
                if (hasText(person.getAgeDesc())) {
                    traits.add(person.getAgeDesc());
                }
                if (!traits.isEmpty()) {
                    builder.append("：").append(String.join("，", traits));
                }
                builder.append("\n");
                appendTrait(builder, "性格", person.getPersonality(), 100);
                appendTrait(builder, "动机", person.getMotivation(), 100);
                appendTrait(builder, "能力", person.getGoldenFinger(), 100);
                appendTrait(builder, "弧线", person.getArc(), 80);
                java.util.List<com.storyforge.modules.bookshelf.dto.AttributeVO> own = attributes.get(person.getId());
                if (own != null && !own.isEmpty()) {
                    builder.append("  当前属性：")
                            .append(own.stream()
                                    .limit(6)
                                    .map(item -> item.name()
                                            + (hasText(item.value()) ? " " + item.value() : "")
                                            + (hasText(item.unit()) ? item.unit() : ""))
                                    .collect(java.util.stream.Collectors.joining("、")))
                            .append("\n");
                }
            }
            if (characters.size() > limit) {
                builder.append("- 其余 ").append(characters.size() - limit).append(" 个次要角色略\n");
            }
        }

        if (!forced.isEmpty()) {
            builder.append("\n【本批指定出场人物】（每章都必须出现，姓名与档案一致）\n");
            for (CharacterVO person : forced) {
                builder.append("- ").append(person.getName())
                        .append("（").append(person.getRoleLabel() == null ? "角色" : person.getRoleLabel()).append("）");
                if (hasText(person.getIdentity())) {
                    builder.append("：").append(truncate(person.getIdentity(), 50));
                }
                builder.append("\n");
            }
        }

        if (!relations.isEmpty()) {
            builder.append("\n【人物关系】（写冲突时用得上，不要写出与关系矛盾的行为）\n");
            int limit = Math.min(relations.size(), MAX_RELATIONS_IN_PROMPT);
            for (int i = 0; i < limit; i++) {
                CharacterRelationVO relation = relations.get(i);
                builder.append("- ").append(relation.fromCharacterName()).append(" — ")
                        .append(relation.toCharacterName()).append("：")
                        .append(relation.relationLabel() == null ? relation.relationType() : relation.relationLabel());
                if (hasText(relation.description())) {
                    builder.append("（").append(truncate(relation.description(), 50)).append("）");
                }
                builder.append("\n");
            }
        }

        builder.append("\n【事件池】（事件仓库，章节能认领的就原样引用标题；一条事件在本批里只给一章）\n");
        if (events.isEmpty()) {
            builder.append("（事件仓库为空，本批按【事件引用策略】新建事件）\n");
        } else {
            int limit = Math.min(events.size(), MAX_EVENTS_IN_PROMPT);
            for (int i = 0; i < limit; i++) {
                TimelineVO event = events.get(i);
                builder.append("- ").append(isUnusedEvent(event) ? "【可用】" : "【已用】")
                        .append(event.title())
                        .append("［").append(event.statusLabel() == null ? "未使用" : event.statusLabel()).append("］");
                if (hasText(event.eventTime())) {
                    builder.append(" ").append(event.eventTime());
                }
                if (hasText(event.description())) {
                    builder.append("：").append(truncate(event.description(), 90));
                }
                if (event.participants() != null && !event.participants().isEmpty()) {
                    builder.append("｜参与：").append(String.join("、",
                            event.participants().stream().map(person -> person.name()).toList()));
                }
                builder.append("\n");
            }
            if (events.size() > limit) {
                builder.append("- 其余 ").append(events.size() - limit).append(" 个事件略\n");
            }
        }

        builder.append("""

                【事件引用策略】（按顺序判断，不要跳步）
                1. 先看【事件池】里标【可用】的事件：只要和本章情节吻合，就把标题原样写进本章 events（一章 0~3 个）。
                2. 事件池里没有合适的，再看下方【联网资料】：如果里面有现实世界的真实事件、真实案例与本章情节同类，
                   就把它改编成小说事件写进本章 newEvents，source 填 SEARCH。改编必须换掉真实人名、机构名与地名，
                   只保留事件结构与冲突逻辑；description 写清起因、经过、代价，60~150 字。
                3. 只有前两步都拿不到素材时，才允许原创事件，source 填 ORIGINAL。原创也要符合世界观与已有因果，
                   不要为了制造冲突硬造巧合。
                4. newEvents 一章最多 2 个；整批新建不超过章数的一半，能复用事件池就不要新建。
                5. 事件是「已经发生过或将发生的事」的卡，写清谁做了什么、结果如何；不要写成「某人可能会…」的设想。
                """);

        builder.append("\n【已有章节】\n");
        if (existing.isEmpty()) {
            builder.append("（暂无，这是全书开头）\n");
        } else {
            int detailFrom = Math.max(0, existing.size() - MAX_EXISTING_DETAIL);
            int titleFrom = Math.max(0, existing.size() - MAX_EXISTING_TITLES);
            if (titleFrom < detailFrom) {
                builder.append("更早章节（只列标题，避免与前文重复）：\n");
                for (int i = titleFrom; i < detailFrom; i++) {
                    ChapterVO item = existing.get(i);
                    builder.append("- 第 ").append(item.chapterNo()).append(" 章 ").append(item.title()).append("\n");
                }
            }
            builder.append("紧邻的章节（承接它们的时间、地点、情绪与未解决问题）：\n");
            for (int i = detailFrom; i < existing.size(); i++) {
                ChapterVO item = existing.get(i);
                builder.append("- 第 ").append(item.chapterNo()).append(" 章 ").append(item.title());
                if (hasText(item.summary())) {
                    builder.append("：").append(truncate(item.summary(), 200));
                }
                if (item.participants() != null && !item.participants().isEmpty()) {
                    builder.append("｜人物：").append(String.join("、",
                            item.participants().stream().map(person -> person.name()).toList()));
                }
                builder.append("\n");
            }
        }

        String searchBlock = SearchPromptFormatter.format(search, 6);
        if (!searchBlock.isEmpty()) {
            builder.append(searchBlock);
        }

        int startChapterNo = request != null && request.getStartChapterNo() != null
                ? request.getStartChapterNo()
                : existing.stream().mapToInt(item -> item.chapterNo() == null ? 0 : item.chapterNo())
                .max().orElse(0) + 1;
        if (PromptVars.emit(used, "authorRequirement")) {
            builder.append("\n【作者要求】\n").append(requirement).append("\n");
        }
        PromptVars.appendConstraint(builder, detail, used, 1200);
        builder.append("\n【本次任务】\n生成 ").append(count).append(" 章大纲，从第 ")
                .append(startChapterNo).append(" 章开始；与已有章节自然衔接，不要重复已写过的事件。\n");

        builder.append("""
                【章节设计方法】
                1. 每章按「场景 → 视角人物 → 目标 → 阻碍 → 转折 → 代价 → 结尾钩子」设计，摘要里要能看出这七件事。
                2. 每章必须改变局势：信息、关系、资源、位置或人物状态至少变一项；禁止写「过渡章」「铺垫章」。
                3. 每 3~5 章升级一次冲突；主线推进与人物关系推进至少各占一条。
                4. 人物行为要符合其档案里的动机、身份与能力边界；用了谁的视角，就只写他能看到、听到的信息。
                5. 世界观里的规则与代价必须生效：受伤要养、动用能力要付出、违反势力的规矩要承担后果。
                6. 每章指定 3~6 个参与人物（必须来自【角色档案】），其中至少一个与主角立场不同。
                7. 事件按【事件引用策略】处理：先认领事件池里标【可用】的事件，不够再用 newEvents 补；
                   一章 events + newEvents 合计不超过 3 个，且同一事件不要在两章重复出现。
                8. 至少埋一条可回收的伏笔（物件、台词、习惯、伤口、账目），并在后续章节安排回收。
                9. 章节标题取本章最具体的那件东西（物件、动作、台词、地点、数字），8~16 字之间浮动；
                   不要用「危机」「转机」「真相」「决战」「暗流」「惊变」这类空词，
                   不要写成四字成语式或「XX的XX」对称式；同一批标题的句式和字数要错开，
                   先看【已有章节】的标题怎么起的，换一种取法。
               10. 第一章必须承接上一章结尾的时间、地点或情绪；如果上一章以动作收尾，本章不能直接跳到平静日常。

                【档案补建规则】（人物 / 物品 / 势力）
                1. 只有在【角色档案】为空、或本章必须有一个档案里没有的关键角色（例如反派方的执行者）时，
                   才在 newCharacters 里补建；档案里已有的姓名绝对不要重复创建。
                2. 物品只在剧情必须靠它推进时才补建（关键道具、信物、凶器），newItems 一次最多 5 个。
                3. 势力只在剧情需要组织背景时才补建（公司、门派、监管机构），newFactions 一次最多 3 个。
                4. 补建的角色要能直接开写：identity / personality / motivation / goldenFinger 都不能空着。
                5. 补建是「一次性初始化」，本批之后的章节直接引用这些名字即可，不要再重复创建。
                6. 补建时姓名要有来处：姓氏随所属势力的地域与阶层走，同门共用辈分字，市井多用诨号；
                   不要用林逸、叶尘、苏晚、顾清寒这类被写烂的组合，也不要扎堆用同一个大姓。

                【输出格式】
                只输出一个 JSON 对象：
                {
                  "newCharacters": [
                    {
                      "name": "姓名，不要与已有角色重复",
                      "rolePos": "PROTAGONIST|SUPPORTING|ANTAGONIST|MINOR",
                      "identity": "身份与职业，40 字内",
                      "gender": "男|女|其他",
                      "ageDesc": "如 28 岁 / 中年",
                      "appearance": "外貌特征，60 字内",
                      "personality": "性格，60 字内",
                      "motivation": "欲望与恐惧，60 字内",
                      "goldenFinger": "能力边界，60 字内",
                      "arc": "成长弧线，40 字内"
                    }
                  ],
                  "newItems": [
                    {
                      "name": "物品名",
                      "category": "类别，如 法器 / 道具 / 文件",
                      "owner": "持有者姓名，可空",
                      "description": "外形与用途，80 字内",
                      "origin": "来历，80 字内",
                      "status": "ACTIVE|LOST|DESTROYED|ABANDONED|CONSUMED"
                    }
                  ],
                  "newFactions": [
                    {
                      "name": "势力名",
                      "category": "类别，如 公司 / 门派 / 监管机构",
                      "scope": "活动范围",
                      "remark": "规矩与行事风格，80 字内",
                      "status": "ACTIVE|DORMANT|DESTROYED|DISBANDED"
                    }
                  ],
                  "chapters": [
                    {
                      "title": "章节标题，取具体物件/动作/台词，不与已有章节重复",
                      "summary": "150~220 字：场景与视角 → 目标 → 阻碍 → 转折 → 代价 → 结尾钩子",
                      "characters": ["参与人物姓名，3~6 个，逐字来自角色档案或 newCharacters"],
                      "events": ["认领的事件标题，0~3 个，必须逐字来自事件池"],
                      "newEvents": [
                        {
                          "title": "新事件标题，12 字内",
                          "eventTime": "如 第三天夜里 / 十年前",
                          "description": "起因 → 经过 → 代价，60~150 字",
                          "source": "SEARCH|ORIGINAL"
                        }
                      ]
                    }
                  ]
                }

                【硬性要求】
                1. chapters 数量等于本次任务要求的数量，按故事先后排列。
                2. 每章必须有明确冲突与推进，不能只是过渡、总结或解释设定。
                3. 摘要要写具体：谁在什么地方、想做什么、被谁挡住、付出什么代价、留下什么悬念。
                4. 不要与【已有章节】重复已写过的事件；需要回顾时只写角色记得的细节，不要复述剧情。
                5. characters 只写姓名本身，events / newEvents 只写标题本身，不要加身份说明或书名号。
                6. 不要为了凑数补建档案：没有需要就分别给空数组 []。
                7. 所有字段值单行纯文本，不要出现英文双引号与换行，引用用「」。
                """);
        builder.append("\n").append(PromptKit.JSON_CONTRACT);
        return builder.toString();
    }

    private void appendTrait(StringBuilder builder, String label, String value, int max) {
        if (hasText(value)) {
            builder.append("  ").append(label).append("：").append(truncate(value, max)).append("\n");
        }
    }

    private void appendSetting(StringBuilder builder, NovelDetailVO detail,
                               String category, String label, int max) {
        if (detail.getSettings() == null) {
            return;
        }
        for (SettingDocVO doc : detail.getSettings()) {
            if (category.equals(doc.getCategory()) && hasText(doc.getContent())) {
                builder.append("● ").append(label).append("：").append(truncate(doc.getContent(), max)).append("\n");
                return;
            }
        }
    }

    /* ------------------------------------------------------------------ */

    /**
     * 联网检索：由 AI 自动判断是否需要联网、要搜什么，失败不影响生成。
     *
     * @param needEventMaterial 事件仓库里没有可用事件时为 true，会在判断提示里要求检索
     *                          「现实同类事件 / 案例」，供模型改编成小说事件
     */
    private SearchBundleVO search(Long novelId,
                                  NovelDetailVO detail,
                                  String requirement,
                                  OutlineGenerateRequest request,
                                  AiEndpoint endpoint,
                                  boolean needEventMaterial) {
        boolean enabled = request == null || request.getUseSearch() == null || request.getUseSearch();
        if (!enabled) {
            return SearchBundleVO.skipped("本次未启用联网检索");
        }
        if (request != null && hasText(request.getSearchQuery())) {
            try {
                return webSearchOrchestrator.searchDirect(AiScene.OUTLINE_GEN, request.getSearchQuery().trim(), null);
            } catch (RuntimeException ex) {
                log.warn("[ai-outline] search failed novelId={}: {}", novelId, ex.toString());
                return new SearchBundleVO(false, null, null, request.getSearchQuery().trim(), null, List.of(),
                        "联网检索失败，已按无资料继续生成", 0);
            }
        }
        try {
            String context = buildContext(detail);
            String hint = needEventMaterial
                    ? requirement + "\n补充说明：事件仓库里没有可直接复用的事件，请检索与该情节同类的现实真实事件或真实案例，"
                    + "供改编成小说事件使用。"
                    : requirement;
            return webSearchOrchestrator.decideAndSearch(AiScene.OUTLINE_GEN, endpoint, context, hint, null);
        } catch (RuntimeException ex) {
            log.warn("[ai-outline] search failed novelId={}: {}", novelId, ex.toString());
            return new SearchBundleVO(false, null, null, requirement, null, List.of(),
                    "联网检索失败，已按无资料继续生成", 0);
        }
    }

    private String buildContext(NovelDetailVO detail) {
        StringBuilder builder = new StringBuilder();
        NovelVO novel = detail.getNovel();
        if (novel != null && hasText(novel.getTitle())) {
            builder.append("书名：").append(novel.getTitle());
            if (hasText(novel.getGenre())) {
                builder.append("（").append(novel.getGenre()).append("）");
            }
            builder.append("\n");
        }
        if (detail.getSettings() != null) {
            for (SettingDocVO doc : detail.getSettings()) {
                if (hasText(doc.getContent())) {
                    builder.append("设定[").append(doc.getCategory()).append("]：")
                            .append(truncate(doc.getContent(), 120)).append("\n");
                }
            }
        }
        return builder.toString();
    }

    private ChatResult chat(AiEndpoint endpoint, String systemPrompt, String userPrompt) {
        try {
            return aiUsageService.chat(AiScene.OUTLINE_GEN, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String reason = hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败";
            log.warn("[ai-outline] chat failed: {}", ex.toString());
            throw BizException.of(ErrorCode.AI_CALL_FAILED, reason);
        }
    }

    private JsonNode parse(String content) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[ai-outline] 模型未返回 JSON：{}", abbreviate(content));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型没有返回结构化大纲，请重试");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[ai-outline] parse failed: {} | raw={}", strictFailure.getMessage(), abbreviate(candidate));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的大纲无法解析，请重试");
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

    /** 读取字符串数组；兼容模型把单个字符串写成逗号分隔的情况。 */
    private List<String> textList(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        List<String> result = new ArrayList<>();
        if (value == null || value.isMissingNode() || value.isNull()) {
            return result;
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                String text = item.asText("").trim();
                if (!text.isEmpty()) {
                    result.add(text);
                }
            }
            return result;
        }
        String text = value.asText("").trim();
        if (text.isEmpty()) {
            return result;
        }
        for (String piece : text.split("[、,，;；/]")) {
            String trimmed = piece.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 标题比对键：去掉空白与常见包裹符号，容忍模型多加的标点。 */
    private String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s「」『』《》\"'·]", "").trim();
    }

    /** 事件是否还没被任何章节用过：只有这种才优先推荐给模型。 */
    private boolean isUnusedEvent(TimelineVO event) {
        String status = event == null || event.status() == null ? "" : event.status();
        return status.isEmpty() || "UNUSED".equalsIgnoreCase(status);
    }

    private String truncate(String value, int max) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= 1000 ? text : text.substring(0, 1000) + "...";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
