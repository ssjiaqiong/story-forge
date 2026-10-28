package com.storyforge.modules.bookshelf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.bookshelf.dto.CharacterSaveRequest;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.EventGenerateRequest;
import com.storyforge.modules.bookshelf.dto.EventGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.TimelineRequest;
import com.storyforge.modules.bookshelf.dto.TimelineVO;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.novel.service.NovelService;
import com.storyforge.modules.search.dto.SearchBundleVO;
import com.storyforge.modules.search.service.SearchPromptFormatter;
import com.storyforge.modules.search.service.WebSearchOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 生成事件：事件只能通过 AI 创建，且必须关联参与角色。
 *
 * <p>生成时把现有角色名单交给模型；若某个关键角色不存在，模型可以在事件里带
 * {@code newCharacters}，由服务端补建并自动挂到该事件上——<b>每个事件最多新建 3 个角色</b>。
 * 所有写入在同一事务内，失败整批回滚。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiEventService {

    private static final int TIMEOUT_MS = 300_000;
    private static final int MAX_TOKENS = 4200;
    private static final int MAX_EVENTS = 10;
    private static final int MAX_EXISTING_IN_PROMPT = 30;
    private static final int MAX_CHARACTERS_IN_PROMPT = 25;
    /** 单个事件最多补建的角色数 */
    private static final int MAX_NEW_CHARACTERS_PER_EVENT = 3;
    /** 单次生成总共最多补建的角色数，避免一次对话造出一个加强连 */
    private static final int MAX_NEW_CHARACTERS_TOTAL = 12;
    private static final int MAX_PARTICIPANTS = 6;

    private static final String SYSTEM_PROMPT = """
            你是资深剧情策划，负责设计能被直接写进正文的事件，而不是一句话梗概。
            每个事件都必须有目标、阻碍、转折、代价与钩子；参与角色必须有各自立场，不能只是站桩。
            你的输出必须严格遵循给定 JSON 结构：只输出 JSON 对象，不要解释文字，不要使用 markdown 代码块。
            所有字段值都是单行纯文本：不要出现英文双引号与换行，需要引用时用「」。
            不要用「突然」「竟然」「原来」当转折；转折必须有前文铺垫。
            """;

    private static final String RETRY_SUFFIX = """
            上一次输出不是合法 JSON（多半是字符串里混入了英文双引号），这次务必只输出可被 JSON 解析器直接解析的对象。
            """;

    /** 状态：兼容英文编码与常见中文说法。 */
    private static final Map<String, String> STATUS_ALIASES = createStatusAliases();

    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;
    private final NovelAssetService assetService;
    private final NovelService novelService;
    private final WebSearchOrchestrator webSearchOrchestrator;
    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;

    @Transactional(rollbackFor = Exception.class)
    public EventGenerateResultVO generate(Long novelId, EventGenerateRequest request) {
        List<TimelineVO> existingEvents = assetService.listTimeline(novelId);
        List<CharacterVO> characters = assetService.listCharacters(novelId);
        String requirement = request == null || request.getPrompt() == null ? "" : request.getPrompt().trim();
        if (requirement.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先描述你想要的事件");
        }
        int count = request.getCount() == null ? 1 : Math.min(MAX_EVENTS, Math.max(1, request.getCount()));

        AiEndpoint base = endpointResolver.resolve(AiScene.EVENT_SUPPLY);
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS),
                base.extraHeaders(),
                0.9D,
                MAX_TOKENS);

        long startedAt = System.currentTimeMillis();
        SearchBundleVO search = search(novelId, requirement, request, endpoint, existingEvents, characters);
        java.util.Set<String> used = promptTemplateService.usedVariables(AiScene.EVENT_SUPPLY);
        NovelVO novel = loadNovel(novelId);
        String userPrompt = buildPrompt(existingEvents, characters, requirement, count, search, used, novel);
        String system = systemPrompt(novel, count, requirement);
        ChatResult reply = chat(endpoint, system, userPrompt);
        JsonNode root;
        try {
            root = parse(reply.content());
        } catch (BizException ex) {
            if (ex.getErrorCode() != ErrorCode.AI_PARSE_FAILED) {
                throw ex;
            }
            log.warn("[ai-event] parse failed, retry once novelId={}: {}", novelId, ex.getMessage());
            reply = chat(endpoint, system + RETRY_SUFFIX, userPrompt);
            root = parse(reply.content());
        }

        return persist(novelId, existingEvents, characters, root, reply.model(),
                System.currentTimeMillis() - startedAt, search);
    }

    /** 用户改过提示词就用模板，否则用内置常量。 */
    private String systemPrompt(NovelVO novel, int count, String requirement) {
        Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("novelTitle", novel == null || !hasText(novel.getTitle()) ? "这本书" : novel.getTitle());
        vars.put("genre", novel == null || !hasText(novel.getGenre()) ? "通用" : novel.getGenre());
        vars.put("count", String.valueOf(count));
        vars.put("authorRequirement", requirement == null ? "" : requirement);
        PromptVars.putNovel(vars, novel, 400);
        vars.put("constraints", PromptVars.constraint(novel, 1200));
        String rendered = promptTemplateService.system(AiScene.EVENT_SUPPLY, PromptVars.clean(vars));
        return hasText(rendered) ? rendered : SYSTEM_PROMPT;
    }

    /** 书名题材与创作约束只影响提示词文案，取不到就用兜底值，不能让事件生成失败。 */
    private NovelVO loadNovel(Long novelId) {
        if (novelId == null) {
            return null;
        }
        try {
            return novelService.detail(novelId);
        } catch (RuntimeException ex) {
            log.warn("[ai-event] load novel failed novelId={}: {}", novelId, ex.getMessage());
            return null;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 落库                                                               */
    /* ------------------------------------------------------------------ */

    private EventGenerateResultVO persist(Long novelId,
                                          List<TimelineVO> existingEvents,
                                          List<CharacterVO> existingCharacters,
                                          JsonNode root,
                                          String model,
                                          long latencyMs,
                                          SearchBundleVO search) {
        Set<String> titles = new LinkedHashSet<>();
        for (TimelineVO item : existingEvents) {
            if (item.title() != null) {
                titles.add(item.title().trim());
            }
        }

        Map<String, Long> nameToId = new LinkedHashMap<>();
        for (CharacterVO item : existingCharacters) {
            if (item.getName() != null) {
                nameToId.put(item.getName().trim(), item.getId());
            }
        }

        List<TimelineVO> createdEvents = new ArrayList<>();
        List<CharacterVO> createdCharacters = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        JsonNode eventsNode = root.path("events");
        if (eventsNode.isArray()) {
            for (JsonNode node : eventsNode) {
                if (createdEvents.size() >= MAX_EVENTS) {
                    break;
                }
                if (!node.isObject()) {
                    continue;
                }
                String title = truncate(textOf(node, "title"), 200);
                if (!hasText(title)) {
                    continue;
                }
                if (!titles.add(title)) {
                    skipped.add(title);
                    continue;
                }

                LinkedHashSet<String> participantNames = new LinkedHashSet<>(textList(node, "participants"));

                // 关键角色不存在时补建：单事件最多 3 个，单次最多 12 个
                int newInEvent = 0;
                JsonNode newNodes = node.path("newCharacters");
                if (newNodes.isArray()) {
                    for (JsonNode characterNode : newNodes) {
                        if (newInEvent >= MAX_NEW_CHARACTERS_PER_EVENT
                                || createdCharacters.size() >= MAX_NEW_CHARACTERS_TOTAL) {
                            break;
                        }
                        String name = truncate(textOf(characterNode, "name"), 64);
                        if (!hasText(name)) {
                            continue;
                        }
                        if (nameToId.containsKey(name)) {
                            participantNames.add(name);
                            continue;
                        }
                        CharacterVO vo = assetService.createCharacter(novelId,
                                toCharacterRequest(characterNode, name));
                        createdCharacters.add(vo);
                        nameToId.put(name, vo.getId());
                        participantNames.add(name);
                        newInEvent++;
                    }
                }

                List<Long> participantIds = new ArrayList<>();
                for (String name : participantNames) {
                    Long id = nameToId.get(name);
                    if (id != null && !participantIds.contains(id)) {
                        participantIds.add(id);
                        if (participantIds.size() >= MAX_PARTICIPANTS) {
                            break;
                        }
                    }
                }

                TimelineRequest save = new TimelineRequest();
                save.setTitle(title);
                save.setEventTime(truncate(textOf(node, "eventTime"), 64));
                save.setDescription(truncate(textOf(node, "description"), 4000));
                save.setStatus(normalizeStatus(textOf(node, "status")));
                save.setParticipantIds(participantIds);
                createdEvents.add(assetService.createTimeline(novelId, save));
            }
        }

        if (createdEvents.isEmpty()) {
            throw BizException.of(ErrorCode.EVENT_GENERATE_EMPTY);
        }

        log.info("[ai-event] generated novelId={} events={} newCharacters={} skipped={} model={} search={}({})",
                novelId, createdEvents.size(), createdCharacters.size(), skipped.size(), model,
                search != null && search.used(), search == null ? null : search.provider());
        return new EventGenerateResultVO(createdEvents, createdCharacters, skipped, model, latencyMs, search);
    }

    private CharacterSaveRequest toCharacterRequest(JsonNode node, String name) {
        CharacterSaveRequest save = new CharacterSaveRequest();
        save.setName(name);
        save.setRolePos(normalizeRole(textOf(node, "rolePos")));
        save.setIdentity(truncate(textOf(node, "identity"), 255));
        save.setGender(truncate(textOf(node, "gender"), 16));
        save.setAgeDesc(truncate(textOf(node, "ageDesc"), 32));
        save.setAppearance(truncate(textOf(node, "appearance"), 2000));
        save.setPersonality(truncate(textOf(node, "personality"), 2000));
        save.setMotivation(truncate(textOf(node, "motivation"), 2000));
        save.setGoldenFinger(truncate(textOf(node, "goldenFinger"), 2000));
        save.setArc(truncate(textOf(node, "arc"), 2000));
        return save;
    }

    /* ------------------------------------------------------------------ */
    /* 提示词与解析                                                       */
    /* ------------------------------------------------------------------ */

    private String buildPrompt(List<TimelineVO> existingEvents,
                               List<CharacterVO> characters,
                               String requirement,
                               int count,
                               SearchBundleVO search,
                               java.util.Set<String> used,
                               NovelVO novel) {
        StringBuilder builder = new StringBuilder();
        builder.append("【已有事件】\n");
        if (existingEvents.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            int limit = Math.min(existingEvents.size(), MAX_EXISTING_IN_PROMPT);
            for (int i = 0; i < limit; i++) {
                TimelineVO item = existingEvents.get(i);
                builder.append("- [").append(item.statusLabel() == null ? "未使用" : item.statusLabel())
                        .append("] ").append(item.title());
                if (hasText(item.eventTime())) {
                    builder.append("（").append(item.eventTime()).append("）");
                }
                builder.append("\n");
            }
            if (existingEvents.size() > limit) {
                builder.append("- 其余 ").append(existingEvents.size() - limit).append(" 个事件略\n");
            }
        }

        builder.append("\n【现有角色】\n");
        if (characters.isEmpty()) {
            builder.append("（暂无，需要你在 newCharacters 里补）\n");
        } else {
            int limit = Math.min(characters.size(), MAX_CHARACTERS_IN_PROMPT);
            for (int i = 0; i < limit; i++) {
                CharacterVO item = characters.get(i);
                builder.append("- ").append(item.getName())
                        .append("（").append(item.getRoleLabel() == null ? "角色" : item.getRoleLabel()).append("）");
                if (hasText(item.getIdentity())) {
                    builder.append("：").append(item.getIdentity());
                }
                builder.append("\n");
            }
            if (characters.size() > limit) {
                builder.append("- 其余 ").append(characters.size() - limit).append(" 个角色略\n");
            }
        }

        String searchBlock = SearchPromptFormatter.format(search, 6);
        if (!searchBlock.isEmpty()) {
            builder.append(searchBlock);
        }

        PromptVars.appendConstraint(builder, novel, used, 1200);
        if (PromptVars.emit(used, "authorRequirement")) {
            builder.append("\n【作者要求】\n").append(requirement).append("\n");
        }
        builder.append("\n【本次任务】\n生成 ").append(count).append(" 个新事件，不要与已有事件重复。\n");

        builder.append("""
                【事件设计方法】
                1. 每个事件按「地点或时机 → 谁想做什么 → 谁阻止 → 转折 → 代价 → 结尾钩子」组织，写成一个能直接扩写成场景的片段。
                2. 事件必须改变至少一件事：局势、关系、信息差、人物处境或资源归属；不能只是「两人聊了几句」。
                3. 事件之间要有因果或伏笔关系；后一个事件最好因前一个事件的代价而起。
                4. 至少一个事件让人物付出真实代价或面对道德两难，不要所有便宜都让主角占了。
                5. 参与角色要有各自立场：谁支持、谁反对、谁观望，写进描述里，不要只列名字。

                【输出格式】
                只输出一个 JSON 对象：
                {
                  "events": [
                    {
                      "eventTime": "具体故事内时间，如 第三年春末 / 主角十八岁那年冬天",
                      "title": "事件标题，20 字内：取最具体的那件事（一场交割、一次误杀、一张作废的契），不用「危机」「暗流」「惊变」这类空词，也不用四字成语式",
                      "description": "100~180 字：地点或时机 → 目标 → 阻碍 → 转折 → 代价 → 留下什么钩子",
                      "status": "UNUSED / ONGOING / DONE / ABANDONED",
                      "participants": ["参与角色姓名，2~5 个，必须来自现有角色或本事件的 newCharacters"],
                      "newCharacters": [
                        {
                          "name": "新角色姓名，不与现有角色重名；按本书的地域与阶层现起，避开烂大街的人名组合",
                          "rolePos": "SUPPORTING / ANTAGONIST / MINOR",
                          "identity": "身份职位",
                          "gender": "性别",
                          "ageDesc": "年龄描述",
                          "personality": "性格，40~80 字，含一个矛盾点或说话习惯",
                          "motivation": "动机，40~80 字，含欲望与顾忌"
                        }
                      ]
                    }
                  ]
                }

                【硬性要求】
                1. events 数量等于本次任务要求的数量，按故事内时间先后排列。
                2. 每个事件必须有 participants，优先复用【现有角色】；只有现有角色确实无法承担关键作用时，才在 newCharacters 里补建。
                3. newCharacters 每个事件最多 3 个；没有需要补建的角色就给空数组 []。已经在【现有角色】里的姓名不要重复创建。
                4. 补建的角色必须在事件里承担具体功能（传信、挡刀、告密、见证、提供技术），不要为凑数造人。
                5. 新事件默认给 UNUSED（还没写进正文）；只有作者明确要求「正在发生」时才用 ONGOING。
                6. 不要与【已有事件】重复；不要用「突然」「竟然」「原来」制造廉价反转。
                7. 所有字段值单行纯文本，不要出现英文双引号与换行，引用用「」。
                """);
        return builder.toString();
    }

    /** 联网检索：由 AI 自动判断是否需要联网、要搜什么，失败不影响生成。 */
    private SearchBundleVO search(Long novelId, String requirement, EventGenerateRequest request,
                                  AiEndpoint endpoint, List<TimelineVO> existingEvents,
                                  List<CharacterVO> characters) {
        boolean enabled = request == null || request.getUseSearch() == null || request.getUseSearch();
        if (!enabled) {
            return SearchBundleVO.skipped("本次未启用联网检索");
        }
        // 用户显式指定了检索词时，直接检索，不再让 AI 判断
        if (request != null && hasText(request.getSearchQuery())) {
            try {
                return webSearchOrchestrator.searchDirect(AiScene.EVENT_SUPPLY, request.getSearchQuery().trim(), null);
            } catch (RuntimeException ex) {
                log.warn("[ai-event] search failed novelId={}: {}", novelId, ex.toString());
                return new SearchBundleVO(false, null, null, request.getSearchQuery().trim(), null, List.of(),
                        "联网检索失败，已按无资料继续生成", 0);
            }
        }
        try {
            String context = buildContext(novelId, existingEvents, characters);
            return webSearchOrchestrator.decideAndSearch(AiScene.EVENT_SUPPLY, endpoint, context, requirement, null);
        } catch (RuntimeException ex) {
            log.warn("[ai-event] search failed novelId={}: {}", novelId, ex.toString());
            return new SearchBundleVO(false, null, null, requirement, null, List.of(),
                    "联网检索失败，已按无资料继续生成", 0);
        }
    }

    /** 构造给「联网判断」模型的小说上下文。 */
    private String buildContext(Long novelId, List<TimelineVO> existingEvents, List<CharacterVO> characters) {
        StringBuilder builder = new StringBuilder();
        try {
            NovelVO novel = novelService.detail(novelId);
            if (novel != null && hasText(novel.getTitle())) {
                builder.append("书名：").append(novel.getTitle());
                if (hasText(novel.getGenre())) {
                    builder.append("（").append(novel.getGenre()).append("）");
                }
                builder.append("\n");
            }
        } catch (RuntimeException ignored) {
            // 书名题材取不到不影响判断
        }
        builder.append("已有角色：");
        if (characters == null || characters.isEmpty()) {
            builder.append("（无）");
        } else {
            for (CharacterVO c : characters) {
                builder.append(c.getName()).append("、");
            }
        }
        builder.append("\n已有事件：");
        if (existingEvents == null || existingEvents.isEmpty()) {
            builder.append("（无）");
        } else {
            int n = Math.min(existingEvents.size(), 10);
            for (int i = 0; i < n; i++) {
                builder.append(existingEvents.get(i).title()).append("；");
            }
        }
        return builder.toString();
    }

    private ChatResult chat(AiEndpoint endpoint, String systemPrompt, String userPrompt) {
        try {
            return aiUsageService.chat(AiScene.EVENT_SUPPLY, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String reason = hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败";
            log.warn("[ai-event] chat failed: {}", ex.toString());
            throw BizException.of(ErrorCode.AI_CALL_FAILED, reason);
        }
    }

    private JsonNode parse(String content) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[ai-event] 模型未返回 JSON：{}", abbreviate(content));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型没有返回结构化事件，请重试");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[ai-event] parse failed: {} | raw={}", strictFailure.getMessage(), abbreviate(candidate));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的事件内容无法解析，请重试");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                               */
    /* ------------------------------------------------------------------ */

    private static Map<String, String> createStatusAliases() {
        Map<String, String> map = new HashMap<>();
        map.put("UNUSED", "UNUSED");
        map.put("未使用", "UNUSED");
        map.put("待用", "UNUSED");
        map.put("未开始", "UNUSED");
        map.put("ONGOING", "ONGOING");
        map.put("进行中", "ONGOING");
        map.put("进行", "ONGOING");
        map.put("正在发生", "ONGOING");
        map.put("DONE", "DONE");
        map.put("已完成", "DONE");
        map.put("完成", "DONE");
        map.put("已结束", "DONE");
        map.put("ABANDONED", "ABANDONED");
        map.put("废弃", "ABANDONED");
        map.put("放弃", "ABANDONED");
        map.put("作废", "ABANDONED");
        return map;
    }

    private String normalizeStatus(String raw) {
        if (!hasText(raw)) {
            return "UNUSED";
        }
        String key = raw.trim();
        String mapped = STATUS_ALIASES.get(key);
        if (mapped == null) {
            mapped = STATUS_ALIASES.get(key.toUpperCase(Locale.ROOT));
        }
        return mapped == null ? "UNUSED" : mapped;
    }

    private String normalizeRole(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.contains("PROTAG") || value.contains("主角")) {
            return "PROTAGONIST";
        }
        if (value.contains("ANTAG") || value.contains("反派") || value.contains("对手")) {
            return "ANTAGONIST";
        }
        if (value.contains("MINOR") || value.contains("龙套") || value.contains("路人")) {
            return "MINOR";
        }
        return "SUPPORTING";
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
        JsonNode value = node == null ? null : node.path(field);
        List<String> items = new ArrayList<>();
        if (value == null || value.isMissingNode() || value.isNull()) {
            return items;
        }
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
