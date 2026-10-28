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
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.FactionGenerateRequest;
import com.storyforge.modules.bookshelf.dto.FactionGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.SettingDocVO;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.FactionEntity;
import com.storyforge.modules.bookshelf.entity.FactionMemberEntity;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMemberMapper;
import com.storyforge.modules.novel.dto.NovelVO;
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
 * AI 生成势力：按作者提示，结合作品设定与角色生成势力（组织 / 阵营），并把
 * 关键角色挂到对应势力下担任职位。
 *
 * <p>模型只负责给内容，服务端清洗：势力重名跳过、状态归一、成员按姓名映射成
 * 角色 ID（映射不上直接丢弃，避免幽灵成员）。整批写入同一事务内，一个都没
 * 生成出来时回滚并返回 {@code 40059}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiFactionService {

    private static final int TIMEOUT_MS = 300_000;
    private static final int MAX_TOKENS = 3200;
    private static final int MAX_FACTIONS = 8;
    private static final int MAX_EXISTING_IN_PROMPT = 30;
    private static final int MAX_CHARACTERS_IN_PROMPT = 20;
    private static final int MAX_MEMBERS_PER_FACTION = 8;

    private static final String SYSTEM_PROMPT = """
            你是资深网络小说设定师，擅长设计能驱动剧情的势力、组织与阵营。
            每个势力必须写清：名称、类型、宗旨或内部矛盾、管辖或经营范围、状态，以及关键角色在其中的职位。
            势力之间要有资源、理念或地盘的冲突，不要只是并列一堆名字。
            你的输出必须严格遵循给定 JSON 结构：只输出 JSON 对象，不要解释文字，不要使用 markdown 代码块。
            所有字段值都是单行纯文本：不要出现英文双引号与换行，需要引用时用「」。
            """;

    private static final String RETRY_SUFFIX = """
            上一次输出不是合法 JSON（多半是字符串里混入了英文双引号），这次务必只输出可被 JSON 解析器直接解析的对象。
            """;

    private static final Map<String, String> STATUS_ALIASES = createStatusAliases();

    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;
    private final BookshelfService bookshelfService;
    private final FactionMapper factionMapper;
    private final FactionMemberMapper factionMemberMapper;
    private final CharacterMapper characterMapper;
    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;

    @Transactional(rollbackFor = Exception.class)
    public FactionGenerateResultVO generate(Long novelId, FactionGenerateRequest request) {
        NovelDetailVO detail = bookshelfService.detail(novelId);
        String requirement = request == null || request.getPrompt() == null ? "" : request.getPrompt().trim();
        if (requirement.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先描述你想要的势力");
        }
        int count = request.getCount() == null ? 2 : Math.min(MAX_FACTIONS, Math.max(1, request.getCount()));

        AiEndpoint base = endpointResolver.resolve(AiScene.FACTION_BUILD);
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS),
                base.extraHeaders(),
                0.85D,
                MAX_TOKENS);

        long startedAt = System.currentTimeMillis();
        java.util.Set<String> used = promptTemplateService.usedVariables(AiScene.FACTION_BUILD);
        String userPrompt = buildPrompt(detail, requirement, count, used);
        String system = systemPrompt(detail, count, requirement);
        ChatResult reply = chat(endpoint, system, userPrompt);
        JsonNode root;
        try {
            root = parse(reply.content());
        } catch (BizException ex) {
            if (ex.getErrorCode() != ErrorCode.AI_PARSE_FAILED) {
                throw ex;
            }
            log.warn("[ai-faction] parse failed, retry once novelId={}: {}", novelId, ex.getMessage());
            reply = chat(endpoint, system + RETRY_SUFFIX, userPrompt);
            root = parse(reply.content());
        }

        return persist(novelId, detail, root, reply.model(), System.currentTimeMillis() - startedAt, count);
    }

    /** 用户改过提示词就用模板，否则用内置常量。 */
    private String systemPrompt(NovelDetailVO detail, int count, String requirement) {
        NovelVO novel = detail == null ? null : detail.getNovel();
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("novelTitle", novel == null || !hasText(novel.getTitle()) ? "这本书" : novel.getTitle());
        vars.put("genre", novel == null || !hasText(novel.getGenre()) ? "通用" : novel.getGenre());
        vars.put("count", String.valueOf(count));
        vars.put("authorRequirement", requirement == null ? "" : requirement);
        PromptVars.putNovel(vars, novel, 400);
        PromptVars.putSettings(vars, detail, 800, 500, 400, 300, 300);
        vars.put("constraints", PromptVars.constraint(detail, 1200));
        String rendered = promptTemplateService.system(AiScene.FACTION_BUILD, PromptVars.clean(vars));
        return hasText(rendered) ? rendered : SYSTEM_PROMPT;
    }

    /* ------------------------------------------------------------------ */
    /* 落库                                                               */
    /* ------------------------------------------------------------------ */

    private FactionGenerateResultVO persist(Long novelId,
                                            NovelDetailVO detail,
                                            JsonNode root,
                                            String model,
                                            long latencyMs,
                                            int count) {
        Long userId = UserContextHolder.requireUserId();

        // 已有势力名（重名跳过）
        Set<String> names = new LinkedHashSet<>();
        int existingCount = 0;
        for (FactionEntity item : factionMapper.selectList(
                Wrappers.<FactionEntity>lambdaQuery()
                        .eq(FactionEntity::getNovelId, novelId))) {
            if (item.getName() != null) {
                names.add(item.getName().trim());
            }
            existingCount++;
        }

        // 角色姓名 → ID 映射
        Map<String, Long> nameToId = new LinkedHashMap<>();
        for (CharacterEntity item : characterMapper.selectList(
                Wrappers.<CharacterEntity>lambdaQuery()
                        .eq(CharacterEntity::getNovelId, novelId))) {
            if (item.getName() != null) {
                nameToId.put(item.getName().trim(), item.getId());
            }
        }

        List<String> skipped = new ArrayList<>();
        int created = 0;
        int memberCount = 0;
        JsonNode factionsNode = root.path("factions");
        if (factionsNode.isArray()) {
            int sort = existingCount;
            for (JsonNode node : factionsNode) {
                if (created >= count) {
                    break;
                }
                if (!node.isObject()) {
                    continue;
                }
                String name = truncate(textOf(node, "name"), 100);
                if (!hasText(name) || !names.add(name)) {
                    if (hasText(name)) {
                        skipped.add(name);
                    }
                    continue;
                }

                FactionEntity entity = new FactionEntity();
                entity.setUserId(userId);
                entity.setNovelId(novelId);
                entity.setName(name);
                entity.setCategory(truncate(textOf(node, "category"), 32));
                entity.setRemark(truncate(textOf(node, "remark"), 1000));
                entity.setScope(truncate(textOf(node, "scope"), 500));
                entity.setStatus(normalizeStatus(textOf(node, "status")));
                entity.setSortNo(sort++);
                entity.setDeleted(0);
                entity.setVersion(0);
                factionMapper.insert(entity);

                // 成员按姓名映射角色 ID
                int memberSort = 0;
                JsonNode membersNode = node.path("members");
                if (membersNode.isArray()) {
                    for (JsonNode memberNode : membersNode) {
                        if (memberSort >= MAX_MEMBERS_PER_FACTION) {
                            break;
                        }
                        if (!memberNode.isObject()) {
                            continue;
                        }
                        String memberName = truncate(textOf(memberNode, "name"), 64);
                        Long characterId = memberName == null ? null : nameToId.get(memberName.trim());
                        if (characterId == null) {
                            continue;
                        }
                        FactionMemberEntity member = new FactionMemberEntity();
                        member.setUserId(userId);
                        member.setNovelId(novelId);
                        member.setFactionId(entity.getId());
                        member.setCharacterId(characterId);
                        member.setPosition(truncate(textOf(memberNode, "position"), 64));
                        member.setSortNo(memberSort++);
                        member.setDeleted(0);
                        member.setVersion(0);
                        factionMemberMapper.insert(member);
                        memberCount++;
                    }
                }
                created++;
            }
        }

        if (created == 0) {
            throw BizException.of(ErrorCode.FACTION_GENERATE_EMPTY);
        }
        log.info("[ai-faction] generated novelId={} factions={} members={} skipped={} model={}",
                novelId, created, memberCount, skipped.size(), model);
        return new FactionGenerateResultVO(created, memberCount, skipped, model, latencyMs);
    }

    /* ------------------------------------------------------------------ */
    /* 提示词与解析                                                       */
    /* ------------------------------------------------------------------ */

    private String buildPrompt(NovelDetailVO detail, String requirement, int count,
                               java.util.Set<String> used) {
        NovelVO novel = detail.getNovel();
        StringBuilder builder = new StringBuilder();
        builder.append("【作品信息】\n");
        builder.append("书名：").append(novel.getTitle()).append("\n");
        if (hasText(novel.getGenre())) {
            builder.append("题材：").append(novel.getGenre()).append("\n");
        }
        if (novel.getTags() != null && !novel.getTags().isEmpty()) {
            builder.append("标签：").append(String.join("、", novel.getTags())).append("\n");
        }
        if (hasText(novel.getIntro())) {
            builder.append("简介：").append(truncate(novel.getIntro(), 400)).append("\n");
        }

        boolean anySetting = PromptVars.emit(used, "worldview") || PromptVars.emit(used, "background")
                || PromptVars.emit(used, "coreConflict");
        if (anySetting) {
            builder.append("\n【核心设定】\n");
            if (PromptVars.emit(used, "worldview")) {
                appendSetting(builder, detail, "WORLDVIEW", "世界观", 800);
            }
            if (PromptVars.emit(used, "background")) {
                appendSetting(builder, detail, "BACKGROUND", "背景", 500);
            }
            if (PromptVars.emit(used, "coreConflict")) {
                appendSetting(builder, detail, "CORE_CONFLICT", "核心冲突", 400);
            }
        }

        builder.append("\n【主要角色】\n");
        List<CharacterVO> characters = detail.getCharacters() == null ? List.of() : detail.getCharacters();
        if (characters.isEmpty()) {
            builder.append("（暂无角色，成员的 name 可留空数组）\n");
        } else {
            int limit = Math.min(characters.size(), MAX_CHARACTERS_IN_PROMPT);
            for (int i = 0; i < limit; i++) {
                CharacterVO person = characters.get(i);
                builder.append("- ").append(person.getName())
                        .append("（").append(person.getRoleLabel() == null ? "角色" : person.getRoleLabel()).append("）");
                if (hasText(person.getIdentity())) {
                    builder.append("：").append(truncate(person.getIdentity(), 60));
                }
                builder.append("\n");
            }
        }

        PromptVars.appendConstraint(builder, detail, used, 1200);
        if (PromptVars.emit(used, "authorRequirement")) {
            builder.append("\n【作者要求】\n").append(requirement).append("\n");
        }
        builder.append("\n【本次任务】\n生成 ").append(count).append(" 个新势力，不要与已有势力重名。\n");

        builder.append("""
                【势力设计方法】
                1. 每个势力写清它想要什么、靠什么运转、内部有什么矛盾，不要只是名字 + 一句口号。
                2. 势力之间要有资源、理念或地盘的冲突；至少有两个势力处于对立或博弈关系。
                3. 成员必须是【主要角色】里出现的姓名，并写明职位；没有合适成员就留空数组。
                4. 状态默认 ACTIVE（活跃）；只有作者明确说该势力已蛰伏、覆灭、解散时才用其它状态。

                【输出格式】
                只输出一个 JSON 对象：
                {
                  "factions": [
                    {
                      "name": "势力名称，20 字内，不与已有势力重复；要透露它做什么或归谁管（如「抬尸行」「盐引公所」「夜巡第三班」），不用「天机阁」「万宝楼」「神秘组织」这类空壳",
                      "category": "公司 / 帮派 / 国家 / 组织 / 其他",
                      "status": "ACTIVE / DORMANT / DESTROYED / DISBANDED",
                      "remark": "宗旨、运作方式与内部矛盾，60~120 字",
                      "scope": "管辖或经营范围，不超过 60 字",
                      "members": [
                        { "name": "角色姓名，必须来自【主要角色】", "position": "职位，如 会长 / 长老 / 掌柜 / 客卿" }
                      ]
                    }
                  ]
                }

                【硬性要求】
                1. factions 数量等于本次任务要求的数量。
                2. 每个势力必须有 remark（宗旨或内部矛盾），不能是空泛口号。
                3. members 的姓名必须逐字来自【主要角色】，不要新造角色；没有就留空数组 []。
                4. 禁止「庞大」「神秘」「只手遮天」这类空话，要写具体做什么、争什么。
                5. 所有字段值单行纯文本，不要出现英文双引号与换行，引用用「」。
                """);
        return builder.toString();
    }

    private void appendSetting(StringBuilder builder, NovelDetailVO detail,
                               String category, String label, int max) {
        if (detail.getSettings() == null) {
            return;
        }
        for (SettingDocVO doc : detail.getSettings()) {
            if (category.equals(doc.getCategory()) && hasText(doc.getContent())) {
                builder.append(label).append("：").append(truncate(doc.getContent(), max)).append("\n");
                return;
            }
        }
    }

    /* ------------------------------------------------------------------ */

    private ChatResult chat(AiEndpoint endpoint, String systemPrompt, String userPrompt) {
        try {
            return aiUsageService.chat(AiScene.FACTION_BUILD, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String reason = hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败";
            log.warn("[ai-faction] chat failed: {}", ex.toString());
            throw BizException.of(ErrorCode.AI_CALL_FAILED, reason);
        }
    }

    private JsonNode parse(String content) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[ai-faction] 模型未返回 JSON：{}", abbreviate(content));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型没有返回结构化势力，请重试");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[ai-faction] parse failed: {} | raw={}", strictFailure.getMessage(), abbreviate(candidate));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的势力内容无法解析，请重试");
            }
        }
    }

    /* ------------------------------------------------------------------ */

    private static Map<String, String> createStatusAliases() {
        Map<String, String> map = new HashMap<>();
        for (String code : List.of("ACTIVE", "DORMANT", "DESTROYED", "DISBANDED")) {
            map.put(code, code);
        }
        map.put("活跃", "ACTIVE");
        map.put("蛰伏", "DORMANT");
        map.put("潜伏", "DORMANT");
        map.put("覆灭", "DESTROYED");
        map.put("毁灭", "DESTROYED");
        map.put("解散", "DISBANDED");
        return map;
    }

    private String normalizeStatus(String raw) {
        if (!hasText(raw)) {
            return "ACTIVE";
        }
        String key = raw.trim();
        String mapped = STATUS_ALIASES.get(key);
        if (mapped == null) {
            mapped = STATUS_ALIASES.get(key.toUpperCase(Locale.ROOT));
        }
        return mapped == null ? "ACTIVE" : mapped;
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
