package com.storyforge.modules.novel.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 智能助手多步执行任务的进度视图。
 *
 * <p>把一条用户指令的多步 Agent Loop 放到后台线程跑，前端轮询此视图实时感知中间过程：
 * 当前走到第几步、每一步改了什么（{@code operations} 汇总已执行的操作）、最终回复与结果。</p>
 */
@Data
@Schema(description = "智能助手任务进度")
public class AgentTaskVO {

    @Schema(description = "任务 id")
    private String taskId;

    @Schema(description = "状态：RUNNING / SUCCEEDED / FAILED / CANCELLED")
    private String status;

    @Schema(description = "进度百分比 0~100")
    private int percent;

    @Schema(description = "当前轮次（第几轮 AI 调用）")
    private int turnNo;

    @Schema(description = "总轮数上限")
    private int turnTotal;

    @Schema(description = "当前阶段文案，如「第 2 轮：继续执行剩余修改」")
    private String stepLabel;

    @Schema(description = "已执行的操作摘要（多轮累积，前端实时展示中间过程）")
    private List<AgentOperationVO> operations;

    @Schema(description = "最终回复（任务结束时填充）")
    private String reply;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "任务结束时返回完整结果（用户消息 / 助手消息 / 会话）")
    private AgentSendResult result;
}
