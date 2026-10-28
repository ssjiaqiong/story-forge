package com.storyforge.modules.novel.agent.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.novel.agent.dto.AgentPlanVO;
import com.storyforge.modules.novel.agent.dto.AgentSendRequest;
import com.storyforge.modules.novel.agent.dto.AgentSendResult;
import com.storyforge.modules.novel.agent.dto.AgentSessionVO;
import com.storyforge.modules.novel.agent.dto.AgentTaskVO;
import com.storyforge.modules.novel.agent.service.NovelAgentPlanService;
import com.storyforge.modules.novel.agent.service.NovelAgentService;
import com.storyforge.modules.novel.agent.service.NovelAgentTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小说智能助手：通过聊天修改小说内容（设定 / 人物 / 物品 / 势力 / 关系 / 事件 / 章节）。
 */
@Tag(name = "小说智能助手", description = "对话式修改小说的设定与内容")
@RestController
@RequestMapping("/api/novels/{novelId}/agent")
@RequiredArgsConstructor
public class NovelAgentController {

    private final NovelAgentService agentService;
    private final NovelAgentTaskService agentTaskService;
    private final NovelAgentPlanService planService;

    @Operation(summary = "获取助手会话", description = "不存在时自动创建；返回聊天记录")
    @GetMapping("/session")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<AgentSessionVO> session(@PathVariable Long novelId) {
        return R.ok(agentService.session(novelId));
    }

    @Operation(summary = "发送助手消息", description = "携带小说状态与历史对话调用 AI，解析并执行修改操作")
    @PostMapping("/messages")
    @Audit(action = "NOVEL_AGENT_EDIT", targetType = "NOVEL")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<AgentSendResult> send(@PathVariable Long novelId, @Valid @RequestBody AgentSendRequest request) {
        return R.ok(agentService.send(novelId, request));
    }

    @Operation(summary = "启动智能助手多步任务",
            description = "把多步 Agent 循环放到后台执行，返回 taskId 供轮询进度，实时感知中间过程")
    @PostMapping("/tasks")
    @Audit(action = "NOVEL_AGENT_EDIT", targetType = "NOVEL")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 30, windowSeconds = 60)
    public R<AgentTaskVO> startTask(@PathVariable Long novelId, @Valid @RequestBody AgentSendRequest request) {
        return R.ok(agentTaskService.start(novelId, request));
    }

    @Operation(summary = "查询智能助手任务进度", description = "返回当前轮次、已执行操作与最终结果")
    @GetMapping("/tasks/{taskId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<AgentTaskVO> taskStatus(@PathVariable Long novelId, @PathVariable String taskId) {
        return R.ok(agentTaskService.status(taskId, UserContextHolder.requireUserId()));
    }

    @Operation(summary = "查询正在执行的助手任务", description = "刷新后恢复轮询用；无进行中任务时 data 为 null")
    @GetMapping("/tasks/running")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<AgentTaskVO> runningTask(@PathVariable Long novelId) {
        return R.ok(agentTaskService.findRunning(novelId, UserContextHolder.requireUserId()));
    }

    @Operation(summary = "停止智能助手任务", description = "取消正在执行的多步任务，返回取消后的状态")
    @DeleteMapping("/tasks/{taskId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<AgentTaskVO> cancelTask(@PathVariable Long novelId, @PathVariable String taskId) {
        return R.ok(agentTaskService.cancel(taskId, UserContextHolder.requireUserId()));
    }

    @Operation(summary = "清空助手会话", description = "删除全部聊天记录，保留会话本身")
    @DeleteMapping("/session")
    @Audit(action = "NOVEL_AGENT_CLEAR", targetType = "NOVEL")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 60, windowSeconds = 60)
    public R<Void> clear(@PathVariable Long novelId) {
        agentService.clearSession(novelId);
        return R.ok();
    }

    /* ---------------- 任务计划：规划 → 连续执行 → 进度跟踪 ---------------- */

    @Operation(summary = "启动助手任务计划",
            description = "先把指令拆成步骤（规划），再在后台逐步执行到完成；不需要用户反复说「继续」")
    @PostMapping("/plans")
    @Audit(action = "NOVEL_AGENT_PLAN", targetType = "NOVEL")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 20, windowSeconds = 60)
    public R<AgentPlanVO> startPlan(@PathVariable Long novelId, @Valid @RequestBody AgentSendRequest request) {
        return R.ok(planService.start(novelId, request));
    }

    @Operation(summary = "查询助手任务计划", description = "整体进度 + 每一步的状态与执行结果")
    @GetMapping("/plans/{planId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 900, windowSeconds = 60)
    public R<AgentPlanVO> planStatus(@PathVariable Long novelId, @PathVariable Long planId) {
        return R.ok(planService.status(planId));
    }

    @Operation(summary = "查询本书最近一次任务计划", description = "打开面板时恢复任务卡；没有则 data 为 null")
    @GetMapping("/plans/latest")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<AgentPlanVO> latestPlan(@PathVariable Long novelId) {
        return R.ok(planService.latest(novelId));
    }

    @Operation(summary = "暂停助手任务", description = "当前步做完后停下，可再点继续")
    @PostMapping("/plans/{planId}/pause")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<AgentPlanVO> pausePlan(@PathVariable Long novelId, @PathVariable Long planId) {
        return R.ok(planService.pause(novelId, planId));
    }

    @Operation(summary = "继续助手任务", description = "从断点接着跑，不重新规划")
    @PostMapping("/plans/{planId}/resume")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<AgentPlanVO> resumePlan(@PathVariable Long novelId, @PathVariable Long planId) {
        return R.ok(planService.resume(novelId, planId));
    }

    @Operation(summary = "取消助手任务", description = "立刻停手；已做的修改不回滚")
    @DeleteMapping("/plans/{planId}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<AgentPlanVO> cancelPlan(@PathVariable Long novelId, @PathVariable Long planId) {
        return R.ok(planService.cancel(novelId, planId));
    }
}
