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
 * 智能助手任务计划：一条复杂指令对应一份步骤清单与整体进度。
 *
 * <p>和 {@code NovelAgentTaskService} 里的内存任务不同，这里落库——助手一次指令可能跑十几
 * 分钟、几十步，进程重启或用户刷新后都要能看到「做到第几步、还剩什么」，内存态做不到。</p>
 */
@Data
@TableName("t_novel_agent_plan")
public class NovelAgentPlanEntity implements Serializable {

    /** 规划中：模型正在拆步骤 */
    public static final String STATUS_PLANNING = "PLANNING";
    /** 执行中：逐步推进，不需要用户干预 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 暂停：等人工确认（连续失败 / 用户主动暂停） */
    public static final String STATUS_PAUSED = "PAUSED";
    /** 全部步骤完成 */
    public static final String STATUS_DONE = "DONE";
    /** 规划失败或首步即失败 */
    public static final String STATUS_FAILED = "FAILED";
    /** 用户取消 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private Long sessionId;

    /** 用户原始指令，每一步都围绕它执行 */
    private String goal;

    private String status;

    private Integer stepTotal;

    private Integer stepDone;

    private Integer percent;

    private String model;

    private String errorMessage;

    /** 暂停原因，例如「连续 2 步失败」 */
    private String pausedReason;

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

    /** 是否还能继续跑（暂停的计划可以直接续跑）。 */
    public boolean resumable() {
        return STATUS_PAUSED.equals(status) || STATUS_FAILED.equals(status);
    }

    /** 是否已经停下来（终态）。 */
    public boolean finished() {
        return STATUS_DONE.equals(status) || STATUS_CANCELLED.equals(status);
    }
}
