package com.storyforge.modules.bookshelf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContext;
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
import com.storyforge.modules.bookshelf.dto.BlueprintTaskVO;
import com.storyforge.modules.bookshelf.dto.CharacterDraft;
import com.storyforge.modules.bookshelf.dto.NovelBlueprint;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 蓝图分段生成任务。
 *
 * <p>把原本一次性的长调用拆成四段（世界观 / 背景 / 核心冲突 / 人物），每段独立请求、
 * 独立超时（{@value #STAGE_TIMEOUT_MS} ms = 5 分钟），前端按 {@link #status} 轮询拿到
 * 百分比与当前阶段文案，从而显示真实进度而不是假动画。</p>
 *
 * <p>任务只存在内存（单机部署，无 Redis/MQ），进程重启即丢失——可接受：生成失败用户重试即可。
 * 已结束的任务保留 30 分钟供前端补偿拉取，随后惰性清理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BlueprintTaskService {

    /** 每段 AI 调用的超时：5 分钟。 */
    public static final int STAGE_TIMEOUT_MS = 300_000;

    private static final int STEP_TOTAL = 4;
    private static final long TASK_TTL_MS = 30 * 60 * 1000L;

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_FAILED = "FAILED";

    private static final String COMMON_RULES = PromptKit.JSON_CONTRACT + PromptKit.ANTI_CLICHE;

    private static final String WORLDVIEW_PROMPT = """
            你是资深世界观设计师，擅长为网络小说构建能直接支撑剧情运转的世界，而不是名词清单。

            【设计任务】
            只设计世界观，不写剧情梗概，不写人物小传。

            【设计方法】
            1. 先立一条核心规则（力量、技术、制度或禁忌），再写它带来的收益、代价和失效条件。
            2. 规则必须能解释普通人的日常：吃什么、靠什么谋生、怕什么、如何向上爬。
            3. 至少写 3 个具体的生活细节（物价、工序、称呼、节日、刑罚、交通），让世界可触摸。
            4. 势力之间要有资源或理念冲突，不要只列名字；写清谁控制什么、谁不服谁。
            5. 给故事留一个当前无法解决的矛盾，不要写成完美自洽的百科。

            【输出格式】
            {"worldview": "600~900 字：核心规则 → 代价与边界 → 社会结构 → 势力格局 → 3 个日常细节 → 当前暗流"}
            """ + PromptKit.GENRE_GUIDE + COMMON_RULES;

    private static final String BACKGROUND_PROMPT = """
            你是资深小说策划，负责把世界观落到具体历史与当下局势。

            【设计任务】
            只写背景设定：这个世界是怎么走到故事开始这一天的。

            【设计方法】
            1. 写 3 个关键历史事件，每个都按「起因 → 经过 → 后果」写；后果必须改变规则、势力或普通人的生活。
            2. 至少有一个事件是官方说法与民间记忆不一致的，为后文留出真相空间。
            3. 写清故事开始时各方在争什么、谁占上风、谁在暗中蓄力。
            4. 留一条尚未爆发的压力（税、灾、继承、边境、技术失控等），它会在故事里被点燃。
            5. 历史必须能解释世界观里的规则是怎么形成的，不能另起一套设定。

            【输出格式】
            {"background": "400~600 字：三个关键事件 → 当前局势 → 一条尚未爆发的压力"}
            """ + COMMON_RULES;

    private static final String CONFLICT_PROMPT = """
            你是资深小说策划，负责提炼卖点与贯穿全书的矛盾。

            【设计任务】
            基于已确定的世界观与背景，写简介、核心冲突、看点与文风。

            【设计方法】
            1. 简介必须让读者在 30 秒内知道：主角是谁、想要什么、谁挡着他、失败会失去什么。
            2. 核心冲突写清双方的目标与底线：为什么双方都不能退让，冲突会如何升级。
            3. 看点要具体到「可想象的画面或桥段」，不要写「节奏紧凑」「设定新颖」。
            4. 文风写清人称、叙述距离与语言质感（冷硬/市井/克制/热烈），不要只写「轻松幽默」。
            5. 简介结尾留一个具体钩子，不要用反问句。

            【输出格式】
            {
              "synopsis": "250~400 字：主角 → 欲望 → 阻碍 → 代价 → 钩子",
              "coreConflict": "150~250 字：双方目标、底线、升级方式、最终要回答的问题",
              "highlights": ["3~5 条核心看点，每条不超过 20 字，必须是具体桥段或设定"],
              "style": "人称 + 叙述距离 + 语言质感，不超过 40 字",
              "genre": "题材，2~6 字，如 玄幻 / 都市 / 悬疑",
              "tags": ["3~6 个标签，每个不超过 8 字"]
            }
            """ + COMMON_RULES;

    private static final String CHARACTER_PROMPT = """
            你是资深角色设定师，负责让角色能自己推动剧情。

            【设计任务】
            只设计人物档案：1 个主角 + 3~6 个与主线强相关的角色。

            【每个角色必须有】
            - 一个具体的欲望（想要什么，具体到可执行）
            - 一个致命缺陷（会在关键时刻让他付出代价）
            - 一个秘密或羞于承认的部分
            - 一个说话或行事习惯（可写进性格、外貌）

            【主角字段要求】
            appearance 60~100 字：一个能被记住的外形特征 + 当前的身体或穿着状态，不要笼统夸好看。
            personality 80~120 字：主导性格 + 一个矛盾点 + 压力下的反应。
            motivation 80~120 字：表面想要什么 / 真正缺什么 / 最怕失去什么。
            goldenFinger 80~120 字：能力 + 代价 + 限制 + 暴露后的风险；没有金手指就写「无」并说明靠什么立足。
            arc 80~120 字：初始相信的谎言 → 被什么打破 → 最终做出什么选择。

            【配角要求】
            - 至少 1 个主要对手（ANTAGONIST），他的动机必须让读者理解，不能是纯恶工具人。
            - 每个配角都要在主线上有一个不可替代的功能（提供信息、制造阻碍、承担代价、见证转变）。
            - 关系通过身份、动机和利益体现，不要只写「好友」「仇敌」。

            【输出格式】
            {
              "protagonist": {
                "name": "姓名",
                "identity": "身份职位",
                "gender": "性别",
                "ageDesc": "年龄描述",
                "appearance": "外貌特征，60~100 字",
                "personality": "性格，80~120 字",
                "motivation": "核心动机与欲望，80~120 字",
                "goldenFinger": "金手指或独有优势，80~120 字",
                "arc": "成长弧线，80~120 字"
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
            characters 给 3~6 个，至少 1 个 ANTAGONIST。
            """ + PromptKit.NAMING + COMMON_RULES;

    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;
    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;

    /** 蓝图四段：每段是一个独立 AI 场景，可各自绑定模型、各自定制提示词。 */
    private static final List<AiScene> BLUEPRINT_STAGES = List.of(
            AiScene.BLUEPRINT_WORLDVIEW,
            AiScene.BLUEPRINT_BACKGROUND,
            AiScene.BLUEPRINT_CONFLICT,
            AiScene.BLUEPRINT_CHARACTER);

    private static final String RETRY_SUFFIX = """
            上一次输出不是合法 JSON（多半是字符串里混入了英文双引号），这次务必只输出可被 JSON 解析器直接解析的对象。
            """;

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "blueprint-task-" + threadSeq.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 创建并启动生成任务。
     *
     * <p>端点解析在调用线程完成：配置缺失时立刻返回 {@code 40002}，
     * 不必等任务跑起来才报错。</p>
     */
    public BlueprintTaskVO start(BlueprintRequest request, Long userId) {
        if (request == null || !hasText(request.getIdea())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先填写核心创意");
        }
        // 四段各自解析端点：配置缺失在调用线程就暴露，不必等任务跑起来才报错
        Map<AiScene, AiEndpoint> endpoints = new java.util.LinkedHashMap<>();
        for (AiScene scene : BLUEPRINT_STAGES) {
            endpoints.put(scene, endpointResolver.resolve(scene));
        }
        purgeExpired();

        // 线程池里没有请求的 ThreadLocal，这里把上下文带过去：用量统计要用它取 user_id
        UserContext context = UserContextHolder.require();
        Task task = new Task(UUID.randomUUID().toString().replace("-", ""), userId);
        tasks.put(task.id, task);
        executor.submit(() -> run(task, endpoints, request, context));
        log.info("[blueprint-task] started taskId={} userId={} models={}", task.id, userId,
                endpoints.values().stream().map(AiEndpoint::model).distinct().toList());
        return toVO(task);
    }

    /** 查询任务进度；仅任务发起人可查。 */
    public BlueprintTaskVO status(String taskId, Long userId) {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) {
            throw BizException.of(ErrorCode.TASK_NOT_FOUND);
        }
        return toVO(task);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /* ------------------------------------------------------------------ */
    /* 任务执行                                                           */
    /* ------------------------------------------------------------------ */

    private void run(Task task, Map<AiScene, AiEndpoint> endpoints, BlueprintRequest request,
                     UserContext context) {
        UserContextHolder.set(context);
        long startedAt = System.currentTimeMillis();
        try {
            NovelBlueprint blueprint = new NovelBlueprint();
            // 建书前追问得到的创作约束随蓝图一起带回，建书时落库
            blueprint.setConstraintText(truncate(request.getConstraintText(), 2000));

            // 1. 世界观
            JsonNode worldview = callStage(task, endpoints, AiScene.BLUEPRINT_WORLDVIEW, 1, "正在生成世界观",
                    12, WORLDVIEW_PROMPT, request, null, 2400);
            blueprint.setWorldview(textOf(worldview, "worldview"));

            // 2. 背景
            JsonNode background = callStage(task, endpoints, AiScene.BLUEPRINT_BACKGROUND, 2, "正在生成背景设定",
                    36, BACKGROUND_PROMPT, request, blueprint, 2400);
            blueprint.setBackground(textOf(background, "background"));

            // 3. 简介 / 冲突 / 看点 / 题材标签
            JsonNode conflict = callStage(task, endpoints, AiScene.BLUEPRINT_CONFLICT, 3, "正在生成简介与核心冲突",
                    60, CONFLICT_PROMPT, request, blueprint, 2400);
            blueprint.setSynopsis(textOf(conflict, "synopsis"));
            blueprint.setCoreConflict(textOf(conflict, "coreConflict"));
            blueprint.setHighlights(normalizeList(textList(conflict, "highlights")));
            blueprint.setStyle(textOf(conflict, "style"));
            if (!hasText(blueprint.getGenre())) {
                blueprint.setGenre(textOf(conflict, "genre"));
            }
            if (blueprint.getTags() == null || blueprint.getTags().isEmpty()) {
                blueprint.setTags(normalizeList(textList(conflict, "tags")));
            }

            // 4. 人物
            JsonNode characters = callStage(task, endpoints, AiScene.BLUEPRINT_CHARACTER, 4, "正在生成人物信息",
                    84, CHARACTER_PROMPT, request, blueprint, 3000);
            blueprint.setProtagonist(readCharacter(characters.path("protagonist"), true));
            List<CharacterDraft> list = new ArrayList<>();
            JsonNode listNode = characters.path("characters");
            if (!listNode.isArray()) {
                listNode = characters.path("supportingCharacters");
            }
            if (listNode.isArray()) {
                for (JsonNode node : listNode) {
                    if (list.size() >= 12) {
                        break;
                    }
                    CharacterDraft draft = readCharacter(node, false);
                    if (hasText(draft.getName())) {
                        list.add(draft);
                    }
                }
            }
            blueprint.setCharacters(list);

            // 5. 整理
            task.step(5, "正在整理设定", 94);
            applyUserInput(blueprint, request);
            validate(blueprint);

            BlueprintResultVO result = new BlueprintResultVO();
            result.setBlueprint(blueprint);
            // 四段可能绑定不同模型，这里展示第一段的模型，完整映射见启动日志
            result.setModel(endpoints.get(AiScene.BLUEPRINT_WORLDVIEW).model());
            result.setLatencyMs(System.currentTimeMillis() - startedAt);

            task.succeed(result);
            log.info("[blueprint-task] succeeded taskId={} userId={} latency={}ms",
                    task.id, task.userId, result.getLatencyMs());
        } catch (BizException ex) {
            task.fail(ex.getMessage());
            log.warn("[blueprint-task] failed taskId={} userId={}: {}", task.id, task.userId, ex.getMessage());
        } catch (RuntimeException ex) {
            task.fail(hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败");
            log.warn("[blueprint-task] failed taskId={} userId={}: {}", task.id, task.userId, ex.toString());
        } finally {
            UserContextHolder.clear();
        }
    }

    /**
     * 单段调用：每段取自己的端点与自己的提示词模板；
     * 解析失败（50002）时用更严格的提示词自动重试一次。
     */
    private JsonNode callStage(Task task, Map<AiScene, AiEndpoint> endpoints, AiScene scene,
                               int stepNo, String label, int percent,
                               String fallbackPrompt, BlueprintRequest request,
                               NovelBlueprint current, int maxTokens) {
        task.step(stepNo, label, percent);
        AiEndpoint base = endpoints.get(scene);
        String systemPrompt = systemPrompt(scene, request, current, fallbackPrompt);
        String userPrompt = buildPrompt(request, current, promptTemplateService.usedVariables(scene));
        try {
            return parse(chat(scene, base, systemPrompt, userPrompt, maxTokens).content(), label);
        } catch (BizException ex) {
            if (ex.getErrorCode() != ErrorCode.AI_PARSE_FAILED) {
                throw ex;
            }
            task.retry(stepNo, label, percent + 2);
            log.warn("[blueprint-task] stage parse failed, retry taskId={} stage={}", task.id, label);
            return parse(chat(scene, base, systemPrompt + RETRY_SUFFIX, userPrompt, maxTokens).content(), label);
        }
    }

    /**
     * 用户自定义过就用模板，否则回退内置常量。
     *
     * <p>作者在表单里填的创意、风格、篇幅、主角偏好，以及前几段已经生成的结果（世界观、背景、简介）
     * 都作为变量交出去；模板里引用哪一个，user 段里同名的那一块就不再重复。</p>
     */
    private String systemPrompt(AiScene scene, BlueprintRequest request,
                                NovelBlueprint current, String fallback) {
        Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("novelTitle", hasText(request.getTitle()) ? request.getTitle().trim() : "这本书");
        vars.put("genre", hasText(request.getGenre()) ? request.getGenre().trim() : "通用");
        vars.put("tags", request.getTags() == null ? "" : String.join("、", request.getTags()));
        vars.put("idea", request.getIdea());
        vars.put("style", request.getStyle());
        vars.put("targetLength", request.getTargetLength());
        vars.put("protagonistHint", request.getProtagonistHint());
        vars.put("constraints", request.getConstraintText());
        if (current != null) {
            vars.put("worldview", current.getWorldview());
            vars.put("background", current.getBackground());
            vars.put("synopsis", current.getSynopsis());
        }
        String rendered = promptTemplateService.system(scene, PromptVars.clean(vars));
        return hasText(rendered) ? rendered : fallback;
    }

    private ChatResult chat(AiScene scene, AiEndpoint base, String systemPrompt, String userPrompt,
                            int maxTokens) {
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), STAGE_TIMEOUT_MS),
                base.extraHeaders(),
                0.85D,
                maxTokens);
        try {
            return aiUsageService.chat(scene, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw BizException.of(ErrorCode.AI_CALL_FAILED,
                    hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败");
        }
    }

    private JsonNode parse(String content, String stageName) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[blueprint-task] stage {} 未返回 JSON，原始输出：{}", stageName, abbreviate(content));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED,
                    "生成" + stageName.replace("正在生成", "") + "时模型没有返回结构化内容，请重试");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[blueprint-task] stage {} 解析失败：{} | 原始输出：{}",
                        stageName, strictFailure.getMessage(), abbreviate(candidate));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED,
                        "生成" + stageName.replace("正在生成", "") + "时模型返回的内容无法解析，请重试");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 提示词与数据整理                                                   */
    /* ------------------------------------------------------------------ */

    private String buildPrompt(BlueprintRequest request, NovelBlueprint current, java.util.Set<String> used) {
        StringBuilder builder = new StringBuilder();
        StringBuilder author = new StringBuilder();
        if (PromptVars.emit(used, "novelTitle")) {
            appendLine(author, "书名", request.getTitle());
        }
        if (PromptVars.emit(used, "genre")) {
            appendLine(author, "题材", request.getGenre());
        }
        if (PromptVars.emit(used, "tags") && request.getTags() != null && !request.getTags().isEmpty()) {
            appendLine(author, "标签", String.join("、", request.getTags()));
        }
        if (PromptVars.emit(used, "idea")) {
            appendLine(author, "核心创意", request.getIdea());
        }
        if (PromptVars.emit(used, "style")) {
            appendLine(author, "风格偏好", request.getStyle());
        }
        if (PromptVars.emit(used, "targetLength")) {
            appendLine(author, "目标篇幅", request.getTargetLength());
        }
        if (PromptVars.emit(used, "protagonistHint")) {
            appendLine(author, "主角偏好", request.getProtagonistHint());
        }
        if (!author.isEmpty()) {
            builder.append("【作者信息】\n").append(author);
        }

        // 建书前追问出来的约束：优先于 AI 自己的推断，必须放在设定之前
        if (hasText(request.getConstraintText()) && PromptVars.emit(used, "constraints")) {
            builder.append("\n【创作约束（作者已确认，必须遵守）】\n")
                    .append(abbreviate(request.getConstraintText(), 1200)).append("\n");
        }

        if (current != null) {
            if (hasText(current.getWorldview()) && PromptVars.emit(used, "worldview")) {
                builder.append("\n【已确定的世界观】\n").append(abbreviate(current.getWorldview(), 1500)).append("\n");
            }
            if (hasText(current.getBackground()) && PromptVars.emit(used, "background")) {
                builder.append("\n【已确定的背景】\n").append(abbreviate(current.getBackground(), 1200)).append("\n");
            }
            if (hasText(current.getSynopsis()) && PromptVars.emit(used, "synopsis")) {
                builder.append("\n【已确定的简介】\n").append(abbreviate(current.getSynopsis(), 800)).append("\n");
            }
        }
        return builder.toString();
    }

    private void appendLine(StringBuilder builder, String label, String value) {
        if (hasText(value)) {
            builder.append(label).append("：").append(value.trim()).append("\n");
        }
    }

    private void applyUserInput(NovelBlueprint blueprint, BlueprintRequest request) {
        if (hasText(request.getTitle())) {
            blueprint.setTitle(request.getTitle().trim());
        } else if (!hasText(blueprint.getTitle())) {
            blueprint.setTitle("未命名小说");
        }
        if (hasText(request.getGenre())) {
            blueprint.setGenre(request.getGenre().trim());
        }
        if (request.getTags() != null && !request.getTags().isEmpty()) {
            blueprint.setTags(normalizeList(request.getTags()));
        }
        if (hasText(request.getStyle())) {
            blueprint.setStyle(truncate(request.getStyle(), 200));
        }
    }

    private void validate(NovelBlueprint blueprint) {
        if (blueprint.getTags() == null) {
            blueprint.setTags(new ArrayList<>());
        }
        if (blueprint.getHighlights() == null) {
            blueprint.setHighlights(new ArrayList<>());
        }
        if (blueprint.getCharacters() == null) {
            blueprint.setCharacters(new ArrayList<>());
        }
        boolean missing = !hasText(blueprint.getWorldview())
                && !hasText(blueprint.getBackground())
                && !hasText(blueprint.getSynopsis());
        if (missing) {
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "模型返回的设定缺少关键内容，请重试");
        }
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
        return draft;
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

    private List<String> normalizeList(List<String> raw) {
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
            result.add(truncate(value, 24));
            if (result.size() >= 8) {
                break;
            }
        }
        return result;
    }

    private BlueprintTaskVO toVO(Task task) {
        return new BlueprintTaskVO(
                task.id,
                task.status,
                task.percent,
                Math.min(task.stepNo, STEP_TOTAL),
                STEP_TOTAL,
                task.stepLabel,
                task.message,
                task.result,
                task.errorMessage);
    }

    /** 惰性清理：只清理已结束且超过 TTL 的任务。 */
    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Task>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next().getValue();
            if (!STATUS_RUNNING.equals(task.status) && now - task.updatedAt > TASK_TTL_MS) {
                iterator.remove();
            }
        }
    }

    private String abbreviate(String value) {
        return abbreviate(value, 1200);
    }

    private String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "...";
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

    /** 内存任务快照。字段用 volatile 保证前端轮询能看到最新进度。 */
    private static final class Task {

        private final String id;
        private final Long userId;
        private volatile String status = STATUS_RUNNING;
        private volatile int percent = 4;
        private volatile int stepNo = 0;
        private volatile String stepLabel = "正在准备";
        private volatile String message;
        private volatile BlueprintResultVO result;
        private volatile String errorMessage;
        private volatile long updatedAt = System.currentTimeMillis();

        private Task(String id, Long userId) {
            this.id = id;
            this.userId = userId;
        }

        private void step(int stepNo, String label, int percent) {
            this.stepNo = stepNo;
            this.stepLabel = label;
            this.percent = Math.min(percent, 99);
            this.message = null;
            this.updatedAt = System.currentTimeMillis();
        }

        private void retry(int stepNo, String label, int percent) {
            this.stepNo = stepNo;
            this.stepLabel = label;
            this.percent = Math.min(percent, 99);
            this.message = "上一段返回格式不合法，正在重新生成";
            this.updatedAt = System.currentTimeMillis();
        }

        private void succeed(BlueprintResultVO result) {
            this.result = result;
            this.status = STATUS_SUCCEEDED;
            this.percent = 100;
            this.stepLabel = "生成完成";
            this.message = null;
            this.updatedAt = System.currentTimeMillis();
        }

        private void fail(String message) {
            this.status = STATUS_FAILED;
            this.errorMessage = message;
            this.stepLabel = "生成失败";
            this.updatedAt = System.currentTimeMillis();
        }
    }
}
