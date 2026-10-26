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
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.ItemGenerateRequest;
import com.storyforge.modules.bookshelf.dto.ItemGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.ItemRequest;
import com.storyforge.modules.bookshelf.dto.ItemVO;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.SettingDocVO;
import com.storyforge.modules.novel.dto.NovelVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 创建物品：按作者提示，结合作品设定、角色与已有物品生成道具 / 线索 / 信物并落库。
 *
 * <p>模型只负责给内容，服务端清洗：重名跳过、状态归一、数量取非负整数（无法识别则不填）。
 * 整批写入在同一事务内，一个都没生成出来时回滚并返回 `40034`。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiItemService {

    private static final int TIMEOUT_MS = 300_000;
    private static final int MAX_TOKENS = 3200;
    private static final int MAX_ITEMS = 10;
    private static final int MAX_EXISTING_IN_PROMPT = 30;
    private static final int MAX_CHARACTERS_IN_PROMPT = 20;
    private static final int MAX_QUANTITY = 999_999;

    private static final String SYSTEM_PROMPT = """
            你是资深网络小说设定师，擅长设计能推动剧情的物品、线索与信物。
            每个物品必须写清两部分：description 是「物品描述」（外貌形态、材质、尺寸、磨损、特殊特征与象征意义），
            origin 是「说明」（来历、经手人、关联的关键事件或秘密）。两部分内容不得重复。
            你的输出必须严格遵循给定 JSON 结构：只输出 JSON 对象，不要解释文字，不要使用 markdown 代码块。
            所有字段值都是单行纯文本：不要出现英文双引号与换行，需要引用时用「」。
            """;

    private static final String RETRY_SUFFIX = """
            上一次输出不是合法 JSON（多半是字符串里混入了英文双引号），这次务必只输出可被 JSON 解析器直接解析的对象。
            """;

    private static final Map<String, String> STATUS_ALIASES = createStatusAliases();
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;
    private final BookshelfService bookshelfService;
    private final NovelAssetService assetService;
    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;

    @Transactional(rollbackFor = Exception.class)
    public ItemGenerateResultVO generate(Long novelId, ItemGenerateRequest request) {
        NovelDetailVO detail = bookshelfService.detail(novelId);
        List<ItemVO> existing = assetService.listItems(novelId);
        String requirement = request == null || request.getPrompt() == null
                ? "" : request.getPrompt().trim();
        if (requirement.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先描述你想要的物品");
        }
        int count = request.getCount() == null ? 3 : Math.min(MAX_ITEMS, Math.max(1, request.getCount()));

        AiEndpoint base = endpointResolver.resolve(AiScene.ITEM_BUILD);
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS),
                base.extraHeaders(),
                0.85D,
                MAX_TOKENS);

        long startedAt = System.currentTimeMillis();
        String userPrompt = buildPrompt(detail, existing, requirement, count);
        String system = systemPrompt(detail, count);
        ChatResult reply = chat(endpoint, system, userPrompt);
        JsonNode root;
        try {
            root = parse(reply.content());
        } catch (BizException ex) {
            if (ex.getErrorCode() != ErrorCode.AI_PARSE_FAILED) {
                throw ex;
            }
            log.warn("[ai-item] parse failed, retry once novelId={}: {}", novelId, ex.getMessage());
            reply = chat(endpoint, system + RETRY_SUFFIX, userPrompt);
            root = parse(reply.content());
        }

        return persist(novelId, existing, root, reply.model(),
                System.currentTimeMillis() - startedAt, count);
    }

    /** 用户改过提示词就用模板，否则用内置常量。 */
    private String systemPrompt(NovelDetailVO detail, int count) {
        var novel = detail == null ? null : detail.getNovel();
        Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("novelTitle", novel == null || !hasText(novel.getTitle()) ? "这本书" : novel.getTitle());
        vars.put("genre", novel == null || !hasText(novel.getGenre()) ? "通用" : novel.getGenre());
        vars.put("count", String.valueOf(count));
        String rendered = promptTemplateService.system(AiScene.ITEM_BUILD, vars);
        return hasText(rendered) ? rendered : SYSTEM_PROMPT;
    }

    /* ------------------------------------------------------------------ */

    private ItemGenerateResultVO persist(Long novelId,
                                         List<ItemVO> existing,
                                         JsonNode root,
                                         String model,
                                         long latencyMs,
                                         int count) {
        Set<String> names = new LinkedHashSet<>();
        for (ItemVO item : existing) {
            if (item.name() != null) {
                names.add(item.name().trim());
            }
        }

        List<ItemVO> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        JsonNode itemsNode = root.path("items");
        if (itemsNode.isArray()) {
            for (JsonNode node : itemsNode) {
                if (created.size() >= count) {
                    break;
                }
                if (!node.isObject()) {
                    continue;
                }
                String name = truncate(textOf(node, "name"), 100);
                if (!hasText(name)) {
                    continue;
                }
                if (!names.add(name)) {
                    skipped.add(name);
                    continue;
                }

                ItemRequest save = new ItemRequest();
                save.setName(name);
                save.setCategory(truncate(textOf(node, "category"), 32));
                save.setOwner(truncate(textOf(node, "owner"), 100));
                save.setDescription(truncate(textOf(node, "description"), 4000));
                String origin = textOf(node, "origin");
                if (!hasText(origin)) {
                    origin = textOf(node, "story");
                }
                save.setOrigin(truncate(origin, 4000));
                save.setStatus(normalizeStatus(textOf(node, "status")));
                save.setQuantity(quantityOf(node));
                created.add(assetService.createItem(novelId, save));
            }
        }

        if (created.isEmpty()) {
            throw BizException.of(ErrorCode.ITEM_GENERATE_EMPTY);
        }
        log.info("[ai-item] generated novelId={} items={} skipped={} model={}",
                novelId, created.size(), skipped.size(), model);
        return new ItemGenerateResultVO(created, skipped, model, latencyMs);
    }

    private String buildPrompt(NovelDetailVO detail,
                               List<ItemVO> existing,
                               String requirement,
                               int count) {
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

        builder.append("\n【核心设定】\n");
        appendSetting(builder, detail, "WORLDVIEW", "世界观", 800);
        appendSetting(builder, detail, "BACKGROUND", "背景", 500);
        appendSetting(builder, detail, "CORE_CONFLICT", "核心冲突", 400);

        builder.append("\n【主要角色】\n");
        List<CharacterVO> characters = detail.getCharacters() == null ? List.of() : detail.getCharacters();
        if (characters.isEmpty()) {
            builder.append("（暂无，持有者可留空或在 owner 里写身份）\n");
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

        builder.append("\n【已有物品】\n");
        if (existing.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            int limit = Math.min(existing.size(), MAX_EXISTING_IN_PROMPT);
            for (int i = 0; i < limit; i++) {
                ItemVO item = existing.get(i);
                builder.append("- ").append(item.name())
                        .append("（").append(item.category() == null ? "未分类" : item.category())
                        .append(" · ").append(item.statusLabel() == null ? "活跃" : item.statusLabel());
                if (item.quantity() != null) {
                    builder.append(" · 数量 ").append(item.quantity());
                }
                builder.append("）\n");
            }
            if (existing.size() > limit) {
                builder.append("- 其余 ").append(existing.size() - limit).append(" 个物品略\n");
            }
        }

        builder.append("\n【作者要求】\n").append(requirement).append("\n");
        builder.append("\n【本次任务】\n生成 ").append(count).append(" 个新物品，不要与已有物品重名。\n");

        builder.append("""
                【输出格式】
                只输出一个 JSON 对象：
                {
                  "items": [
                    {
                      "name": "物品名称，不超过 20 字，不要与已有物品重复",
                      "category": "法宝 / 信物 / 线索 / 消耗品 / 其他",
                      "owner": "当前持有者姓名或势力，可为空",
                      "quantity": "数量，非负整数；唯一或不可计数的物品写 null",
                      "status": "ACTIVE / DESTROYED / ABANDONED / LOST / CONSUMED",
                      "description": "物品描述，80~150 字：材质、尺寸、颜色、磨损或修补痕迹、纹样机关，以及它在故事里象征什么",
                      "origin": "说明，80~150 字：谁打造或从哪来、经手过谁、关联哪段关键事件或秘密，目前还有谁知道"
                    }
                  ]
                }

                【示例】
                {"name":"修书刀","category":"工具","owner":"沈默","quantity":1,"status":"ACTIVE","description":"七寸长的窄刃刀，刀身发乌，刃口薄得能透光；刀背嵌一枚黄铜起钉钩，柄上缠着褪成灰白的蓝布，布尾烧焦了一小截。","origin":"沈默师父留下的唯一遗物。当年师父用它修过旧街七户人家的族谱，也在刀柄蓝布下刻了一个只有守夜人认得的记号；师父失踪后，这把刀成了沈默查旧街大火的第一条线索。"}

                【硬性要求】
                1. items 数量等于本次任务要求的数量。
                2. description 只写看得见、摸得到的形态与特征，以及象征意义；不要写来历、经手人、剧情经过。
                3. origin 只写来历、经手人与关联的关键故事；不要重复外貌描写。
                4. 每条都必须具体：至少给出一个后文可以用的细节（缺口、暗记、气味、尺寸、夹层、褪色、补丁等）。
                5. 禁止「很厉害」「很重要」「神秘莫测」「价值连城」这类空话，也不要只写用途。
                6. 新物品默认 ACTIVE；只有作者明确说被毁、遗失、用完时才用其它状态。
                7. 所有字段值单行纯文本，不要出现英文双引号与换行，引用用「」。
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
            return aiUsageService.chat(AiScene.ITEM_BUILD, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String reason = hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败";
            log.warn("[ai-item] chat failed: {}", ex.toString());
            throw BizException.of(ErrorCode.AI_CALL_FAILED, reason);
        }
    }

    private JsonNode parse(String content) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[ai-item] 模型未返回 JSON：{}", abbreviate(content));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型没有返回结构化物品，请重试");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[ai-item] parse failed: {} | raw={}", strictFailure.getMessage(), abbreviate(candidate));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的物品内容无法解析，请重试");
            }
        }
    }

    private static Map<String, String> createStatusAliases() {
        Map<String, String> map = new HashMap<>();
        for (String code : List.of("ACTIVE", "DESTROYED", "ABANDONED", "LOST", "CONSUMED")) {
            map.put(code, code);
        }
        map.put("活跃", "ACTIVE");
        map.put("存在", "ACTIVE");
        map.put("完好", "ACTIVE");
        map.put("使用中", "ACTIVE");
        map.put("摧毁", "DESTROYED");
        map.put("毁坏", "DESTROYED");
        map.put("损毁", "DESTROYED");
        map.put("已毁", "DESTROYED");
        map.put("破碎", "DESTROYED");
        map.put("废弃", "ABANDONED");
        map.put("弃用", "ABANDONED");
        map.put("不再使用", "ABANDONED");
        map.put("遗失", "LOST");
        map.put("丢失", "LOST");
        map.put("失踪", "LOST");
        map.put("下落不明", "LOST");
        map.put("已消耗", "CONSUMED");
        map.put("消耗", "CONSUMED");
        map.put("耗尽", "CONSUMED");
        map.put("用完", "CONSUMED");
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

    /** 数量：数字或含数字的文本都接受，识别不了就留空；上限 999999。 */
    private Integer quantityOf(JsonNode node) {
        JsonNode value = node.path("quantity");
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return clampQuantity(value.asInt());
        }
        String text = value.asText("").trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text) || "无".equals(text)) {
            return null;
        }
        Matcher matcher = DIGITS.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return clampQuantity(Integer.parseInt(matcher.group()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private Integer clampQuantity(int value) {
        return Math.max(0, Math.min(MAX_QUANTITY, value));
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
