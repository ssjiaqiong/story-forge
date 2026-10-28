package com.storyforge.modules.novel.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 助手任务计划视图：整体进度 + 每一步的明细。
 *
 * <p>前端用它渲染「任务卡」——目标、进度条、步骤清单（待办 / 进行中 / 已完成 / 失败），
 * 以及暂停 / 继续 / 取消按钮。</p>
 */
@Data
@Schema(description = "智能助手任务计划")
public class AgentPlanVO {

    @Schema(description = "计划 id")
    private String id;

    @Schema(description = "所属小说 id")
    private String novelId;

    @Schema(description = "用户原始指令")
    private String goal;

    @Schema(description = "PLANNING / RUNNING / PAUSED / DONE / FAILED / CANCELLED")
    private String status;

    @Schema(description = "步骤总数")
    private Integer stepTotal;

    @Schema(description = "已完成步骤数")
    private Integer stepDone;

    @Schema(description = "进度百分比")
    private Integer percent;

    @Schema(description = "当前正在执行的步骤序号，未在执行为 null")
    private Integer currentStepNo;

    @Schema(description = "当前步骤标题，配合进度展示")
    private String currentStepTitle;

    @Schema(description = "实际使用的模型")
    private String model;

    @Schema(description = "失败原因（status=FAILED 时有值）")
    private String errorMessage;

    @Schema(description = "暂停原因（status=PAUSED 时有值）")
    private String pausedReason;

    @Schema(description = "创建时间")
    private String createdAt;

    @Schema(description = "最近一次更新时间")
    private String updatedAt;

    @Schema(description = "步骤清单，按序号升序")
    private List<AgentPlanStepVO> steps;
}
