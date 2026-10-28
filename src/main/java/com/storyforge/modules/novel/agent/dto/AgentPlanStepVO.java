package com.storyforge.modules.novel.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 计划里的单步视图。
 */
@Data
@Schema(description = "智能助手任务的单个步骤")
public class AgentPlanStepVO {

    @Schema(description = "步骤 id")
    private String id;

    @Schema(description = "步骤序号，从 1 开始")
    private Integer stepNo;

    @Schema(description = "步骤标题（给作者看的一句话）")
    private String title;

    @Schema(description = "细化说明（执行时喂给模型）")
    private String detail;

    @Schema(description = "PENDING / RUNNING / DONE / FAILED / SKIPPED")
    private String status;

    @Schema(description = "该步完成后模型的说明")
    private String reply;

    @Schema(description = "该步执行过的操作摘要")
    private List<AgentOperationVO> operations;

    @Schema(description = "失败原因")
    private String errorMessage;
}
