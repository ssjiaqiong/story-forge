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
import com.storyforge.modules.bookshelf.dto.CharacterGenerateRequest;
import com.storyforge.modules.bookshelf.dto.CharacterGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.CharacterRelationVO;
import com.storyforge.modules.bookshelf.dto.CharacterSaveRequest;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.FactionVO;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.RelationRequest;
import com.storyforge.modules.bookshelf.dto.SettingDocVO;
import com.storyforge.modules.novel.dto.NovelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 创建角色：一段提示语 → 一批角色档案 + 它们之间的关系。
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>提示词带上「作品信息 + 世界观/背景/核心冲突/看点」四类设定，新角色必须落在已验证的世界规则里；</li>
 *   <li>已有角色不只给姓名，还给身份、性格、动机摘要与所属势力，让模型知道谁已经存在、谁是主角；</li>
 *   <li>已有关系网一并给出，新关系不得与既有关系矛盾，优先把新角色挂到现有角色身上；</li>
 *   <li>关系只让模型给「姓名」，落库前由服务端映射成角色 ID，映射不上的关系直接丢弃；</li>
 *   <li>重名角色跳过而不是覆盖；已有主角时新角色不会被再设为主角；</li>
 *   <li>全部写入在同一事务里，AI 失败或没有可用角色时不留脏数据。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCharacterService {

    private static final int TIMEOUT_MS = 300_000;
    private static final int MAX_TOKENS = 3200;
    private static final int MAX_RELATIONS = 20;
    private static final int MAX_CHARACTERS = 8;
    /** 提示词里最多列多少个已有角色：太多了模型会失焦，也白烧 token */
    private static final int MAX_CHARACTERS_IN_PROMPT = 30;
    /** 提示词里最多列多少条已有关系 */
    private static final int MAX_RELATIONS_IN_PROMPT = 24;

    private static final String SYSTEM_PROMPT = """
            你是资深角色设定师兼关系设计师，擅长让角色自己制造冲突、推动剧情，而不是等着作者安排。
            你的输出必须严格遵循给定 JSON 结构：只输出 JSON 对象，不要解释文字，不要使用 markdown 代码块。
            所有字段值都是单行纯文本：不要出现英文双引号与换行，需要引用时用「」。
            不要写「天赋异禀」「深不可测」「倾国倾城」这类标签；能力要写边界与代价，人物要写欲望与缺陷。
            """;

    private static final String RETRY_SUFFIX = """
            上一次输出不是合法 JSON（多半是字符串里混入了英文双引号），这次务必只输出可被 JSON 解析器直接解析的对象。
            """;

    /** 关系类型：既认英文编码，也认模型常写的中文说法。 */
    private static final Map<String, String> RELATION_ALIASES = createRelationAliases();

    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;
    private final NovelAssetService assetService;
    private final BookshelfService bookshelfService;
    private final FactionService factionService;
    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;

    @Transactional(rollbackFor = Exception.class)
    public CharacterGenerateResultVO generate(Long novelId, CharacterGenerateRequest request) {
        NovelDetailVO detail = loadDetail(novelId);
        List<CharacterVO> existing = detail != null && detail.getCharacters() != null
                ? detail.getCharacters()
                : assetService.listCharacters(novelId);
        String requirement = request == null || request.getPrompt() == null ? "" : request.getPrompt().trim();
        if (requirement.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先描述你想要的角色");
        }
        int count = request.getCount() == null ? 2 : Math.min(6, Math.max(1, request.getCount()));

        AiEndpoint base = endpointResolver.resolve(AiScene.CHARACTER_BUILD);
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS),
                base.extraHeaders(),
                0.85D,
                MAX_TOKENS);

        long startedAt = System.currentTimeMillis();
        String userPrompt = buildPrompt(detail, existing, listRelationsQuietly(novelId),
                listFactionsQuietly(novelId), requirement, count);
        String system = systemPrompt(detail, count);
        ChatResult reply = chat(endpoint, system, userPrompt);
        JsonNode root;
        try {
            root = parse(reply.content());
        } catch (BizException ex) {
            if (ex.getErrorCode() != ErrorCode.AI_PARSE_FAILED) {
                throw ex;
            }
            log.warn("[ai-character] parse failed, retry once novelId={}: {}", novelId, ex.getMessage());
            reply = chat(endpoint, system + RETRY_SUFFIX, userPrompt);
            root = parse(reply.content());
        }

        return persist(novelId, existing, root, reply.model(), System.currentTimeMillis() - startedAt);
    }

    /** 用户改过提示词就用模板，否则用内置常量。 */
    private String systemPrompt(NovelDetailVO detail, int count) {
        com.storyforge.modules.novel.dto.NovelVO novel = detail == null ? null : detail.getNovel();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("novelTitle", novel == null || !hasText(novel.getTitle()) ? "这本书" : novel.getTitle());
        vars.put("genre", novel == null || !hasText(novel.getGenre()) ? "通用" : novel.getGenre());
        vars.put("count", String.valueOf(count));
        String rendered = promptTemplateService.system(AiScene.CHARACTER_BUILD, vars);
        return hasText(rendered) ? rendered : SYSTEM_PROMPT;
    }

    /** 详情取不到只影响提示词丰富度，不能让角色生成失败。 */
    private NovelDetailVO loadDetail(Long novelId) {
        if (novelId == null) {
            return null;
        }
        try {
            return bookshelfService.detail(novelId);
        } catch (RuntimeException ex) {
            log.warn("[ai-character] load novel failed novelId={}: {}", novelId, ex.getMessage());
            return null;
        }
    }

    private List<CharacterRelationVO> listRelationsQuietly(Long novelId) {
        try {
            List<CharacterRelationVO> relations = assetService.listRelations(novelId);
            return relations == null ? List.of() : relations;
        } catch (RuntimeException ex) {
            log.warn("[ai-character] list relations failed novelId={}: {}", novelId, ex.getMessage());
            return List.of();
        }
    }

    private List<FactionVO> listFactionsQuietly(Long novelId) {
        try {
            List<FactionVO> factions = factionService.list(novelId);
            return factions == null ? List.of() : factions;
        } catch (RuntimeException ex) {
            log.warn("[ai-character] list factions failed novelId={}: {}", novelId, ex.getMessage());
            return List.of();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 落库                                                               */
    /* ------------------------------------------------------------------ */

    private CharacterGenerateResultVO persist(Long novelId,
                                              List<CharacterVO> existing,
                                              JsonNode root,
                                              String model,
                                              long latencyMs) {
        Map<String, Long> nameToId = new LinkedHashMap<>();
        boolean protagonistTaken = false;
        for (CharacterVO item : existing) {
            if (item.getName() != null) {
                nameToId.put(item.getName().trim(), item.getId());
            }
            if ("PROTAGONIST".equals(item.getRolePos())) {
                protagonistTaken = true;
            }
        }

        List<CharacterVO> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        JsonNode charactersNode = root.path("characters");
        if (charactersNode.isArray()) {
            for (JsonNode node : charactersNode) {
                if (created.size() >= Math.min(MAX_CHARACTERS, charactersNode.size())) {
                    break;
                }
                if (!node.isObject()) {
                    continue;
                }
                String name = truncate(textOf(node, "name"), 64);
                if (!hasText(name) || nameToId.containsKey(name)) {
                    if (hasText(name)) {
                        skipped.add(name);
                    }
                    continue;
                }
                String rolePos = normalizeRole(textOf(node, "rolePos"));
                if ("PROTAGONIST".equals(rolePos)) {
                    if (protagonistTaken) {
                        rolePos = "SUPPORTING";
                    } else {
                        protagonistTaken = true;
                    }
                }
                CharacterSaveRequest save = new CharacterSaveRequest();
                save.setName(name);
                save.setRolePos(rolePos);
                save.setIdentity(textOf(node, "identity"));
                save.setGender(textOf(node, "gender"));
                save.setAgeDesc(textOf(node, "ageDesc"));
                save.setAppearance(truncate(textOf(node, "appearance"), 2000));
                save.setPersonality(truncate(textOf(node, "personality"), 2000));
                save.setMotivation(truncate(textOf(node, "motivation"), 2000));
                save.setGoldenFinger(truncate(textOf(node, "goldenFinger"), 2000));
                save.setArc(truncate(textOf(node, "arc"), 2000));

                CharacterVO vo = assetService.createCharacter(novelId, save);
                created.add(vo);
                nameToId.put(name, vo.getId());
            }
        }

        if (created.isEmpty()) {
            throw BizException.of(ErrorCode.CHARACTER_GENERATE_EMPTY);
        }

        List<CharacterRelationVO> relations = new ArrayList<>();
        Set<String> relationKeys = new HashSet<>();
        JsonNode relationsNode = root.path("relations");
        if (relationsNode.isArray()) {
            for (JsonNode node : relationsNode) {
                if (relations.size() >= MAX_RELATIONS) {
                    break;
                }
                if (!node.isObject()) {
                    continue;
                }
                String fromName = truncate(textOf(node, "from"), 64);
                String toName = truncate(textOf(node, "to"), 64);
                Long fromId = fromName == null ? null : nameToId.get(fromName);
                Long toId = toName == null ? null : nameToId.get(toName);
                if (fromId == null || toId == null || fromId.equals(toId)) {
                    continue;
                }
                String relationType = normalizeRelationType(textOf(node, "relationType"));
                String key = (Math.min(fromId, toId)) + "|" + (Math.max(fromId, toId)) + "|" + relationType;
                if (!relationKeys.add(key)) {
                    continue;
                }
                RelationRequest relation = new RelationRequest();
                relation.setFromCharacterId(fromId);
                relation.setToCharacterId(toId);
                relation.setRelationType(relationType);
                relation.setDescription(truncate(textOf(node, "description"), 255));
                try {
                    relations.add(assetService.createRelation(novelId, relation));
                } catch (BizException ex) {
                    // 与已有关系重复时跳过，不阻断整批创建
                    if (ex.getErrorCode() != ErrorCode.RELATION_EXISTS) {
                        throw ex;
                    }
                }
            }
        }

        log.info("[ai-character] generated novelId={} characters={} relations={} skipped={} model={}",
                novelId, created.size(), relations.size(), skipped.size(), model);
        return new CharacterGenerateResultVO(created, relations, skipped, model, latencyMs);
    }

    /* ------------------------------------------------------------------ */
    /* 提示词与解析                                                       */
    /* ------------------------------------------------------------------ */

    private String buildPrompt(NovelDetailVO detail,
                               List<CharacterVO> existing,
                               List<CharacterRelationVO> relations,
                               List<FactionVO> factions,
                               String requirement,
                               int count) {
        StringBuilder builder = new StringBuilder();
        appendNovelInfo(builder, detail);
        appendBackground(builder, detail);
        appendExistingCharacters(builder, existing);
        appendExistingRelations(builder, relations);
        appendFactions(builder, factions);
        builder.append("\n【作者要求】\n").append(requirement).append("\n");
        builder.append("\n【本次任务】\n生成 ").append(count).append(" 个新角色，并给出这些角色之间、以及它们与已有角色之间的关系。\n");

        builder.append("""
                【角色字段要求】
                - appearance：50~90 字。一个能被记住的外形特征 + 当前身体或穿着状态；不要笼统夸好看。
                - personality：70~120 字。主导性格 + 一个矛盾点 + 压力下的反应 + 一个说话或行事习惯。
                - motivation：70~120 字。表面想要什么 / 真正缺什么 / 最怕失去什么。
                - goldenFinger：60~100 字。能力 + 代价 + 限制；没有就写「无」，并说明靠什么立足。
                - arc：60~100 字。初始相信的谎言 → 被什么打破 → 最终做出什么选择。

                【关系要求】
                - relations 给 3~8 条，必须包含至少 1 条有张力的关系（互相需要又互相提防、旧怨、利益冲突、说不出口的亏欠）。
                - description 写 30~60 字：表面关系 + 没说出来的一层，不要只写「关系很好」。
                - 两端姓名必须出现在「已有角色」或本次 characters 里；同一对角色不要重复同一类型的关系。
                - 至少有 1 条关系落在「已有角色」身上：新角色要接住现有的情感线与利益链，不要凭空另起一个圈子。
                - 不得与「已有关系」矛盾：已经写死的仇敌不能突然变成挚友，除非作者要求里明确说了关系会变。

                【输出格式】
                只输出一个 JSON 对象，字段如下：
                {
                  "characters": [
                    {
                      "name": "姓名，不要与已有角色重名",
                      "rolePos": "PROTAGONIST / SUPPORTING / ANTAGONIST / MINOR",
                      "identity": "身份职位",
                      "gender": "性别",
                      "ageDesc": "年龄描述",
                      "appearance": "外貌特征，50~90 字",
                      "personality": "性格，70~120 字",
                      "motivation": "核心动机与欲望，70~120 字",
                      "goldenFinger": "独有优势，60~100 字，或「无」",
                      "arc": "成长弧线，60~100 字"
                    }
                  ],
                  "relations": [
                    {
                      "from": "角色姓名",
                      "to": "角色姓名",
                      "relationType": "MASTER_APPRENTICE / FAMILY / SPOUSE / LOVER / FRIEND / ALLY / ENEMY / RIVAL / COLLEAGUE / OTHER",
                      "description": "表面关系 + 潜在张力，30~60 字"
                    }
                  ]
                }

                【硬性要求】
                1. characters 数量等于本次任务要求的数量；relations 给 3~8 条。
                2. 如果已有主角，新角色的 rolePos 不要再用 PROTAGONIST；没有主角时最多给 1 个 PROTAGONIST。
                3. 每个角色必须有具体欲望、致命缺陷和一个秘密；不要工具人，不要纯恶反派。
                4. 关系要能推动剧情：既有盟友也有对手，至少一条关系会在后续变成阻碍。
                5. 所有字段值单行纯文本，不要出现英文双引号与换行，引用用「」。
                6. 身份、能力、组织必须落在「世界观与背景」的规则内：设定里写明的代价与限制必须生效，不要自造一套力量体系。
                7. 有「已有势力」时，新角色要么明确属于某个势力，要么明确是局外人，不要含糊其辞。
                """);
        return builder.toString();
    }

    /* ---------------- 提示词上下文拼装 ---------------- */

    private void appendNovelInfo(StringBuilder builder, NovelDetailVO detail) {
        NovelVO novel = detail == null ? null : detail.getNovel();
        if (novel == null) {
            return;
        }
        builder.append("【作品信息】\n");
        builder.append("书名：").append(hasText(novel.getTitle()) ? novel.getTitle() : "（未命名）").append("\n");
        if (hasText(novel.getGenre())) {
            builder.append("题材：").append(novel.getGenre()).append("\n");
        }
        if (novel.getTags() != null && !novel.getTags().isEmpty()) {
            builder.append("标签：").append(String.join("、", novel.getTags())).append("\n");
        }
        if (hasText(novel.getIntro())) {
            builder.append("简介：").append(truncate(novel.getIntro(), 500)).append("\n");
        }
    }

    /** 四类设定原样带入：角色必须活在这套规则里，否则新角色一出现就打自己的脸。 */
    private void appendBackground(StringBuilder builder, NovelDetailVO detail) {
        StringBuilder block = new StringBuilder();
        if (detail != null) {
            appendSetting(block, detail, "WORLDVIEW", "世界观", 1600);
            appendSetting(block, detail, "BACKGROUND", "背景", 1200);
            appendSetting(block, detail, "CORE_CONFLICT", "核心冲突", 600);
            appendSetting(block, detail, "HIGHLIGHT", "核心看点", 500);
        }
        if (block.isEmpty()) {
            builder.append("\n【世界观与背景】\n（本书尚未填写设定，请让角色与题材自洽，不要引入需要额外解释的世界规则）\n");
            return;
        }
        builder.append("\n【世界观与背景】（不可违背的硬设定：规则、代价、时代、地理、组织）\n");
        builder.append(block);
    }

    private void appendExistingCharacters(StringBuilder builder, List<CharacterVO> existing) {
        builder.append("\n【已有角色】（新角色要与这些人共存，不能重名，关系要接上他们）\n");
        if (existing == null || existing.isEmpty()) {
            builder.append("（暂无，作者刚刚开书）\n");
            return;
        }
        int limit = Math.min(existing.size(), MAX_CHARACTERS_IN_PROMPT);
        for (int i = 0; i < limit; i++) {
            CharacterVO person = existing.get(i);
            if (person == null || !hasText(person.getName())) {
                continue;
            }
            builder.append("- ").append(person.getName())
                    .append("（").append(roleLabel(person.getRolePos())).append("）");
            List<String> traits = new ArrayList<>();
            if (hasText(person.getIdentity())) {
                traits.add("身份 " + truncate(person.getIdentity(), 40));
            }
            if (hasText(person.getGender())) {
                traits.add(person.getGender());
            }
            if (hasText(person.getAgeDesc())) {
                traits.add(truncate(person.getAgeDesc(), 20));
            }
            if (!traits.isEmpty()) {
                builder.append("：").append(String.join("，", traits));
            }
            builder.append("\n");
            // 性格与动机决定「他会怎么对待新角色」，必须给到模型
            appendTrait(builder, "性格", person.getPersonality(), 90);
            appendTrait(builder, "动机", person.getMotivation(), 90);
            appendTrait(builder, "能力", person.getGoldenFinger(), 60);
        }
        if (existing.size() > limit) {
            builder.append("- 另有 ").append(existing.size() - limit).append(" 名角色未列出，不要与他们重名。\n");
        }
    }

    private void appendExistingRelations(StringBuilder builder, List<CharacterRelationVO> relations) {
        builder.append("\n【已有关系】（新关系不得与这些冲突；同一对角色不要重复同一类型）\n");
        if (relations == null || relations.isEmpty()) {
            builder.append("（暂无）\n");
            return;
        }
        int limit = Math.min(relations.size(), MAX_RELATIONS_IN_PROMPT);
        for (int i = 0; i < limit; i++) {
            CharacterRelationVO relation = relations.get(i);
            if (relation == null) {
                continue;
            }
            String label = hasText(relation.relationLabel()) ? relation.relationLabel()
                    : hasText(relation.relationType()) ? relation.relationType() : "关系";
            builder.append("- ").append(hasText(relation.fromCharacterName()) ? relation.fromCharacterName() : "未知角色")
                    .append(" —[").append(label).append("]— ")
                    .append(hasText(relation.toCharacterName()) ? relation.toCharacterName() : "未知角色");
            if (hasText(relation.description())) {
                builder.append("：").append(truncate(relation.description(), 60));
            }
            builder.append("\n");
        }
        if (relations.size() > limit) {
            builder.append("- 另有 ").append(relations.size() - limit).append(" 条关系未列出。\n");
        }
    }

    private void appendFactions(StringBuilder builder, List<FactionVO> factions) {
        if (factions == null || factions.isEmpty()) {
            return;
        }
        builder.append("\n【已有势力】（新角色的立场要能挂进这些势力，或明确写他是局外人）\n");
        int limit = Math.min(factions.size(), 15);
        for (int i = 0; i < limit; i++) {
            FactionVO faction = factions.get(i);
            if (faction == null || !hasText(faction.name())) {
                continue;
            }
            builder.append("- ").append(faction.name());
            List<String> traits = new ArrayList<>();
            if (hasText(faction.category())) {
                traits.add(faction.category());
            }
            if (hasText(faction.scope())) {
                traits.add("范围 " + truncate(faction.scope(), 20));
            }
            if (hasText(faction.statusLabel())) {
                traits.add(faction.statusLabel());
            }
            if (faction.memberCount() > 0) {
                traits.add("成员 " + faction.memberCount() + " 人");
            }
            if (!traits.isEmpty()) {
                builder.append("：").append(String.join("，", traits));
            }
            if (hasText(faction.remark())) {
                builder.append("｜").append(truncate(faction.remark(), 60));
            }
            builder.append("\n");
        }
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
            if (doc != null && category.equals(doc.getCategory()) && hasText(doc.getContent())) {
                builder.append("● ").append(label).append("：").append(truncate(doc.getContent(), max)).append("\n");
                return;
            }
        }
    }

    private ChatResult chat(AiEndpoint endpoint, String systemPrompt, String userPrompt) {
        try {
            return aiUsageService.chat(AiScene.CHARACTER_BUILD, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String reason = hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败";
            log.warn("[ai-character] chat failed: {}", ex.toString());
            throw BizException.of(ErrorCode.AI_CALL_FAILED, reason);
        }
    }

    private JsonNode parse(String content) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[ai-character] 模型未返回 JSON：{}", abbreviate(content));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型没有返回结构化角色，请重试");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[ai-character] parse failed: {} | raw={}", strictFailure.getMessage(), abbreviate(candidate));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的角色内容无法解析，请重试");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                               */
    /* ------------------------------------------------------------------ */

    private static Map<String, String> createRelationAliases() {
        Map<String, String> map = new HashMap<>();
        map.put("MASTER_APPRENTICE", "MASTER_APPRENTICE");
        map.put("FAMILY", "FAMILY");
        map.put("SPOUSE", "SPOUSE");
        map.put("LOVER", "LOVER");
        map.put("FRIEND", "FRIEND");
        map.put("ALLY", "ALLY");
        map.put("ENEMY", "ENEMY");
        map.put("RIVAL", "RIVAL");
        map.put("COLLEAGUE", "COLLEAGUE");
        map.put("OTHER", "OTHER");
        map.put("师徒", "MASTER_APPRENTICE");
        map.put("师父", "MASTER_APPRENTICE");
        map.put("徒弟", "MASTER_APPRENTICE");
        map.put("亲属", "FAMILY");
        map.put("家人", "FAMILY");
        map.put("父子", "FAMILY");
        map.put("母女", "FAMILY");
        map.put("兄妹", "FAMILY");
        map.put("兄弟", "FAMILY");
        map.put("夫妻", "SPOUSE");
        map.put("夫妻关系", "SPOUSE");
        map.put("恋人", "LOVER");
        map.put("情侣", "LOVER");
        map.put("好友", "FRIEND");
        map.put("朋友", "FRIEND");
        map.put("盟友", "ALLY");
        map.put("同盟", "ALLY");
        map.put("仇敌", "ENEMY");
        map.put("敌人", "ENEMY");
        map.put("宿敌", "ENEMY");
        map.put("对手", "RIVAL");
        map.put("竞争", "RIVAL");
        map.put("同门", "COLLEAGUE");
        map.put("同事", "COLLEAGUE");
        return map;
    }

    private String normalizeRelationType(String raw) {
        if (!hasText(raw)) {
            return "OTHER";
        }
        String key = raw.trim();
        String mapped = RELATION_ALIASES.get(key);
        if (mapped == null) {
            mapped = RELATION_ALIASES.get(key.toUpperCase(Locale.ROOT));
        }
        return mapped == null ? "OTHER" : mapped;
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
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
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
