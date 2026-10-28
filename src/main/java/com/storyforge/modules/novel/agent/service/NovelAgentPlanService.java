package com.storyforge.modules.novel.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.DataPermissionContext;
import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.modules.novel.agent.dto.AgentOperationVO;
import com.storyforge.modules.novel.agent.dto.AgentPlanStepVO;
import com.storyforge.modules.novel.agent.dto.AgentPlanVO;
import com.storyforge.modules.novel.agent.dto.AgentSendRequest;
import com.storyforge.modules.novel.agent.entity.NovelAgentPlanEntity;
import com.storyforge.modules.novel.agent.entity.NovelAgentPlanStepEntity;
import com.storyforge.modules.novel.agent.mapper.NovelAgentPlanMapper;
import com.storyforge.modules.novel.agent.mapper.NovelAgentPlanStepMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 智能助手「任务计划」引擎：把一条复杂指令拆成步骤，后台连续执行到完成。
 *
 * <p>老实现是两个极端：同步接口只能在一次请求里跑 6 轮多步，跑不完就断；异步任务虽然挪到
 * 了后台，但轮数上限、进度含义（第几轮 ≠ 做完了几件事）和「必须由用户回一句继续」这三个
 * 问题都没解决。这里的做法：</p>
 *
 * <ol>
 *   <li><b>先规划</b>：一次模型调用把指令拆成 2~8 步，每步带标题（给作者看）和说明（给模型执行）；</li>
 *   <li><b>再连续执行</b>：按步推进，每步内部最多 3 轮工具调用，步骤之间不需要用户插话；</li>
 *   <li><b>可跟踪</b>：每一步的状态、说明、执行过的操作都落库，刷新页面也能看到做到哪了；</li>
 *   <li><b>可干预</b>：随时暂停 / 继续 / 取消；连续两步失败自动暂停，避免把书改坏。</li>
 * </ol>
 *
 * <p>线程模型与自动写书一致：请求线程只负责落库+提交任务，执行在独立线程池里；执行线程没有
 * 请求的 ThreadLocal，所以先把 {@link UserContext} 捕获进来再 set，数据隔离才不会失效。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelAgentPlanService {

    /** 规划出的步骤上限：步骤越多单步越碎，模型反而容易跑偏 */
    private static final int MAX_STEPS = 8;
    /** 单步内部最多几轮工具调用（一轮能改一批，3 轮足够覆盖绝大多数指令） */
    private static final int MAX_TURNS_PER_STEP = 3;
    /** 单轮 AI 调用失败重试次数（不含首次） */
    private static final int TURN_RETRY = 1;
    /** 连续几步失败就暂停等人工（不暂停会一路错到底） */
    private static final int PAUSE_AFTER_FAILED_STEPS = 2;
    /** 写进会话的每步摘要长度上限 */
    private static final int REPLY_SUMMARY_CHARS = 300;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String PLANNER_INSTRUCTION = """
            【任务规划模式】
            这次不要改任何内容，只做规划：把作者的指令拆成若干可执行、可验证的步骤。

            规则：
            1. 步骤之间必须没有重叠，前一步做完后，后一步不需要重做前面的事；
            2. 每一步都要能在一次工具调用里完成（例如「重建全部总纲」算一步，「重建总纲 + 全部章节大纲」不算）；
            3. 步骤数控制在 2~8 条，能合并就合并；
            4. 如果指令本身只要改一处，也要给出 1~2 步，不要拆碎；
            5. 涉及删除/重建时，明确写出「先清掉什么、再建什么」。

            只输出下面这个 JSON，不要任何解释、不要代码块围栏：
            {"steps":[{"title":"一句话说明这一步做什么","detail":"执行这一步时的具体要求，包含范围、数量、判断标准"}]}
            """;

    private static final String EXECUTOR_INSTRUCTION = """
            【单步执行模式】
            你现在在执行一个多步任务中的某一步，只做这一步。

            要求：
            1. 只处理【当前步骤】范围内的修改，不要提前做后面的步骤，也不要重复已完成的内容；
            2. 需要引用已有实体时，id 必须取自【当前小说状态】或上一轮执行结果，绝不能编造；
            3. 该删的先删、该改的改完，保证这一步结束时是自洽的；
            4. 这一步做完，用一两句话说明你改了什么，并把全部修改放进 operations。

            正常输出 {"reply":"…","operations":[…] }；如果这一步确实无事可做，
            输出 {"reply":"说明原因","operations":[],"done":true}。
            """;

    private final NovelAgentPlanMapper planMapper;
    private final NovelAgentPlanStepMapper stepMapper;
    private final NovelAgentService agentService;
    private final ObjectMapper objectMapper;

    /** 计划 id → 运行控制（暂停 / 取消都是协作式的，靠这个标志让执行线程自己收尾） */
    private final Map<Long, Control> controls = new ConcurrentHashMap<>();

    private final AtomicInteger threadSeq = new AtomicInteger();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "agent-plan-" + threadSeq.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /* ------------------------------------------------------------------ */
    /* 对外入口                                                           */
    /* ------------------------------------------------------------------ */

    /** 为一条指令建立计划并立即开始执行。 */
    public AgentPlanVO start(Long novelId, AgentSendRequest request) {
        UserContext context = UserContextHolder.require();
        String goal = request == null || request.getContent() == null ? "" : request.getContent().trim();
        if (goal.isEmpty()) {
            throw BizException.of(ErrorCode.AGENT_PLAN_PLANNING_FAILED);
        }
        NovelAgentPlanEntity running = findRunningPlan(novelId);
        if (running != null) {
            throw BizException.of(ErrorCode.AGENT_PLAN_RUNNING);
        }

        NovelAgentPlanEntity plan = new NovelAgentPlanEntity();
        plan.setUserId(context.userId());
        plan.setNovelId(novelId);
        plan.setGoal(goal);
        plan.setStatus(NovelAgentPlanEntity.STATUS_PLANNING);
        plan.setStepTotal(0);
        plan.setStepDone(0);
        plan.setPercent(0);
        plan.setDeleted(0);
        plan.setVersion(0);
        planMapper.insert(plan);

        // 原始指令先进会话，聊天记录与计划卡对得上
        agentService.appendUserMessage(novelId, goal);

        controls.put(plan.getId(), new Control());
        executor.submit(() -> run(plan.getId(), context));
        log.info("[agent-plan] 已创建 planId={} novelId={} steps 未知", plan.getId(), novelId);
        return status(plan.getId());
    }

    /** 查询计划详情；不属于自己或不存在都按 404 处理。 */
    public AgentPlanVO status(Long planId) {
        NovelAgentPlanEntity plan = planMapper.selectById(planId);
        if (plan == null) {
            throw BizException.of(ErrorCode.AGENT_PLAN_NOT_FOUND);
        }
        return toVO(plan, listSteps(planId));
    }

    /** 某书最近一次计划，没有则返回 null（前端打开面板时恢复任务卡用）。 */
    public AgentPlanVO latest(Long novelId) {
        NovelAgentPlanEntity plan = planMapper.selectOne(Wrappers.<NovelAgentPlanEntity>lambdaQuery()
                .eq(NovelAgentPlanEntity::getNovelId, novelId)
                .orderByDesc(NovelAgentPlanEntity::getCreatedAt)
                .last("limit 1"));
        return plan == null ? null : toVO(plan, listSteps(plan.getId()));
    }

    /** 暂停：当前步做完后停在下一步之前。 */
    public AgentPlanVO pause(Long novelId, Long planId) {
        NovelAgentPlanEntity plan = requirePlan(novelId, planId);
        if (!NovelAgentPlanEntity.STATUS_RUNNING.equals(plan.getStatus())
                && !NovelAgentPlanEntity.STATUS_PLANNING.equals(plan.getStatus())) {
            throw BizException.of(ErrorCode.AGENT_PLAN_STATE_INVALID);
        }
        Control control = controls.get(planId);
        if (control == null) {
            // 没有在跑的线程（例如服务重启后遗留的 RUNNING），直接落库为暂停
            patchPlan(planId, NovelAgentPlanEntity.STATUS_PAUSED, "已手动暂停");
            return status(planId);
        }
        control.pauseRequested = true;
        log.info("[agent-plan] 收到暂停请求 planId={}", planId);
        return status(planId);
    }

    /** 继续：把失败/未完成的步骤重置后从断点接着跑。 */
    public AgentPlanVO resume(Long novelId, Long planId) {
        NovelAgentPlanEntity plan = requirePlan(novelId, planId);
        if (!plan.resumable()) {
            throw BizException.of(ErrorCode.AGENT_PLAN_STATE_INVALID);
        }
        if (controls.containsKey(planId)) {
            throw BizException.of(ErrorCode.AGENT_PLAN_RUNNING);
        }
        // FAILED / RUNNING 的步骤是「上次没跑完」的，重置成待执行；DONE / SKIPPED 保留
        stepMapper.update(null, Wrappers.<NovelAgentPlanStepEntity>lambdaUpdate()
                .eq(NovelAgentPlanStepEntity::getPlanId, planId)
                .in(NovelAgentPlanStepEntity::getStatus,
                        NovelAgentPlanStepEntity.STATUS_FAILED, NovelAgentPlanStepEntity.STATUS_RUNNING)
                .set(NovelAgentPlanStepEntity::getStatus, NovelAgentPlanStepEntity.STATUS_PENDING)
                .set(NovelAgentPlanStepEntity::getErrorMessage, null)
                .set(NovelAgentPlanStepEntity::getStartedAt, null)
                .set(NovelAgentPlanStepEntity::getFinishedAt, null));

        patchPlan(planId, NovelAgentPlanEntity.STATUS_RUNNING, null);
        UserContext context = UserContextHolder.require();
        controls.put(planId, new Control());
        executor.submit(() -> resumeRun(planId, context));
        log.info("[agent-plan] 继续执行 planId={}", planId);
        return status(planId);
    }

    /** 取消：立刻停手，已做的修改不回滚（作者自己看得到，回滚反而更糟）。 */
    public AgentPlanVO cancel(Long novelId, Long planId) {
        NovelAgentPlanEntity plan = requirePlan(novelId, planId);
        if (plan.finished()) {
            return status(planId);
        }
        Control control = controls.get(planId);
        if (control != null) {
            control.cancelled = true;
        } else {
            patchPlan(planId, NovelAgentPlanEntity.STATUS_CANCELLED, "已取消");
        }
        log.info("[agent-plan] 收到取消请求 planId={}", planId);
        return status(planId);
    }

    /**
     * 启动时把「上次没跑完」的计划标成暂停。
     *
     * <p>进程重启后内存里的执行线程没了，库里的 RUNNING 会一直挂着，前端会永远显示「执行中」
     * 且无法再次启动——必须在这里收口，并把原因写清楚，作者点「继续」就能接着跑。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        List<NovelAgentPlanEntity> stuck = DataPermissionContext.withoutIsolation(
                () -> planMapper.selectList(Wrappers.<NovelAgentPlanEntity>lambdaQuery()
                        .in(NovelAgentPlanEntity::getStatus,
                                NovelAgentPlanEntity.STATUS_PLANNING, NovelAgentPlanEntity.STATUS_RUNNING)));
        for (NovelAgentPlanEntity plan : stuck) {
            DataPermissionContext.withoutIsolation(() -> {
                patchPlan(plan.getId(), NovelAgentPlanEntity.STATUS_PAUSED, "服务重启，任务已暂停，可点继续");
                stepMapper.update(null, Wrappers.<NovelAgentPlanStepEntity>lambdaUpdate()
                        .eq(NovelAgentPlanStepEntity::getPlanId, plan.getId())
                        .eq(NovelAgentPlanStepEntity::getStatus, NovelAgentPlanStepEntity.STATUS_RUNNING)
                        .set(NovelAgentPlanStepEntity::getStatus, NovelAgentPlanStepEntity.STATUS_PENDING));
                return null;
            });
        }
        if (!stuck.isEmpty()) {
            log.warn("[agent-plan] 启动重置 {} 个未完成计划为暂停", stuck.size());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 执行                                                               */
    /* ------------------------------------------------------------------ */

    private void run(Long planId, UserContext context) {
        UserContextHolder.set(context);
        Control control = controls.get(planId);
        NovelAgentPlanEntity plan = planMapper.selectById(planId);
        try {
            if (plan == null) {
                return;
            }
            List<StepSpec> specs = planSteps(plan);
            saveSteps(plan, specs);
            plan.setStatus(NovelAgentPlanEntity.STATUS_RUNNING);
            plan.setStepTotal(specs.size());
            plan.setPercent(5);
            patchPlan(planId, NovelAgentPlanEntity.STATUS_RUNNING, null, specs.size(), 0, 5, null);
            agentService.appendAssistantMessage(plan.getNovelId(),
                    "我把这条指令拆成了 " + specs.size() + " 步，现在开始执行：" + summaryOfTitles(specs),
                    List.of(), plan.getModel(), null, null);
            executeSteps(plan, control);
        } catch (BizException ex) {
            fail(planId, ex.getMessage());
        } catch (RuntimeException ex) {
            fail(planId, ex.getMessage() == null ? "任务执行失败" : ex.getMessage());
            log.error("[agent-plan] planId={} 异常结束", planId, ex);
        } finally {
            controls.remove(planId);
            UserContextHolder.clear();
        }
    }

    /** 继续执行（不重新规划，沿用已有步骤）。 */
    private void resumeRun(Long planId, UserContext context) {
        UserContextHolder.set(context);
        Control control = controls.get(planId);
        NovelAgentPlanEntity plan = planMapper.selectById(planId);
        try {
            if (plan == null) {
                return;
            }
            executeSteps(plan, control);
        } catch (RuntimeException ex) {
            fail(planId, ex.getMessage() == null ? "任务执行失败" : ex.getMessage());
            log.error("[agent-plan] planId={} 续跑异常", planId, ex);
        } finally {
            controls.remove(planId);
            UserContextHolder.clear();
        }
    }

    private void executeSteps(NovelAgentPlanEntity plan, Control control) {
        List<NovelAgentPlanStepEntity> steps = listSteps(plan.getId());
        if (steps.isEmpty()) {
            fail(plan.getId(), "计划里没有任何步骤");
            return;
        }
        int failedInRow = 0;
        for (NovelAgentPlanStepEntity step : steps) {
            if (control != null && control.cancelled) {
                patchPlan(plan.getId(), NovelAgentPlanEntity.STATUS_CANCELLED, "已取消");
                log.info("[agent-plan] planId={} 已取消", plan.getId());
                return;
            }
            if (control != null && control.pauseRequested) {
                patchPlan(plan.getId(), NovelAgentPlanEntity.STATUS_PAUSED, "已手动暂停");
                return;
            }
            if (step.settled()) {
                continue;
            }
            step.setStatus(NovelAgentPlanStepEntity.STATUS_RUNNING);
            step.setStartedAt(LocalDateTime.now());
            patchStep(step);
            try {
                StepOutcome outcome = runStep(plan, step, steps);
                step.setStatus(NovelAgentPlanStepEntity.STATUS_DONE);
                step.setReply(outcome.reply());
                step.setOpsJson(outcome.opsJson());
                step.setErrorMessage(null);
                step.setFinishedAt(LocalDateTime.now());
                patchStep(step);
                failedInRow = 0;

                agentService.appendAssistantMessage(plan.getNovelId(),
                        "第 " + step.getStepNo() + " 步「" + step.getTitle() + "」已完成："
                                + truncate(outcome.reply(), REPLY_SUMMARY_CHARS),
                        outcome.operations(),
                        outcome.model(), outcome.promptTokens(), outcome.completionTokens());
            } catch (RuntimeException ex) {
                step.setStatus(NovelAgentPlanStepEntity.STATUS_FAILED);
                step.setErrorMessage(truncate(ex.getMessage() == null ? "执行失败" : ex.getMessage(), 480));
                step.setFinishedAt(LocalDateTime.now());
                patchStep(step);
                failedInRow++;
                log.warn("[agent-plan] planId={} 第{}步失败: {}", plan.getId(), step.getStepNo(), ex.toString());
                if (failedInRow >= PAUSE_AFTER_FAILED_STEPS) {
                    patchPlan(plan.getId(), NovelAgentPlanEntity.STATUS_PAUSED,
                            "连续 " + failedInRow + " 步失败，已暂停："+ truncate(step.getErrorMessage(), 120));
                    return;
                }
            }
            refreshProgress(plan);
        }
        patchPlan(plan.getId(), NovelAgentPlanEntity.STATUS_DONE, null);
        NovelAgentPlanEntity latest = planMapper.selectById(plan.getId());
        agentService.appendAssistantMessage(plan.getNovelId(),
                "全部 " + (latest == null ? steps.size() : latest.getStepDone()) + " 步已执行完，任务结束。",
                List.of(), null, null, null);
        log.info("[agent-plan] planId={} 完成", plan.getId());
    }

    /**
     * 执行单步：最多 {@link #MAX_TURNS_PER_STEP} 轮，每轮把「这一步做到哪了」喂回模型。
     *
     * <p>一步之内也可能需要多轮——比如「重建 12 条总纲」一轮写不完，模型会只建一部分。
     * 因此这里保留轮内循环，但把边界收紧到「这一步」，跑偏成本比整条指令跑偏低得多。</p>
     */
    private StepOutcome runStep(NovelAgentPlanEntity plan, NovelAgentPlanStepEntity step,
                                List<NovelAgentPlanStepEntity> allSteps) {
        String system = agentService.systemPromptWith(plan.getNovelId(), EXECUTOR_INSTRUCTION);
        StringBuilder feedback = new StringBuilder();
        List<AgentOperationVO> collected = new ArrayList<>();
        String lastReply = "";
        String model = null;
        Integer promptTokens = null;
        Integer completionTokens = null;

        for (int turn = 1; turn <= MAX_TURNS_PER_STEP; turn++) {
            String userText = stepPrompt(plan, step, allSteps, turn) + feedback;
            NovelAgentService.AgentTurn result = callWithRetry(plan, system, userText);
            model = result.model();
            promptTokens = add(promptTokens, result.promptTokens());
            completionTokens = add(completionTokens, result.completionTokens());
            collected.addAll(result.operations());
            if (hasText(result.reply())) {
                lastReply = result.reply();
            }
            if (result.done() || result.operations().isEmpty()) {
                break;
            }
            feedback.append("\n【第 ").append(turn).append(" 轮已执行结果】\n").append(opsDigest(result.operations()))
                    .append("如果这一步还没做完，继续做完；做完了就输出 done: true。\n");
        }
        return new StepOutcome(lastReply, collected, writeJson(collected), model, promptTokens, completionTokens);
    }

    private NovelAgentService.AgentTurn callWithRetry(NovelAgentPlanEntity plan, String system, String userText) {
        RuntimeException last = null;
        for (int attempt = 0; attempt <= TURN_RETRY; attempt++) {
            try {
                return agentService.runTurn(plan.getNovelId(), system, userText);
            } catch (BizException ex) {
                last = ex;
                log.warn("[agent-plan] planId={} 单轮失败 第{}次: {}", plan.getId(), attempt + 1, ex.getMessage());
                if (attempt < TURN_RETRY) {
                    sleep(1200L);
                }
            } catch (RuntimeException ex) {
                last = ex;
                log.warn("[agent-plan] planId={} 单轮异常 第{}次: {}", plan.getId(), attempt + 1, ex.toString());
                if (attempt < TURN_RETRY) {
                    sleep(1200L);
                }
            }
        }
        throw last == null ? new IllegalStateException("AI 调用失败") : last;
    }

    /* ------------------------------------------------------------------ */
    /* 规划                                                               */
    /* ------------------------------------------------------------------ */

    private List<StepSpec> planSteps(NovelAgentPlanEntity plan) {
        String system = agentService.systemPromptWith(plan.getNovelId(), PLANNER_INSTRUCTION);
        String userText = "作者这次的指令是：\n" + plan.getGoal()
                + "\n\n请按【任务规划模式】的规则输出步骤 JSON。";
        String raw;
        try {
            raw = agentService.rawChat(plan.getNovelId(), system, userText);
        } catch (RuntimeException ex) {
            throw BizException.of(ErrorCode.AGENT_PLAN_PLANNING_FAILED);
        }
        List<StepSpec> specs = parseSteps(raw);
        if (specs.isEmpty()) {
            log.warn("[agent-plan] 规划结果无法解析 planId={} raw={}", plan.getId(), truncate(raw, 300));
            throw BizException.of(ErrorCode.AGENT_PLAN_PLANNING_FAILED);
        }
        if (specs.size() > MAX_STEPS) {
            specs = new ArrayList<>(specs.subList(0, MAX_STEPS));
        }
        return specs;
    }

    private List<StepSpec> parseSteps(String raw) {
        List<StepSpec> specs = new ArrayList<>();
        if (!hasText(raw)) {
            return specs;
        }
        JsonNode root = readJson(raw);
        JsonNode array = root == null ? null : root.path("steps");
        if (array == null || !array.isArray()) {
            // 兼容模型直接给数组的情况
            array = root != null && root.isArray() ? root : null;
        }
        if (array == null) {
            return specs;
        }
        int no = 1;
        for (JsonNode node : array) {
            String title = node.path("title").asText("").trim();
            String detail = node.path("detail").asText("").trim();
            if (title.isEmpty() && detail.isEmpty()) {
                continue;
            }
            if (title.isEmpty()) {
                title = truncate(detail, 60);
            }
            specs.add(new StepSpec(no++, truncate(title, 200), detail));
        }
        return specs;
    }

    private JsonNode readJson(String raw) {
        String candidate = LenientJson.extractObject(raw);
        if (candidate.isEmpty()) {
            candidate = raw.trim();
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strict) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 提示词与落库                                                       */
    /* ------------------------------------------------------------------ */

    private String stepPrompt(NovelAgentPlanEntity plan, NovelAgentPlanStepEntity step,
                              List<NovelAgentPlanStepEntity> allSteps, int turn) {
        StringBuilder builder = new StringBuilder();
        builder.append("【整体任务】\n").append(plan.getGoal()).append("\n\n");
        String done = doneSummary(allSteps, step.getStepNo());
        if (!done.isEmpty()) {
            builder.append("【已完成步骤】\n").append(done).append("\n");
        }
        builder.append("【当前步骤 第 ").append(step.getStepNo()).append(" / ")
                .append(allSteps.size()).append(" 步】").append(step.getTitle()).append("\n");
        if (hasText(step.getDetail())) {
            builder.append("具体要求：").append(step.getDetail()).append("\n");
        }
        if (turn > 1) {
            builder.append("\n这是这一步的第 ").append(turn).append(" 轮，接着上一轮继续。\n");
        }
        builder.append("\n只做这一步。");
        return builder.toString();
    }

    private String doneSummary(List<NovelAgentPlanStepEntity> steps, int beforeNo) {
        StringBuilder builder = new StringBuilder();
        for (NovelAgentPlanStepEntity item : steps) {
            if (item.getStepNo() >= beforeNo) {
                break;
            }
            if (!item.settled()) {
                continue;
            }
            builder.append(item.getStepNo()).append(". ").append(item.getTitle());
            if (hasText(item.getReply())) {
                builder.append(" —— ").append(truncate(item.getReply(), 80));
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private void saveSteps(NovelAgentPlanEntity plan, List<StepSpec> specs) {
        for (StepSpec spec : specs) {
            NovelAgentPlanStepEntity step = new NovelAgentPlanStepEntity();
            step.setUserId(plan.getUserId());
            step.setNovelId(plan.getNovelId());
            step.setPlanId(plan.getId());
            step.setStepNo(spec.no());
            step.setTitle(spec.title());
            step.setDetail(spec.detail());
            step.setStatus(NovelAgentPlanStepEntity.STATUS_PENDING);
            step.setDeleted(0);
            step.setVersion(0);
            stepMapper.insert(step);
        }
    }

    private void refreshProgress(NovelAgentPlanEntity plan) {
        List<NovelAgentPlanStepEntity> steps = listSteps(plan.getId());
        int total = steps.size();
        int done = (int) steps.stream().filter(NovelAgentPlanStepEntity::settled).count();
        int percent = total == 0 ? 0 : Math.min(99, 5 + done * 95 / total);
        patchPlan(plan.getId(), NovelAgentPlanEntity.STATUS_RUNNING, null, total, done, percent, null);
    }

    private void patchPlan(Long planId, String status, String pausedReason) {
        NovelAgentPlanEntity plan = planMapper.selectById(planId);
        patchPlan(planId, status, pausedReason,
                plan == null ? null : plan.getStepTotal(),
                plan == null ? null : plan.getStepDone(),
                plan == null ? null : plan.getPercent(),
                null);
    }

    /**
     * 更新计划字段。
     *
     * <p>用 {@code lambdaUpdate().set(...)} 而不是 {@code updateById}：后者会跳过 null，
     * 「把暂停原因/错误信息清空」这种操作根本写不进去（自动写书那边踩过一次）。</p>
     *
     * <p>注意：<b>同一列只能 set 一次</b>。PG 碰到 {@code SET percent = ?, percent = ?} 会直接报
     * 「对同一列 percent 进行了多次分配」，所以这里先把最终值算出来再拼 SQL。</p>
     */
    private void patchPlan(Long planId, String status, String pausedReason,
                           Integer stepTotal, Integer stepDone, Integer percent, String errorMessage) {
        boolean running = NovelAgentPlanEntity.STATUS_RUNNING.equals(status);
        boolean done = NovelAgentPlanEntity.STATUS_DONE.equals(status);
        // 完成时进度固定 100，优先于调用方传进来的「进行中」百分比
        Integer finalPercent = done ? Integer.valueOf(100) : percent;

        var update = Wrappers.<NovelAgentPlanEntity>lambdaUpdate()
                .eq(NovelAgentPlanEntity::getId, planId)
                .set(NovelAgentPlanEntity::getUpdatedAt, LocalDateTime.now());
        if (status != null) {
            update.set(NovelAgentPlanEntity::getStatus, status);
        }
        if (running) {
            // 重新开跑：上一轮的暂停原因/错误信息作废
            update.set(NovelAgentPlanEntity::getPausedReason, null)
                    .set(NovelAgentPlanEntity::getErrorMessage, null);
        } else {
            if (pausedReason != null) {
                update.set(NovelAgentPlanEntity::getPausedReason, pausedReason);
            }
            if (errorMessage != null) {
                update.set(NovelAgentPlanEntity::getErrorMessage, errorMessage);
            }
        }
        if (stepTotal != null) {
            update.set(NovelAgentPlanEntity::getStepTotal, stepTotal);
        }
        if (stepDone != null) {
            update.set(NovelAgentPlanEntity::getStepDone, stepDone);
        }
        if (finalPercent != null) {
            update.set(NovelAgentPlanEntity::getPercent, finalPercent);
        }
        planMapper.update(null, update);
    }

    private void patchStep(NovelAgentPlanStepEntity step) {
        var update = Wrappers.<NovelAgentPlanStepEntity>lambdaUpdate()
                .eq(NovelAgentPlanStepEntity::getId, step.getId())
                .set(NovelAgentPlanStepEntity::getStatus, step.getStatus())
                .set(NovelAgentPlanStepEntity::getUpdatedAt, LocalDateTime.now())
                .set(NovelAgentPlanStepEntity::getStartedAt, step.getStartedAt())
                .set(NovelAgentPlanStepEntity::getFinishedAt, step.getFinishedAt())
                .set(NovelAgentPlanStepEntity::getReply, step.getReply())
                .set(NovelAgentPlanStepEntity::getOpsJson, step.getOpsJson())
                .set(NovelAgentPlanStepEntity::getErrorMessage, step.getErrorMessage());
        stepMapper.update(null, update);
    }

    private void fail(Long planId, String message) {
        patchPlan(planId, NovelAgentPlanEntity.STATUS_FAILED, null, null, null, null,
                truncate(message, 480));
        log.warn("[agent-plan] planId={} 失败: {}", planId, message);
    }

    /* ------------------------------------------------------------------ */
    /* 查询与工具                                                         */
    /* ------------------------------------------------------------------ */

    private NovelAgentPlanEntity requirePlan(Long novelId, Long planId) {
        NovelAgentPlanEntity plan = planId == null ? null : planMapper.selectById(planId);
        if (plan == null || !novelId.equals(plan.getNovelId())) {
            throw BizException.of(ErrorCode.AGENT_PLAN_NOT_FOUND);
        }
        return plan;
    }

    private NovelAgentPlanEntity findRunningPlan(Long novelId) {
        for (Map.Entry<Long, Control> entry : controls.entrySet()) {
            NovelAgentPlanEntity plan = planMapper.selectById(entry.getKey());
            if (plan != null && novelId.equals(plan.getNovelId())
                    && !NovelAgentPlanEntity.STATUS_PAUSED.equals(plan.getStatus())) {
                return plan;
            }
        }
        return null;
    }

    private List<NovelAgentPlanStepEntity> listSteps(Long planId) {
        return stepMapper.selectList(Wrappers.<NovelAgentPlanStepEntity>lambdaQuery()
                .eq(NovelAgentPlanStepEntity::getPlanId, planId)
                .orderByAsc(NovelAgentPlanStepEntity::getStepNo));
    }

    private AgentPlanVO toVO(NovelAgentPlanEntity plan, List<NovelAgentPlanStepEntity> steps) {
        AgentPlanVO vo = new AgentPlanVO();
        vo.setId(String.valueOf(plan.getId()));
        vo.setNovelId(String.valueOf(plan.getNovelId()));
        vo.setGoal(plan.getGoal());
        vo.setStatus(plan.getStatus());
        vo.setStepTotal(plan.getStepTotal());
        vo.setStepDone(plan.getStepDone());
        vo.setPercent(plan.getPercent());
        vo.setModel(plan.getModel());
        vo.setErrorMessage(plan.getErrorMessage());
        vo.setPausedReason(plan.getPausedReason());
        vo.setCreatedAt(plan.getCreatedAt() == null ? null : plan.getCreatedAt().format(TS));
        vo.setUpdatedAt(plan.getUpdatedAt() == null ? null : plan.getUpdatedAt().format(TS));
        List<AgentPlanStepVO> stepVOs = new ArrayList<>();
        for (NovelAgentPlanStepEntity step : steps) {
            AgentPlanStepVO item = new AgentPlanStepVO();
            item.setId(String.valueOf(step.getId()));
            item.setStepNo(step.getStepNo());
            item.setTitle(step.getTitle());
            item.setDetail(step.getDetail());
            item.setStatus(step.getStatus());
            item.setReply(step.getReply());
            item.setErrorMessage(step.getErrorMessage());
            item.setOperations(readOps(step.getOpsJson()));
            stepVOs.add(item);
            if (NovelAgentPlanStepEntity.STATUS_RUNNING.equals(step.getStatus())) {
                vo.setCurrentStepNo(step.getStepNo());
                vo.setCurrentStepTitle(step.getTitle());
            }
        }
        if (vo.getCurrentStepNo() == null) {
            // 还没开跑时，把「下一个待执行」当作当前步骤，进度条才有意义
            for (NovelAgentPlanStepEntity step : steps) {
                if (NovelAgentPlanStepEntity.STATUS_PENDING.equals(step.getStatus())) {
                    vo.setCurrentStepNo(step.getStepNo());
                    vo.setCurrentStepTitle(step.getTitle());
                    break;
                }
            }
        }
        vo.setSteps(stepVOs);
        return vo;
    }

    private List<AgentOperationVO> readOps(String json) {
        if (!hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, AgentOperationVO.class));
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String writeJson(List<AgentOperationVO> operations) {
        if (operations == null || operations.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(operations);
        } catch (Exception ex) {
            return null;
        }
    }

    private String opsDigest(List<AgentOperationVO> operations) {
        if (operations == null || operations.isEmpty()) {
            return "- （没有执行任何修改）\n";
        }
        StringBuilder builder = new StringBuilder();
        for (AgentOperationVO op : operations) {
            builder.append("- ").append(op.isSuccess() ? "成功" : "失败").append(" ").append(op.getType());
            if (hasText(op.getTarget())) {
                builder.append("「").append(op.getTarget()).append("」");
            }
            if (hasText(op.getId())) {
                builder.append("(id=").append(op.getId()).append(")");
            }
            if (!op.isSuccess() && hasText(op.getMessage())) {
                builder.append("，原因：").append(op.getMessage());
            }
            builder.append("\n");
        }
        return builder.toString();
    }

    private String summaryOfTitles(List<StepSpec> specs) {
        StringBuilder builder = new StringBuilder();
        for (StepSpec spec : specs) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(spec.no()).append(". ").append(spec.title());
        }
        return builder.toString();
    }

    private Integer add(Integer left, Integer right) {
        if (right == null) {
            return left;
        }
        return left == null ? right : left + right;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 内部类型                                                           */
    /* ------------------------------------------------------------------ */

    /** 规划出来的步骤 */
    private record StepSpec(int no, String title, String detail) {
    }

    /** 单步执行结果 */
    private record StepOutcome(String reply, List<AgentOperationVO> operations, String opsJson,
                               String model, Integer promptTokens, Integer completionTokens) {
    }

    /** 运行控制：暂停 / 取消都是协作式的 */
    private static final class Control {
        private volatile boolean pauseRequested = false;
        private volatile boolean cancelled = false;
    }
}
