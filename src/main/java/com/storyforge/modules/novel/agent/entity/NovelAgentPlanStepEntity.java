package com.storyforge.modules.novel.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 计划里的单步。
 *
 * <p>{@code title} 是给作者看的一句话（进度列表里显示），{@code detail} 是执行时喂给模型的
 * 细化说明——规划阶段一并写好，执行阶段就不用模型再「想一遍要做啥」，避免跑偏。</p>
 */
@Data
@TableName("t_novel_agent_plan_step")
public class NovelAgentPlanStepEntity implements Serializable {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private Long planId;

    private Integer stepNo;

    private String title;

    private String detail;

    private String status;

    /** 该步完成后模型给作者的一句话说明 */
    private String reply;

    /** 该步执行过的操作摘要（AgentOperationVO 数组 JSON） */
    private String opsJson;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @Version
    private Integer version;

    /** 已到终态，重跑时应跳过。 */
    public boolean settled() {
        return STATUS_DONE.equals(status) || STATUS_SKIPPED.equals(status);
    }
}
