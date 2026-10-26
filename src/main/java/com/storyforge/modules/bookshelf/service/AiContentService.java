package com.storyforge.modules.bookshelf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.util.WordCounts;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.PromptKit;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.bookshelf.dto.AttributeVO;
import com.storyforge.modules.bookshelf.dto.ChapterEventVO;
import com.storyforge.modules.bookshelf.dto.ChapterRequest;
import com.storyforge.modules.bookshelf.dto.ChapterVO;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.ContentGenerateRequest;
import com.storyforge.modules.bookshelf.dto.ContentGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.EventParticipantVO;
import com.storyforge.modules.bookshelf.dto.FactionMemberVO;
import com.storyforge.modules.bookshelf.dto.FactionVO;
import com.storyforge.modules.bookshelf.dto.InventoryVO;
import com.storyforge.modules.bookshelf.dto.ItemVO;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.SettingDocVO;
import com.storyforge.modules.bookshelf.dto.TimelineVO;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.search.dto.SearchBundleVO;
import com.storyforge.modules.search.service.SearchPromptFormatter;
import com.storyforge.modules.search.service.WebSearchOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 写正文：把「上下文」按写作时的真实需要分层注入。
 *
 * <p>上下文分五层，越近越详细：</p>
 * <ol>
 *   <li>本章：大纲全文 + 关联事件 + 参与人物的完整档案；</li>
 *   <li>上一章结尾 500 字（语气、场景、动作必须接得上）；</li>
 *   <li>前 10 章大纲（完整摘要，理清近期因果）；</li>
 *   <li>更早章节（前 50 章内，每章一句概括，防止长期设定打架）；</li>
 *   <li>物品与势力（参与人物持有的物品、所属势力与职位），保证道具与组织写得对。</li>
 * </ol>
 *
 * <p>输出为纯文本（不走 JSON），避免长文本被引号问题打断；支持覆盖或追加两种写入方式。
 * 字数按汉字统计，预计字数默认 2500。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiContentService {

    private static final int TIMEOUT_MS = 300_000;
    private static final int DEFAULT_TARGET_WORDS = 2500;
    private static final int MAX_TARGET_WORDS = 6000;
    private static final int MIN_TARGET_WORDS = 300;
    /** 前 10 章给完整大纲 */
    private static final int RECENT_OUTLINE_CHAPTERS = 10;
    /** 前 50 章给一句话概括 */
    private static final int EARLIER_SUMMARY_CHAPTERS = 50;
    private static final int PREVIOUS_TAIL_CHARS = 500;
    private static final int MAX_PARTICIPANTS_IN_PROMPT = 12;
    private static final int MAX_ITEMS_IN_PROMPT = 12;
    private static final int MAX_FACTIONS_IN_PROMPT = 8;
    private static final int MAX_FALLBACK_EVENTS = 6;
    /** 提示词里每人最多带几条属性 / 背包，防止上下文膨胀 */
    private static final int MAX_ATTRIBUTES = 6;
    private static final int MAX_INVENTORY = 8;
    /** 正文写完后「档案补建」这一步的限额：单章新面孔不宜太多，避免档案被一次撑爆 */
    private static final int MAX_BODY_NEW_CHARACTERS = 4;
    private static final int MAX_BODY_NEW_ITEMS = 3;
    private static final int MAX_BODY_NEW_FACTIONS = 2;
    private static final int ARCHIVE_TIMEOUT_MS = 180_000;
    private static final int ARCHIVE_MAX_TOKENS = 2600;
    /** 送进建档提示词的正文长度：一章正文整段给，太长的旧稿只取新生成的这段 */
    private static final int ARCHIVE_BODY_CHARS = 6000;
    private static final int MAX_NAMES_IN_ARCHIVE_PROMPT = 80;

    private static final String SYSTEM_PROMPT_BASE = """
            你就是这本书的作者，一位擅长写有画面感、节奏紧、对话自然的网络小说家。
            只输出本章正文：不要输出章节标题、序号、作者注释或创作说明，也不要写「好的」「以下是正文」。
            不要使用 markdown 标记，段落之间用空行分隔。
            文中人物、物品、势力必须与给定的设定一致：名字不改、能力不越界、归属不搞错。
            """;

    /**
     * 档案补建（第二步）：正文已经写完，让模型以「资料管理员」的身份把这一章里
     * 新出现并且后面还会用到的人物 / 关键道具 / 组织整理成结构化档案。
     *
     * <p>单独一次调用而不是让正文模型在同一次输出里附带 JSON：正文是纯文本输出，
     * 混进 JSON 契约会让长文被引号问题打断，两者分开更稳。</p>
     */
    private static final String ARCHIVE_SYSTEM_PROMPT = """
            你是小说编辑部的资料管理员，负责把刚写完的一章里新出现的人物、关键道具、组织补进档案。
            只输出 JSON 对象，不要解释文字，不要使用 markdown 代码块。
            所有字段值都是单行纯文本：不要出现英文双引号与换行，需要引用时用「」。
            宁可少建，也不要给路人、一次性物品、泛指的组织建档。
            """;

    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final BookshelfService bookshelfService;
    private final NovelAssetService assetService;
    private final FactionService factionService;
    private final CharacterAssetService characterAssetService;
    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;
    private final WebSearchOrchestrator webSearchOrchestrator;
    private final NovelArchiveBootstrapService archiveBootstrapService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /**
     * 生成本章正文。
     *
     * <p>不加事务：正文写入与后面的「档案补建」是两步独立的事——补建失败只记 warning，
     * 不能让作者辛苦等来的一章因为一个档案字段不合法而一起回滚。</p>
     */
    public ContentGenerateResultVO generate(Long chapterId, ContentGenerateRequest request) {
        ChapterVO chapter = assetService.getChapter(chapterId);
        NovelDetailVO detail = bookshelfService.detail(chapter.novelId());
        List<ChapterVO> chapters = assetService.listChapters(chapter.novelId());
        List<TimelineVO> events = assetService.listTimeline(chapter.novelId());
        List<ItemVO> items = assetService.listItems(chapter.novelId());
        List<FactionVO> factions = factionService.list(chapter.novelId());

        String requirement = request == null || request.getPrompt() == null ? "" : request.getPrompt().trim();
        int targetWords = request == null || request.getTargetWords() == null
                ? DEFAULT_TARGET_WORDS
                : Math.min(MAX_TARGET_WORDS, Math.max(MIN_TARGET_WORDS, request.getTargetWords()));
        String mode = request == null || request.getMode() == null ? "REPLACE" : request.getMode();

        AiEndpoint base = endpointResolver.resolve(AiScene.BODY_GEN);
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS),
                base.extraHeaders(),
                0.88D,
                Math.min(8192, targetWords * 2 + 800));

        long startedAt = System.currentTimeMillis();
        SearchBundleVO search = search(chapter, detail, requirement, request, endpoint);
        Map<String, String> promptVars = new java.util.LinkedHashMap<>();
        promptVars.put("novelTitle", detail.getNovel() == null ? "这本书" : detail.getNovel().getTitle());
        promptVars.put("genre", detail.getNovel() == null || detail.getNovel().getGenre() == null
                ? "通用" : detail.getNovel().getGenre());
        promptVars.put("chapterNo", String.valueOf(chapter.chapterNo()));
        promptVars.put("targetWords", String.valueOf(targetWords));
        // 上下文变量：模板里引用哪一个，user 段里同名块就不再生效，避免重复注入
        promptVars.putAll(contextVariables(detail, chapter, chapters, events, items, factions,
                requirement, search));
        String system = promptTemplateService.system(AiScene.BODY_GEN, promptVars);
        if (!hasText(system)) {
            system = systemPrompt(detail.getNovel());
        }
        java.util.Set<String> used = promptTemplateService.usedVariables(AiScene.BODY_GEN);
        String userPrompt = buildPrompt(detail, chapter, chapters, events, items, factions,
                requirement, targetWords, search, used);
        ChatResult reply = chat(endpoint, system, userPrompt);
        String generated = cleanGenerated(reply.content());
        if (!hasText(generated)) {
            throw BizException.of(ErrorCode.CONTENT_GENERATE_EMPTY);
        }

        String previous = chapter.content() == null ? "" : chapter.content().trim();
        String content = "APPEND".equals(mode) && !previous.isEmpty()
                ? previous + "\n\n" + generated
                : generated;

        ChapterRequest save = new ChapterRequest();
        save.setContent(content);
        save.setStatus("WRITING");
        ChapterVO saved = assetService.updateChapter(chapterId, save);

        // 正文落库后再补建档案：这一章新出现的人 / 道具 / 组织，档案里有了，下一章写作与分析才接得上
        ArchiveBootstrap archive = bootstrapArchive(chapter.novelId(), detail, generated, endpoint, items, factions);

        log.info("[ai-content] generated chapterId={} mode={} words={} target={} model={} participants={} events={} "
                        + "newCharacters={} newItems={} newFactions={}",
                chapterId, mode, WordCounts.countChinese(generated), targetWords, reply.model(),
                chapter.participants() == null ? 0 : chapter.participants().size(),
                chapter.events() == null ? 0 : chapter.events().size(),
                archive.characters().size(), archive.items().size(), archive.factions().size());
        return new ContentGenerateResultVO(
                chapterId,
                content,
                generated,
                saved.wordCount(),
                WordCounts.countChinese(generated),
                mode,
                reply.model(),
                System.currentTimeMillis() - startedAt,
                search,
                archive.characters(),
                archive.items(),
                archive.factions());
    }

    /* ------------------------------------------------------------------ */
    /* 生成阶段的档案补建                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * 把本章新出现的人物 / 物品 / 势力补进档案。
     *
     * <p>整段失败只记 warning：正文已经写好了，建档是附带收益，不能反过来把正文判为失败。</p>
     */
    private ArchiveBootstrap bootstrapArchive(Long novelId,
                                              NovelDetailVO detail,
                                              String text,
                                              AiEndpoint endpoint,
                                              List<ItemVO> items,
                                              List<FactionVO> factions) {
        if (!hasText(text)) {
            return ArchiveBootstrap.empty();
        }
        try {
            AiEndpoint archiveEndpoint = new AiEndpoint(
                    endpoint.baseUrl(), endpoint.apiKey(), endpoint.model(),
                    Math.max(endpoint.timeoutMs(), ARCHIVE_TIMEOUT_MS),
                    endpoint.extraHeaders(), 0.2D, ARCHIVE_MAX_TOKENS);
            ChatResult reply = aiUsageService.chat(AiScene.BODY_GEN, archiveEndpoint, List.of(
                    ChatMessage.system(ARCHIVE_SYSTEM_PROMPT),
                    ChatMessage.user(buildArchivePrompt(detail, items, factions, text))));
            JsonNode root = parseArchive(reply.content());
            if (root == null) {
                return ArchiveBootstrap.empty();
            }
            List<CharacterVO> characters = detail.getCharacters() == null ? List.of() : detail.getCharacters();
            Map<String, Long> characterIds = new LinkedHashMap<>();
            boolean hasProtagonist = false;
            for (CharacterVO person : characters) {
                if (hasText(person.getName())) {
                    characterIds.put(person.getName().trim(), person.getId());
                }
                if ("PROTAGONIST".equals(person.getRolePos())) {
                    hasProtagonist = true;
                }
            }
            Set<String> itemNames = new LinkedHashSet<>();
            for (ItemVO item : items) {
                if (hasText(item.name())) {
                    itemNames.add(item.name());
                }
            }
            Set<String> factionNames = new LinkedHashSet<>();
            for (FactionVO faction : factions) {
                if (hasText(faction.name())) {
                    factionNames.add(faction.name());
                }
            }
            List<CharacterVO> newCharacters = archiveBootstrapService.createCharacters(
                    novelId, root.path("newCharacters"), characterIds, hasProtagonist, MAX_BODY_NEW_CHARACTERS);
            List<ItemVO> newItems = archiveBootstrapService.createItems(
                    novelId, root.path("newItems"), itemNames, MAX_BODY_NEW_ITEMS);
            List<FactionVO> newFactions = archiveBootstrapService.createFactions(
                    novelId, root.path("newFactions"), factionNames, MAX_BODY_NEW_FACTIONS);
            return new ArchiveBootstrap(newCharacters, newItems, newFactions);
        } catch (RuntimeException ex) {
            log.warn("[ai-content] 档案补建失败，正文已保存：{}", ex.toString());
            return ArchiveBootstrap.empty();
        }
    }

    private String buildArchivePrompt(NovelDetailVO detail,
                                      List<ItemVO> items,
                                      List<FactionVO> factions,
                                      String text) {
        StringBuilder builder = new StringBuilder();
        builder.append("【已有角色】")
                .append(joinNames(detail.getCharacters() == null ? List.of()
                        : detail.getCharacters().stream().map(CharacterVO::getName).toList()))
                .append("\n");
        builder.append("【已有物品】")
                .append(joinNames(items.stream().map(ItemVO::name).toList()))
                .append("\n");
        builder.append("【已有势力】")
                .append(joinNames(factions.stream().map(FactionVO::name).toList()))
                .append("\n");

        builder.append("\n【本章正文】\n")
                .append(truncate(text, ARCHIVE_BODY_CHARS))
                .append("\n");

        builder.append("""

                【任务】找出本章出现、但不在上面名单里，并且后面还会继续用到的对象，整理成档案。
                建档标准：
                1. 人物：有姓名、有身份，不是只露一次面的路人；主角、反派、关键配角优先。
                2. 物品：推动剧情的关键道具（信物、凶器、核心设备、功法、证件），日常物件不建。
                3. 势力：有正式名称的组织（公司、门派、机构、家族）；「他们那一伙」这种泛称不建。
                4. 人物字段只用正文里已有的信息归纳，正文没写的留空，不要编：identity 身份职业、personality 性格、motivation 当前目的、goldenFinger 能力与限制、appearance 外貌、arc 可能的变化方向。
                5. rolePos 只能是 PROTAGONIST / SUPPORTING / ANTAGONIST / MINOR；物品 status 只能是 ACTIVE / DESTROYED / ABANDONED / LOST / CONSUMED；势力 status 只能是 ACTIVE / DORMANT / DESTROYED / DISBANDED。
                6. 名单里已有的名字不要重复输出；确实没有新东西时，三个数组都给空数组。

                【输出 JSON】
                {"newCharacters":[{"name":"","rolePos":"SUPPORTING","identity":"","gender":"","ageDesc":"","appearance":"","personality":"","motivation":"","goldenFinger":"","arc":""}],"newItems":[{"name":"","category":"","owner":"","description":"","origin":"","status":"ACTIVE"}],"newFactions":[{"name":"","category":"","scope":"","remark":"","status":"ACTIVE"}]}
                """);
        return builder.toString();
    }

    /** 名单只给姓名：足够判重，又不把提示词撑大。 */
    private String joinNames(List<String> names) {
        List<String> cleaned = names.stream()
                .filter(this::hasText)
                .map(String::trim)
                .distinct()
                .limit(MAX_NAMES_IN_ARCHIVE_PROMPT)
                .toList();
        return cleaned.isEmpty() ? "（暂无）" : String.join("、", cleaned);
    }

    /** 档案 JSON 解析：先严格、再容错修复，都失败就当作没有新档案（不抛异常）。 */
    private JsonNode parseArchive(String raw) {
        String candidate = com.storyforge.infra.ai.LenientJson.extractObject(raw);
        if (candidate.isEmpty()) {
            log.warn("[ai-content] 档案补建未返回 JSON：{}", abbreviate(raw));
            return null;
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(com.storyforge.infra.ai.LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[ai-content] 档案 JSON 解析失败：{} | raw={}", strictFailure.getMessage(),
                        abbreviate(candidate));
                return null;
            }
        }
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    private record ArchiveBootstrap(List<CharacterVO> characters, List<ItemVO> items, List<FactionVO> factions) {
        static ArchiveBootstrap empty() {
            return new ArchiveBootstrap(List.of(), List.of(), List.of());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 提示词                                                             */
    /* ------------------------------------------------------------------ */

    private String buildPrompt(NovelDetailVO detail,
                               ChapterVO chapter,
                               List<ChapterVO> chapters,
                               List<TimelineVO> events,
                               List<ItemVO> items,
                               List<FactionVO> factions,
                               String requirement,
                               int targetWords,
                               SearchBundleVO search,
                               java.util.Set<String> used) {
        NovelVO novel = detail.getNovel();
        PromptInputs in = promptInputs(detail, chapter, chapters, items, factions);
        Map<Long, CharacterVO> characterById = in.characterById();
        List<CharacterVO> participants = in.participants();
        Set<Long> participantIds = in.participantIds();
        Set<String> participantNames = in.participantNames();
        int index = in.index();

        StringBuilder builder = new StringBuilder();
        builder.append("【作品】").append(novel.getTitle());
        if (hasText(novel.getGenre())) {
            builder.append("（").append(novel.getGenre()).append("）");
        }
        builder.append("\n");
        if (novel.getTags() != null && !novel.getTags().isEmpty()) {
            builder.append("标签：").append(String.join("、", novel.getTags())).append("\n");
        }
        if (hasText(novel.getIntro())) {
            builder.append("简介：").append(truncate(novel.getIntro(), 500)).append("\n");
        }
        PromptVars.appendConstraint(builder, detail, used, 1200);

        builder.append("\n【本章】第 ").append(chapter.chapterNo()).append(" 章 ").append(chapter.title()).append("\n");
        if (emit(used, "chapterSummary")) {
            if (hasText(chapter.summary())) {
                builder.append("大纲：").append(chapter.summary().trim()).append("\n");
            } else {
                builder.append("大纲：（本章还没有大纲，按【近期走向】与前情自然推进，不要跳时间线）\n");
            }
        }

        if (emit(used, "events")) {
            appendChapterEvents(builder, chapter, events);
        }
        if (emit(used, "characters")) {
            appendParticipants(builder, participants, factions, characterById,
                    characterAssetService.attributesByCharacter(novel.getId()),
                    characterAssetService.inventoryByCharacter(novel.getId()));
        }

        List<ItemVO> relatedItems = in.relatedItems();
        if (emit(used, "items")) {
            builder.append(renderItems(relatedItems));
        }

        List<FactionVO> relatedFactions = in.relatedFactions();
        if (emit(used, "factions")) {
            builder.append(renderFactions(relatedFactions, participantIds));
        }

        if (emit(used, "previousSummary")) {
            builder.append(renderPrevious(chapters, index));
        }

        // 五类设定全被模板引用时不再留下一个空标题
        boolean anySetting = emit(used, "worldview") || emit(used, "background")
                || emit(used, "coreConflict") || emit(used, "highlights") || emit(used, "style");
        if (anySetting) {
            builder.append("\n【核心设定】\n");
        }
        if (emit(used, "worldview")) {
            appendSetting(builder, detail, "WORLDVIEW", "世界观", 1200);
        }
        if (emit(used, "background")) {
            appendSetting(builder, detail, "BACKGROUND", "背景", 800);
        }
        if (emit(used, "coreConflict")) {
            appendSetting(builder, detail, "CORE_CONFLICT", "核心冲突", 400);
        }
        if (emit(used, "highlights")) {
            appendSetting(builder, detail, "HIGHLIGHT", "核心看点", 300);
        }
        if (emit(used, "style")) {
            appendSetting(builder, detail, "STYLE", "文风基调", 300);
        }

        String searchBlock = SearchPromptFormatter.format(search, 6);
        if (!searchBlock.isEmpty() && emit(used, "searchResults")) {
            builder.append(searchBlock);
        }

        if (emit(used, "authorRequirement")) {
            builder.append("\n【作者要求】\n");
            builder.append(requirement.isEmpty() ? "按大纲写完整的一章，注意人物动机与场景细节。" : requirement).append("\n");
        }

        builder.append("\n【本章写作方法】\n")
                .append("1. 开头三句内进入场景、动作或对话，并与上一章结尾的时间、地点、动作接上；不要用天气或背景介绍开场。\n")
                .append("2. 本章至少完成一次「目标 → 阻碍 → 尝试 → 转折 → 代价」的推进；结束时局势必须和开始时不同。\n")
                .append("3. 大纲里写到的事件要落地成具体场景，不要一笔带过；关联事件里的人物立场要写出来。\n")
                .append("4. 出场人物只用已给出的人物档案里的角色；需要提到档案外的人时，只能是被提及，不要给对白与动作。\n")
                .append("5. 物品与势力按已给出的档案写：外形、磨损、来历细节可以复用，能力与数量不要超出档案。\n")
                .append("6. 对话要有潜台词：每个人说话方式不同，别让人物替作者解释设定。\n")
                .append("7. 描写只选 2~3 个关键感官细节（声音、气味、触感、光线），服务于人物处境，不要堆形容词。\n")
                .append("8. 视角统一：以【文风基调】约定的人称为准；未约定时用第三人称限知，不写视角人物看不到、听不到的信息。\n")
                .append("9. 人物行为符合其动机、身份与当前能力边界；受伤、消耗、欠债、承诺要延续到后文。\n")
                .append("10. 结尾停在具体动作、画面或未解决的问题上；不要总结道理，不要用反问句收尾。\n")
                .append("11. 不复述前文已交代过的信息；需要提及时用一句带过。\n")
                .append("\n【字数】\n目标 ").append(targetWords)
                .append(" 个汉字（只计汉字，标点、数字、字母不计），允许上下浮动 10%；")
                .append("不要为凑字数重复动作或对话，也不要把一个场景写成提纲。\n")
                .append(PromptKit.PROSE_RULES)
                .append("\n【禁止事项】\n")
                .append("- 禁止输出章节标题、作者注释、「本章完」或字数统计。\n")
                .append("- 禁止出现「根据搜索结果」「资料显示」这类出戏表达；与设定冲突时以设定为准。\n")
                .append("- 禁止让本章人物知道他们不可能知道的信息（未在场的对话、未公开的秘密）。\n");
        return builder.toString();
    }

    /**
     * 模板已经引用了这个上下文变量时返回 {@code false}：user 消息里不再重复输出同一块。
     *
     * <p>默认模板不引用任何上下文变量，返回集合为空，因此现有行为与改造前完全一致；
     * 只有用户主动在模板里写了 {@code {{worldview}}} 这类占位符，才会触发裁剪。</p>
     */
    private boolean emit(java.util.Set<String> used, String variable) {
        return used == null || !used.contains(variable);
    }

    /** 一次生成要用的中间量：buildPrompt 与模板变量共用，避免两处各算一遍。 */
    private record PromptInputs(int index,
                                Map<Long, CharacterVO> characterById,
                                List<CharacterVO> participants,
                                Set<Long> participantIds,
                                Set<String> participantNames,
                                List<ItemVO> relatedItems,
                                List<FactionVO> relatedFactions) {
    }

    private PromptInputs promptInputs(NovelDetailVO detail,
                                      ChapterVO chapter,
                                      List<ChapterVO> chapters,
                                      List<ItemVO> items,
                                      List<FactionVO> factions) {
        List<CharacterVO> characters = detail.getCharacters() == null ? List.of() : detail.getCharacters();
        Map<Long, CharacterVO> characterById = new LinkedHashMap<>();
        for (CharacterVO person : characters) {
            characterById.put(person.getId(), person);
        }
        int index = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).id().equals(chapter.id())) {
                index = i;
                break;
            }
        }
        List<CharacterVO> participants = resolveParticipants(chapter, characterById, characters);
        Set<Long> participantIds = new LinkedHashSet<>();
        Set<String> participantNames = new LinkedHashSet<>();
        for (CharacterVO person : participants) {
            participantIds.add(person.getId());
            participantNames.add(person.getName());
        }
        return new PromptInputs(index, characterById, participants, participantIds, participantNames,
                pickItems(items, participantNames), pickFactions(factions, participantIds));
    }

    /**
     * 上下文变量池：键名与 {@code PromptSceneRegistry} 里登记的变量一一对应。
     *
     * <p>值就是「本来要拼进 user 消息的那一段」，因此模板引用它之后，
     * user 段里同名块被裁掉，信息不重复也不丢失。</p>
     */
    private Map<String, String> contextVariables(NovelDetailVO detail,
                                                 ChapterVO chapter,
                                                 List<ChapterVO> chapters,
                                                 List<TimelineVO> events,
                                                 List<ItemVO> items,
                                                 List<FactionVO> factions,
                                                 String requirement,
                                                 SearchBundleVO search) {
        Map<String, String> vars = new LinkedHashMap<>();
        NovelVO novel = detail.getNovel();
        if (novel != null) {
            vars.put("tags", novel.getTags() == null ? "" : String.join("、", novel.getTags()));
            vars.put("synopsis", hasText(novel.getIntro()) ? novel.getIntro().trim() : "");
        }
        vars.put("chapterTitle", chapter.title() == null ? "" : chapter.title().trim());
        vars.put("chapterSummary", chapter.summary() == null ? "" : chapter.summary().trim());
        vars.put("authorRequirement", requirement);
        vars.put("constraints", PromptVars.constraint(detail, 1200));
        vars.put("worldview", settingText(detail, "WORLDVIEW", 1200));
        vars.put("background", settingText(detail, "BACKGROUND", 800));
        vars.put("coreConflict", settingText(detail, "CORE_CONFLICT", 400));
        vars.put("highlights", settingText(detail, "HIGHLIGHT", 300));
        vars.put("style", settingText(detail, "STYLE", 300));

        PromptInputs in = promptInputs(detail, chapter, chapters, items, factions);
        vars.put("events", renderChapterEvents(chapter, events));
        vars.put("characters", novel == null ? "" : renderParticipants(in.participants(), factions,
                in.characterById(),
                characterAssetService.attributesByCharacter(novel.getId()),
                characterAssetService.inventoryByCharacter(novel.getId())));
        vars.put("items", renderItems(in.relatedItems()));
        vars.put("factions", renderFactions(in.relatedFactions(), in.participantIds()));
        vars.put("previousSummary", renderPrevious(chapters, in.index()));
        vars.put("searchResults", SearchPromptFormatter.format(search, 6));
        // 空块统一成空串，模板里的占位符直接消失，不留下空段落
        vars.replaceAll((key, value) -> value == null ? "" : value);
        return vars;
    }

    private String settingText(NovelDetailVO detail, String category, int max) {
        if (detail.getSettings() == null) {
            return "";
        }
        for (SettingDocVO doc : detail.getSettings()) {
            if (category.equals(doc.getCategory()) && hasText(doc.getContent())) {
                return truncate(doc.getContent(), max);
            }
        }
        return "";
    }

    private String renderChapterEvents(ChapterVO chapter, List<TimelineVO> events) {
        StringBuilder builder = new StringBuilder();
        appendChapterEvents(builder, chapter, events);
        return builder.toString();
    }

    private String renderParticipants(List<CharacterVO> participants,
                                      List<FactionVO> factions,
                                      Map<Long, CharacterVO> characterById,
                                      Map<Long, List<AttributeVO>> attributes,
                                      Map<Long, List<InventoryVO>> inventories) {
        StringBuilder builder = new StringBuilder();
        appendParticipants(builder, participants, factions, characterById, attributes, inventories);
        return builder.toString();
    }

    private String renderItems(List<ItemVO> relatedItems) {
        StringBuilder builder = new StringBuilder();
        builder.append("\n【本章相关物品】\n");
        if (relatedItems.isEmpty()) {
            builder.append("（暂无物品档案；如情节需要道具，只用日常物件，不要凭空设定法宝或信物）\n");
            return builder.toString();
        }
        for (ItemVO item : relatedItems) {
            builder.append("- ").append(item.name());
            List<String> tags = new ArrayList<>();
            if (hasText(item.category())) {
                tags.add(item.category());
            }
            if (hasText(item.owner())) {
                tags.add("持有者 " + item.owner());
            }
            if (hasText(item.statusLabel())) {
                tags.add(item.statusLabel());
            }
            if (item.quantity() != null) {
                tags.add("数量 " + item.quantity());
            }
            if (!tags.isEmpty()) {
                builder.append("（").append(String.join("，", tags)).append("）");
            }
            builder.append("\n");
            if (hasText(item.description())) {
                builder.append("  外形与特征：").append(truncate(item.description(), 120)).append("\n");
            }
            if (hasText(item.origin())) {
                builder.append("  来历与故事：").append(truncate(item.origin(), 120)).append("\n");
            }
        }
        return builder.toString();
    }

    private String renderFactions(List<FactionVO> relatedFactions, Set<Long> participantIds) {
        StringBuilder builder = new StringBuilder();
        builder.append("\n【相关势力】\n");
        if (relatedFactions.isEmpty()) {
            builder.append("（暂无势力档案；不要临时发明组织，需要背景时用泛称）\n");
            return builder.toString();
        }
        for (FactionVO faction : relatedFactions) {
            builder.append("- ").append(faction.name());
            List<String> tags = new ArrayList<>();
            if (hasText(faction.category())) {
                tags.add(faction.category());
            }
            if (hasText(faction.statusLabel())) {
                tags.add(faction.statusLabel());
            }
            builder.append("（").append(String.join("，", tags)).append("）");
            if (hasText(faction.scope())) {
                builder.append(" 管辖/营业：").append(truncate(faction.scope(), 80));
            }
            builder.append("\n");
            if (hasText(faction.remark())) {
                builder.append("  备注：").append(truncate(faction.remark(), 100)).append("\n");
            }
            String positions = participantPositions(faction, participantIds);
            if (!positions.isEmpty()) {
                builder.append("  本章人物在其中的职位：").append(positions).append("\n");
            }
        }
        return builder.toString();
    }

    private String renderPrevious(List<ChapterVO> chapters, int index) {
        if (index <= 0) {
            return "\n【前情】\n（这是全书第一章，没有前情；开篇不要写「回想当年」，直接把人放进具体处境）\n";
        }
        StringBuilder builder = new StringBuilder();
        appendRecentOutlines(builder, chapters, index);
        appendEarlierSummary(builder, chapters, index);
        appendPreviousTail(builder, chapters.get(index - 1));
        return builder.toString();
    }

    /** 本章关联事件；没有关联时，退化为「可用事件」提示。 */
    private void appendChapterEvents(StringBuilder builder, ChapterVO chapter, List<TimelineVO> events) {
        Map<Long, TimelineVO> eventById = new LinkedHashMap<>();
        for (TimelineVO event : events) {
            eventById.put(event.id(), event);
        }
        List<TimelineVO> linked = new ArrayList<>();
        for (ChapterEventVO item : chapter.events() == null ? List.<ChapterEventVO>of() : chapter.events()) {
            TimelineVO event = eventById.get(item.id());
            if (event != null) {
                linked.add(event);
            }
        }
        if (!linked.isEmpty()) {
            builder.append("\n【本章关联事件】（必须写进本章，不要改名、不要改结果）\n");
            for (TimelineVO event : linked) {
                builder.append("- ").append(event.title())
                        .append("［").append(event.statusLabel() == null ? "未使用" : event.statusLabel()).append("］");
                if (hasText(event.eventTime())) {
                    builder.append(" ").append(event.eventTime());
                }
                builder.append("\n");
                if (hasText(event.description())) {
                    builder.append("  ").append(truncate(event.description(), 300)).append("\n");
                }
                if (event.participants() != null && !event.participants().isEmpty()) {
                    builder.append("  事件参与：").append(String.join("、",
                            event.participants().stream().map(EventParticipantVO::name).toList())).append("\n");
                }
            }
            return;
        }

        List<TimelineVO> usable = events.stream()
                .filter(item -> "ONGOING".equals(item.status()) || "UNUSED".equals(item.status()))
                .limit(MAX_FALLBACK_EVENTS)
                .toList();
        if (!usable.isEmpty()) {
            builder.append("\n【可选用事件】（本章未指定关联事件；如需推进，可择一使用，标题保持不变）\n");
            for (TimelineVO event : usable) {
                builder.append("- [").append(event.statusLabel()).append("] ").append(event.title());
                if (hasText(event.description())) {
                    builder.append("：").append(truncate(event.description(), 80));
                }
                builder.append("\n");
            }
        }
    }

    /** 本章参与人物档案：身份、性格、动机、能力边界、成长弧线与势力职位。 */
    private void appendParticipants(StringBuilder builder,
                                    List<CharacterVO> participants,
                                    List<FactionVO> factions,
                                    Map<Long, CharacterVO> characterById,
                                    Map<Long, List<AttributeVO>> attributes,
                                    Map<Long, List<InventoryVO>> inventories) {
        builder.append("\n【本章参与人物】\n");
        if (participants.isEmpty()) {
            builder.append("（本章未标注参与人物；只用主角推进，不要临时添加有名字的新角色）\n");
            return;
        }
        for (CharacterVO person : participants) {
            builder.append("- ").append(person.getName())
                    .append("（").append(person.getRoleLabel() == null ? "角色" : person.getRoleLabel()).append("）");
            List<String> traits = new ArrayList<>();
            if (hasText(person.getIdentity())) {
                traits.add("身份 " + truncate(person.getIdentity(), 50));
            }
            if (hasText(person.getGender())) {
                traits.add(person.getGender());
            }
            if (hasText(person.getAgeDesc())) {
                traits.add(person.getAgeDesc());
            }
            String positions = factionPositions(factions, person.getId());
            if (!positions.isEmpty()) {
                traits.add(positions);
            }
            if (!traits.isEmpty()) {
                builder.append("：").append(String.join("，", traits));
            }
            builder.append("\n");
            appendTrait(builder, "性格", person.getPersonality(), 100);
            appendTrait(builder, "动机", person.getMotivation(), 100);
            appendTrait(builder, "能力与限制", person.getGoldenFinger(), 100);
            appendTrait(builder, "弧线", person.getArc(), 80);
            appendTrait(builder, "外貌", person.getAppearance(), 80);
            appendAssets(builder, attributes.get(person.getId()), inventories.get(person.getId()));
        }
        if (participants.size() < 2) {
            builder.append("（本章只有一个人物标注；不要临时加入有名字的新角色，用无名配角或画外音处理）\n");
        }
    }

    /**
     * 属性与背包：让模型知道「这个人现在有多少钱、身上带着什么、数值到什么程度」。
     *
     * <p>都做条数截断，避免一个人物就把提示词撑满；货币排在最前，因为它最常被剧情用到。</p>
     */
    private void appendAssets(StringBuilder builder,
                              List<AttributeVO> attributes,
                              List<InventoryVO> inventories) {
        if (attributes != null && !attributes.isEmpty()) {
            List<String> parts = attributes.stream()
                    .limit(MAX_ATTRIBUTES)
                    .map(item -> {
                        String text = item.name();
                        if (hasText(item.value())) {
                            text += " " + item.value();
                        }
                        if (hasText(item.unit())) {
                            text += item.unit();
                        }
                        return text;
                    })
                    .toList();
            builder.append("  当前属性：").append(String.join("、", parts)).append("\n");
        }
        if (inventories == null || inventories.isEmpty()) {
            return;
        }
        List<String> owned = inventories.stream()
                .filter(item -> !"LOST".equals(item.status()) && !"CONSUMED".equals(item.status()))
                .limit(MAX_INVENTORY)
                .map(item -> {
                    StringBuilder text = new StringBuilder(item.name());
                    if (item.quantity() != null) {
                        text.append("×").append(item.quantity().stripTrailingZeros().toPlainString());
                    }
                    if (hasText(item.unit())) {
                        text.append(item.unit());
                    }
                    if ("CURRENCY".equals(item.entryType())) {
                        text.append("（随身钱财）");
                    }
                    return text.toString();
                })
                .toList();
        if (!owned.isEmpty()) {
            builder.append("  随身携带：").append(String.join("、", owned)).append("\n");
        }
    }

    /** 前 10 章大纲：完整摘要，让模型接住近期因果与伏笔。 */
    private void appendRecentOutlines(StringBuilder builder, List<ChapterVO> chapters, int index) {
        int from = Math.max(0, index - RECENT_OUTLINE_CHAPTERS);
        builder.append("\n【前 10 章大纲】（越靠后越近，本章必须接住最后一章的未解决问题）\n");
        for (int i = from; i < index; i++) {
            ChapterVO item = chapters.get(i);
            builder.append("- 第 ").append(item.chapterNo()).append(" 章 ").append(item.title());
            if (hasText(item.summary())) {
                builder.append("：").append(truncate(item.summary(), 220));
            }
            if (item.participants() != null && !item.participants().isEmpty()) {
                builder.append("｜人物：").append(String.join("、",
                        item.participants().stream().map(EventParticipantVO::name).toList()));
            }
            builder.append("\n");
        }
    }

    /** 前 50 章概括：更早的章节只给一句，防止长期设定与人际关系打架。 */
    private void appendEarlierSummary(StringBuilder builder, List<ChapterVO> chapters, int index) {
        int from = Math.max(0, index - EARLIER_SUMMARY_CHAPTERS);
        int to = Math.max(0, index - RECENT_OUTLINE_CHAPTERS);
        if (to <= from) {
            return;
        }
        builder.append("\n【更早剧情概括】（前 50 章内，每章一句；只用于对齐设定，不要复述）\n");
        for (int i = from; i < to; i++) {
            ChapterVO item = chapters.get(i);
            builder.append("- 第 ").append(item.chapterNo()).append(" 章 ").append(item.title());
            if (hasText(item.summary())) {
                builder.append("：").append(truncate(item.summary(), 60));
            }
            builder.append("\n");
        }
    }

    /** 上一章最后 500 字：语气、场景与动作必须接得上。 */
    private void appendPreviousTail(StringBuilder builder, ChapterVO previous) {
        builder.append("\n【上一章结尾】第 ").append(previous.chapterNo()).append(" 章 ")
                .append(previous.title()).append("\n");
        try {
            ChapterVO full = assetService.getChapter(previous.id());
            String content = full.content();
            if (!hasText(content)) {
                builder.append("（上一章还没有正文，只按大纲衔接）\n");
                return;
            }
            String tail = content.trim();
            if (tail.length() > PREVIOUS_TAIL_CHARS) {
                tail = tail.substring(tail.length() - PREVIOUS_TAIL_CHARS);
            }
            builder.append("最后 ").append(WordCounts.countChinese(tail)).append(" 个汉字：\n")
                    .append(tail).append("\n")
                    .append("（本章开头要接住这段的人、地点、动作或情绪，不要另起一场戏）\n");
        } catch (RuntimeException ex) {
            log.debug("[ai-content] previous chapter content unavailable: {}", ex.toString());
            builder.append("（上一章正文读取失败，只按大纲衔接）\n");
        }
    }

    /* ------------------------------------------------------------------ */
    /* 取数                                                               */
    /* ------------------------------------------------------------------ */

    private List<CharacterVO> resolveParticipants(ChapterVO chapter,
                                                  Map<Long, CharacterVO> characterById,
                                                  List<CharacterVO> characters) {
        List<CharacterVO> result = new ArrayList<>();
        List<EventParticipantVO> linked = chapter.participants() == null ? List.of() : chapter.participants();
        for (EventParticipantVO item : linked) {
            CharacterVO person = characterById.get(item.id());
            if (person != null) {
                result.add(person);
            }
            if (result.size() >= MAX_PARTICIPANTS_IN_PROMPT) {
                break;
            }
        }
        if (!result.isEmpty()) {
            return result;
        }
        // 兜底：章节还没标注人物时，用主角 + 配角前几位，保证正文至少有人可写
        for (CharacterVO person : characters) {
            result.add(person);
            if (result.size() >= 6) {
                break;
            }
        }
        return result;
    }

    /** 物品优先给「参与人物持有」的，其次给活跃的其它物品，避免上下文被无关道具挤满。 */
    private List<ItemVO> pickItems(List<ItemVO> items, Set<String> participantNames) {
        List<ItemVO> preferred = new ArrayList<>();
        List<ItemVO> others = new ArrayList<>();
        for (ItemVO item : items) {
            if (hasText(item.owner()) && participantNames.contains(item.owner().trim())) {
                preferred.add(item);
            } else if (!"DESTROYED".equals(item.status()) && !"LOST".equals(item.status())
                    && !"CONSUMED".equals(item.status())) {
                others.add(item);
            }
        }
        List<ItemVO> result = new ArrayList<>(preferred);
        for (ItemVO item : others) {
            if (result.size() >= MAX_ITEMS_IN_PROMPT) {
                break;
            }
            result.add(item);
        }
        return result.size() > MAX_ITEMS_IN_PROMPT ? result.subList(0, MAX_ITEMS_IN_PROMPT) : result;
    }

    /** 势力优先给参与人物所属的，其次补其它势力，保证组织规矩有依据。 */
    private List<FactionVO> pickFactions(List<FactionVO> factions, Set<Long> participantIds) {
        List<FactionVO> preferred = new ArrayList<>();
        List<FactionVO> others = new ArrayList<>();
        for (FactionVO faction : factions) {
            boolean hasMember = faction.members() != null && faction.members().stream()
                    .anyMatch(member -> participantIds.contains(member.characterId()));
            if (hasMember) {
                preferred.add(faction);
            } else if (!"DESTROYED".equals(faction.status()) && !"DISBANDED".equals(faction.status())) {
                others.add(faction);
            }
        }
        List<FactionVO> result = new ArrayList<>(preferred);
        for (FactionVO faction : others) {
            if (result.size() >= MAX_FACTIONS_IN_PROMPT) {
                break;
            }
            result.add(faction);
        }
        return result.size() > MAX_FACTIONS_IN_PROMPT ? result.subList(0, MAX_FACTIONS_IN_PROMPT) : result;
    }

    private String factionPositions(List<FactionVO> factions, Long characterId) {
        List<String> parts = new ArrayList<>();
        for (FactionVO faction : factions) {
            if (faction.members() == null) {
                continue;
            }
            for (FactionMemberVO member : faction.members()) {
                if (characterId.equals(member.characterId())) {
                    parts.add(faction.name() + (hasText(member.position()) ? " " + member.position() : " 成员"));
                }
            }
        }
        return parts.isEmpty() ? "" : "所属 " + String.join("、", parts);
    }

    private String participantPositions(FactionVO faction, Set<Long> participantIds) {
        if (faction.members() == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (FactionMemberVO member : faction.members()) {
            if (participantIds.contains(member.characterId())) {
                parts.add(member.name() + (hasText(member.position()) ? "（" + member.position() + "）" : ""));
            }
        }
        return String.join("、", parts);
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

    private SearchBundleVO search(ChapterVO chapter,
                                  NovelDetailVO detail,
                                  String requirement,
                                  ContentGenerateRequest request,
                                  AiEndpoint endpoint) {
        boolean enabled = request == null || request.getUseSearch() == null || request.getUseSearch();
        if (!enabled) {
            return SearchBundleVO.skipped("本次未启用联网检索");
        }
        if (request != null && hasText(request.getSearchQuery())) {
            try {
                return webSearchOrchestrator.searchDirect(AiScene.BODY_GEN, request.getSearchQuery().trim(), null);
            } catch (RuntimeException ex) {
                log.warn("[ai-content] search failed chapterId={}: {}", chapter.id(), ex.toString());
                return new SearchBundleVO(false, null, null, request.getSearchQuery().trim(), null, List.of(),
                        "联网检索失败，已按无资料继续生成", 0);
            }
        }
        try {
            String context = buildContext(detail, chapter);
            return webSearchOrchestrator.decideAndSearch(AiScene.BODY_GEN, endpoint, context, requirement, null);
        } catch (RuntimeException ex) {
            log.warn("[ai-content] search failed chapterId={}: {}", chapter.id(), ex.toString());
            return new SearchBundleVO(false, null, null, requirement, null, List.of(),
                    "联网检索失败，已按无资料继续生成", 0);
        }
    }

    private String buildContext(NovelDetailVO detail, ChapterVO chapter) {
        StringBuilder builder = new StringBuilder();
        NovelVO novel = detail.getNovel();
        if (novel != null && hasText(novel.getTitle())) {
            builder.append("书名：").append(novel.getTitle());
            if (hasText(novel.getGenre())) {
                builder.append("（").append(novel.getGenre()).append("）");
            }
            builder.append("\n");
        }
        builder.append("本章：").append(chapter.title());
        if (hasText(chapter.summary())) {
            builder.append("（").append(truncate(chapter.summary(), 120)).append("）");
        }
        builder.append("\n");
        return builder.toString();
    }

    private String systemPrompt(NovelVO novel) {
        StringBuilder builder = new StringBuilder();
        builder.append("你是《").append(novel == null || !hasText(novel.getTitle()) ? "这本书" : novel.getTitle())
                .append("》的作者");
        if (novel != null && hasText(novel.getGenre())) {
            builder.append("，一位专注于").append(novel.getGenre()).append("类型的网络小说家");
        }
        builder.append("。\n").append(SYSTEM_PROMPT_BASE);
        return builder.toString();
    }

    private ChatResult chat(AiEndpoint endpoint, String systemPrompt, String userPrompt) {
        try {
            return aiUsageService.chat(AiScene.BODY_GEN, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String reason = hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败";
            log.warn("[ai-content] chat failed: {}", ex.toString());
            throw BizException.of(ErrorCode.AI_CALL_FAILED, reason);
        }
    }

    /** 去掉代码围栏、模型自加的章节标题与结尾说明。 */
    private String cleanGenerated(String raw) {
        if (!hasText(raw)) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            text = firstLineEnd > 0 ? text.substring(firstLineEnd + 1) : text;
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        String[] lines = text.split("\\R", 2);
        if (lines.length > 1 && lines[0].trim().matches("^第[0-9零一二三四五六七八九十百千两]+[章节回].{0,40}$")) {
            text = lines[1].trim();
        }
        text = text.replaceAll("(?m)^\\s*[（(]?(本章完|全文完|未完待续|字数[:：]?\\s*\\d+)[）)]?\\s*$", "").trim();
        return text;
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
}
