package com.storyforge.modules.inspiration.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.storyforge.modules.bookshelf.dto.CharacterGenerateRequest;
import com.storyforge.modules.bookshelf.dto.EventGenerateRequest;
import com.storyforge.modules.bookshelf.dto.FactionGenerateRequest;
import com.storyforge.modules.bookshelf.dto.ItemGenerateRequest;
import com.storyforge.modules.bookshelf.dto.OutlineGenerateRequest;
import com.storyforge.modules.bookshelf.entity.SettingDocEntity;
import com.storyforge.modules.bookshelf.mapper.SettingDocMapper;
import com.storyforge.modules.bookshelf.service.AiCharacterService;
import com.storyforge.modules.bookshelf.service.AiEventService;
import com.storyforge.modules.bookshelf.service.AiFactionService;
import com.storyforge.modules.bookshelf.service.AiItemService;
import com.storyforge.modules.bookshelf.service.AiOutlineService;
import com.storyforge.modules.inspiration.dto.CreateNovelResult;
import com.storyforge.modules.inspiration.dto.NovelCreationTaskVO;
import com.storyforge.modules.inspiration.entity.InspirationMessageEntity;
import com.storyforge.modules.inspiration.entity.InspirationSessionEntity;
import com.storyforge.modules.inspiration.mapper.InspirationMessageMapper;
import com.storyforge.modules.inspiration.mapper.InspirationSessionMapper;
import com.storyforge.modules.novel.dto.NovelVO;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 灵感模式「一键创建小说」的完整落库任务。
 *
 * <p>取代原来只建书名 + 简介的同步接口：把整个开书过程拆成 9 步，每步独立 AI 调用、
 * 独立超时（5 分钟），前端按 {@link #status} 轮询拿到百分比与当前阶段文案，用进度条
 * 展示真实进度。</p>
 *
 * <p>九步：① 提炼要素并建书 ② 世界观 ③ 背景 ④ 简介冲突与风格 ⑤ 关键人物与关系
 * ⑥ 事件（可选） ⑦ 五章大纲 ⑧ 物品（可选） ⑨ 势力（可选）。核心步骤（①~⑤、⑦）
 * 失败则任务失败；可选步骤失败仅记录提示，不阻断开书。</p>
 *
 * <p>任务只存在内存（单机部署），进程重启即丢——可接受：失败用户重试即可。
 * 已结束的任务保留 30 分钟供前端补偿拉取，随后惰性清理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelCreationTaskService {

    /** 每步 AI 调用超时：5 分钟。 */
    private static final int STAGE_TIMEOUT_MS = 300_000;
    /** 参与世界观 / 背景 / 冲突生成的对话最长字符数。 */
    private static final int TRANSCRIPT_MAX_CHARS = 14_000;
    private static final int STEP_TOTAL = 10;
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
            基于已确定的世界观与背景，写核心冲突、看点与文风。

            【设计方法】
            1. 核心冲突写清双方的目标与底线：为什么双方都不能退让，冲突会如何升级。
            2. 看点要具体到「可想象的画面或桥段」，不要写「节奏紧凑」「设定新颖」。
            3. 文风写清人称、叙述距离与语言质感（冷硬/市井/克制/热烈），不要只写「轻松幽默」。

            【输出格式】
            {
              "coreConflict": "150~250 字：双方目标、底线、升级方式、最终要回答的问题",
              "highlights": ["3~5 条核心看点，每条不超过 20 字，必须是具体桥段或设定"],
              "style": "人称 + 叙述距离 + 语言质感，不超过 40 字"
            }
            """ + COMMON_RULES;

    private static final String RETRY_SUFFIX = """
            上一次输出不是合法 JSON（多半是字符串里混入了英文双引号），这次务必只输出可被 JSON 解析器直接解析的对象。
            """;

    private final InspirationService inspirationService;
    private final InspirationSessionMapper sessionMapper;
    private final InspirationMessageMapper messageMapper;
    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;
    private final SettingDocMapper settingDocMapper;
    private final AiCharacterService aiCharacterService;
    private final AiOutlineService aiOutlineService;
    private final com.storyforge.modules.bookshelf.service.MasterOutlineService masterOutlineService;
    private final AiEventService aiEventService;
    private final AiItemService aiItemService;
    private final AiFactionService aiFactionService;
    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "novel-creation-" + threadSeq.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /** 启动一键建书任务。 */
    public NovelCreationTaskVO start(Long userId) {
        // 会话是否有内容、AI 端点是否可用，都在建书步骤（第一步）里校验，
        // 这里只做轻量前置检查：端点缺失在任务里会以失败状态暴露给前端。
        purgeExpired();
        UserContext context = UserContextHolder.require();
        Task task = new Task(UUID.randomUUID().toString().replace("-", ""), userId);
        tasks.put(task.id, task);
        executor.submit(() -> run(task, context));
        log.info("[novel-creation] started taskId={} userId={}", task.id, userId);
        return toVO(task);
    }

    /** 查询任务进度；仅任务发起人可查。 */
    public NovelCreationTaskVO status(String taskId, Long userId) {
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

    private void run(Task task, UserContext context) {
        UserContextHolder.set(context);
        Long userId = task.userId;
        List<String> warnings = new ArrayList<>();
        Long novelId = null;
        try {
            // 1. 提炼要素并建书（复用灵感服务的抽取 + 建草稿逻辑）
            task.step(1, "正在提炼故事要素", 8);
            CreateNovelResult created = inspirationService.createNovel(null);
            NovelVO novel = created.getNovel();
            novelId = novel.getId();
            task.novelId = novelId;
            task.novelTitle = novel.getTitle();
            String transcript = buildTranscript(userId);

            // 2. 世界观
            JsonNode worldviewNode = callSettingStage(task, novel, transcript,
                    2, "正在生成世界观", 18, AiScene.BLUEPRINT_WORLDVIEW, WORLDVIEW_PROMPT, "worldview");
            String worldview = textOf(worldviewNode, "worldview");

            // 3. 背景
            JsonNode backgroundNode = callSettingStage(task, novel, transcript,
                    3, "正在生成背景设定", 30, AiScene.BLUEPRINT_BACKGROUND, BACKGROUND_PROMPT, "background");
            String background = textOf(backgroundNode, "background");

            // 4. 简介冲突 / 看点 / 文风
            JsonNode conflictNode = callSettingStage(task, novel, transcript,
                    4, "正在生成核心冲突与文风", 42, AiScene.BLUEPRINT_CONFLICT, CONFLICT_PROMPT, "coreConflict");

            // 落设定文档（世界观 / 背景 / 核心冲突 / 看点 / 文风）
            int sort = 0;
            sort = insertSetting(novelId, userId, "WORLDVIEW", "世界观", worldview, sort);
            sort = insertSetting(novelId, userId, "BACKGROUND", "背景设定", background, sort);
            sort = insertSetting(novelId, userId, "CORE_CONFLICT", "核心冲突", textOf(conflictNode, "coreConflict"), sort);
            sort = insertSetting(novelId, userId, "HIGHLIGHT", "核心看点", joinLines(textList(conflictNode, "highlights")), sort);
            insertSetting(novelId, userId, "STYLE", "文风基调", textOf(conflictNode, "style"), sort);

            // 5. 关键人物与关系
            task.step(5, "正在生成关键人物与关系", 54);
            CharacterGenerateRequest characterRequest = new CharacterGenerateRequest();
            characterRequest.setPrompt("根据世界观、背景与核心冲突，创建本书的关键人物：1 个主角、主要配角与反派，共 6 人，并给出他们之间的人物关系。");
            characterRequest.setCount(6);
            aiCharacterService.generate(novelId, characterRequest);

            // 6. 事件（可选）
            task.step(6, "正在生成事件", 66);
            try {
                EventGenerateRequest eventRequest = new EventGenerateRequest();
                eventRequest.setPrompt("根据世界观与人物关系，补充一批能推动主线、可作为章节素材的事件。");
                eventRequest.setCount(3);
                aiEventService.generate(novelId, eventRequest);
            } catch (RuntimeException ex) {
                log.warn("[novel-creation] events skipped taskId={} novelId={}: {}", task.id, novelId, ex.toString());
                warnings.add("事件生成失败，可到工作台手动补充");
            }

            // 7. 总纲（指导后续章节大纲；资料充足时覆盖全书，不足时只生成一部分）
            Long firstMasterOutlineId = null;
            task.step(7, "正在规划总纲", 74);
            try {
                List<com.storyforge.modules.bookshelf.dto.MasterOutlineVO> outlines =
                        masterOutlineService.generate(novelId,
                                new com.storyforge.modules.bookshelf.dto.MasterOutlineGenerateRequest());
                if (outlines != null && !outlines.isEmpty()) {
                    firstMasterOutlineId = outlines.get(0).id();
                }
                log.info("[novel-creation] master outlines generated taskId={} novelId={} count={}",
                        task.id, novelId, outlines == null ? 0 : outlines.size());
            } catch (RuntimeException ex) {
                log.warn("[novel-creation] master outline skipped taskId={} novelId={}: {}",
                        task.id, novelId, ex.toString());
                warnings.add("总纲生成失败，可到工作台手动生成");
            }

            // 8. 五章大纲（核心）：关联第一条总纲，保证不脱离总纲范围
            task.step(8, "正在生成章节大纲", 86);
            OutlineGenerateRequest outlineRequest = new OutlineGenerateRequest();
            outlineRequest.setChapterCount(5);
            outlineRequest.setMasterOutlineId(firstMasterOutlineId);
            aiOutlineService.generate(novelId, outlineRequest);

            // 9. 物品（可选）
            task.step(9, "正在生成物品", 93);
            try {
                ItemGenerateRequest itemRequest = new ItemGenerateRequest();
                itemRequest.setPrompt("根据世界观与人物，设计几个能推动剧情的核心物品、线索或信物。");
                itemRequest.setCount(3);
                aiItemService.generate(novelId, itemRequest);
            } catch (RuntimeException ex) {
                log.warn("[novel-creation] items skipped taskId={} novelId={}: {}", task.id, novelId, ex.toString());
                warnings.add("物品生成失败，可到工作台手动补充");
            }

            // 10. 势力（可选）
            task.step(10, "正在生成势力", 96);
            try {
                FactionGenerateRequest factionRequest = new FactionGenerateRequest();
                factionRequest.setPrompt("根据世界观与人物，设计几个能驱动剧情的势力或阵营，并把关键角色安排到对应职位。");
                factionRequest.setCount(2);
                aiFactionService.generate(novelId, factionRequest);
            } catch (RuntimeException ex) {
                log.warn("[novel-creation] factions skipped taskId={} novelId={}: {}", task.id, novelId, ex.toString());
                warnings.add("势力生成失败，可到工作台手动补充");
            }

            task.succeed(novelId, novel.getTitle(),
                    warnings.isEmpty() ? null : String.join("；", warnings));
            log.info("[novel-creation] succeeded taskId={} userId={} novelId={}",
                    task.id, userId, novelId);
        } catch (BizException ex) {
            task.fail(ex.getMessage());
            log.warn("[novel-creation] failed taskId={} userId={}: {}", task.id, userId, ex.getMessage());
        } catch (RuntimeException ex) {
            task.fail(hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败");
            log.warn("[novel-creation] failed taskId={} userId={}: {}", task.id, userId, ex.toString());
        } finally {
            UserContextHolder.clear();
        }
    }

    /** 世界观 / 背景 / 冲突三段共用的调用：取对应场景端点与模板，解析失败自动重试一次。 */
    private JsonNode callSettingStage(Task task, NovelVO novel, String transcript,
                                      int stepNo, String label, int percent,
                                      AiScene scene, String fallbackPrompt, String requiredField) {
        task.step(stepNo, label, percent);
        AiEndpoint base = endpointResolver.resolve(scene);
        String systemPrompt = systemPrompt(scene, novel, fallbackPrompt);
        String userPrompt = buildSettingPrompt(novel, transcript);
        try {
            JsonNode result = parse(chat(scene, base, systemPrompt, userPrompt).content(), label);
            if (!hasText(textOf(result, requiredField))) {
                // 缺少关键字段视作解析失败，走重试
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, "生成" + label.replace("正在生成", "") + "时缺少关键内容");
            }
            return result;
        } catch (BizException ex) {
            if (ex.getErrorCode() != ErrorCode.AI_PARSE_FAILED) {
                throw ex;
            }
            task.retry(stepNo, label, percent + 2);
            log.warn("[novel-creation] stage parse failed, retry taskId={} stage={}", task.id, label);
            return parse(chat(scene, base, systemPrompt + RETRY_SUFFIX, userPrompt).content(), label);
        }
    }

    private ChatResult chat(AiScene scene, AiEndpoint base, String systemPrompt, String userPrompt) {
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), STAGE_TIMEOUT_MS),
                base.extraHeaders(),
                0.85D,
                2400);
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

    /**
     * 用户自定义过就用模板，否则回退内置常量。
     *
     * <p>这条路径的输入是灵感对话而不是建书表单，所以只填作品级变量；
     * 蓝图模板里若引用 {@code idea}、{@code protagonistHint} 这类表单字段，这里会渲染为空
     * （页面上的变量说明有写）。</p>
     */
    private String systemPrompt(AiScene scene, NovelVO novel, String fallback) {
        Map<String, String> vars = new java.util.LinkedHashMap<>();
        vars.put("novelTitle", novel == null || !hasText(novel.getTitle()) ? "这本书" : novel.getTitle());
        vars.put("genre", novel == null || !hasText(novel.getGenre()) ? "通用" : novel.getGenre());
        vars.put("tags", novel == null || novel.getTags() == null ? "" : String.join("、", novel.getTags()));
        vars.put("synopsis", novel == null || !hasText(novel.getIntro()) ? "" : novel.getIntro().trim());
        String rendered = promptTemplateService.system(scene, vars);
        return hasText(rendered) ? rendered : fallback;
    }

    private String buildSettingPrompt(NovelVO novel, String transcript) {
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
            builder.append("简介：").append(abbreviate(novel.getIntro(), 800)).append("\n");
        }
        if (hasText(transcript)) {
            builder.append("\n【作者与策划的对话记录】（构思来源，务必贴合其中已确定的设定）\n")
                    .append(transcript).append("\n");
        }
        return builder.toString();
    }

    /** 组装当前会话对话记录，供设定生成使用。 */
    private String buildTranscript(Long userId) {
        InspirationSessionEntity session = sessionMapper.selectOne(Wrappers.<InspirationSessionEntity>lambdaQuery()
                .eq(InspirationSessionEntity::getUserId, userId)
                .orderByDesc(InspirationSessionEntity::getCreatedAt)
                .last("limit 1"));
        if (session == null) {
            return "";
        }
        List<InspirationMessageEntity> messages = messageMapper.selectList(
                Wrappers.<InspirationMessageEntity>lambdaQuery()
                        .eq(InspirationMessageEntity::getSessionId, session.getId())
                        .orderByAsc(InspirationMessageEntity::getId));
        StringBuilder builder = new StringBuilder();
        for (InspirationMessageEntity item : messages) {
            if (!"OK".equals(item.getStatus())) {
                continue;
            }
            if (!"USER".equals(item.getRole()) && !"ASSISTANT".equals(item.getRole())) {
                continue;
            }
            String text = item.getContent() == null ? "" : item.getContent().trim();
            if (text.isEmpty()) {
                continue;
            }
            builder.append("USER".equals(item.getRole()) ? "作者：" : "策划：")
                    .append(text)
                    .append("\n\n");
        }
        String transcript = builder.toString().trim();
        if (transcript.length() <= TRANSCRIPT_MAX_CHARS) {
            return transcript;
        }
        int head = 5_000;
        int tail = TRANSCRIPT_MAX_CHARS - head;
        return transcript.substring(0, head)
                + "\n\n……（中间内容省略）……\n\n"
                + transcript.substring(transcript.length() - tail);
    }

    /* ------------------------------------------------------------------ */
    /* 落库与解析                                                         */
    /* ------------------------------------------------------------------ */

    private int insertSetting(Long novelId, Long userId, String category, String title, String content, int sort) {
        if (!hasText(content)) {
            return sort;
        }
        SettingDocEntity entity = new SettingDocEntity();
        entity.setUserId(userId);
        entity.setNovelId(novelId);
        entity.setCategory(category);
        entity.setTitle(title);
        entity.setContentMd(content.trim());
        entity.setInjectScope("GLOBAL");
        entity.setSort(sort);
        entity.setDeleted(0);
        entity.setVersion(0);
        settingDocMapper.insert(entity);
        return sort + 1;
    }

    private JsonNode parse(String content, String stageName) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[novel-creation] stage {} 未返回 JSON，原始输出：{}", stageName, abbreviate(content));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED,
                    "生成" + stageName.replace("正在生成", "") + "时模型没有返回结构化内容，请重试");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[novel-creation] stage {} 解析失败：{} | 原始输出：{}",
                        stageName, strictFailure.getMessage(), abbreviate(candidate));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED,
                        "生成" + stageName.replace("正在生成", "") + "时模型返回的内容无法解析，请重试");
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

    /* ------------------------------------------------------------------ */
    /* 任务快照与工具                                                     */
    /* ------------------------------------------------------------------ */

    private NovelCreationTaskVO toVO(Task task) {
        return new NovelCreationTaskVO(
                task.id,
                task.status,
                task.percent,
                Math.min(task.stepNo, STEP_TOTAL),
                STEP_TOTAL,
                task.stepLabel,
                task.message,
                task.novelId,
                task.novelTitle,
                task.errorMessage);
    }

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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 内存任务快照。字段用 volatile 保证前端轮询能看到最新进度。 */
    private static final class Task {

        private final String id;
        private final Long userId;
        private volatile String status = STATUS_RUNNING;
        private volatile int percent = 3;
        private volatile int stepNo = 0;
        private volatile String stepLabel = "正在准备";
        private volatile String message;
        private volatile Long novelId;
        private volatile String novelTitle;
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

        private void succeed(Long novelId, String novelTitle, String message) {
            this.novelId = novelId;
            this.novelTitle = novelTitle;
            this.message = message;
            this.status = STATUS_SUCCEEDED;
            this.percent = 100;
            this.stepLabel = "创建完成";
            this.updatedAt = System.currentTimeMillis();
        }

        private void fail(String message) {
            this.status = STATUS_FAILED;
            this.errorMessage = message;
            this.stepLabel = "创建失败";
            this.updatedAt = System.currentTimeMillis();
        }
    }
}
