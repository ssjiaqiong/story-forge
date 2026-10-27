package com.storyforge.modules.autowrite.entity;

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
 * 自动写书定时任务配置：一本书一个任务（唯一约束 {@code uk_auto_write_novel(user_id, novel_id)}）。
 *
 * <p>{@code enabled} 是「定时开关」，{@code status} 是「运行态」，两者独立：
 * 任务可以 enabled=true 但 status=PAUSED（熔断或人工暂停后不再自动跑）。</p>
 */
@Data
@TableName("t_auto_write_task")
public class AutoWriteTaskEntity implements Serializable {

    /** 运行态：空闲，等待下次触发 */
    public static final String STATUS_IDLE = "IDLE";
    /** 运行态：本轮正在执行（此状态即代表「本书正在自动生成」） */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 运行态：已暂停（人工暂停 / 体检熔断 / 连续失败），需人工介入恢复 */
    public static final String STATUS_PAUSED = "PAUSED";
    /** 运行态：已停止（用户手动停止，不再自动跑） */
    public static final String STATUS_STOPPED = "STOPPED";
    /** 运行态：已完成 */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 完结口径：写到指定章数 */
    public static final String END_MODE_CHAPTERS = "CHAPTERS";
    /** 完结口径：直到写完预计字数 */
    public static final String END_MODE_UNTIL_END = "UNTIL_END";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    /** 是否启用定时：1 启用 / 0 暂停 */
    private Integer enabled;

    /** cron 表达式（Spring 6 段，秒级） */
    private String cronExpr;

    /** 每次触发生成的章节数（1~20） */
    private Integer chaptersPerRun;

    /** CHAPTERS / UNTIL_END */
    private String endMode;

    /** CHAPTERS 模式的目标章数 */
    private Integer endChapterNo;

    /** 体检硬伤（P0）熔断阈值，默认 5 */
    private Integer hardIssueLimit;

    /** 体检是否跑 AI 深检：1 是 / 0 只跑规则层 */
    private Integer consistencyAi;

    /** 每章目标字数，默认 3000 */
    private Integer targetWordsPerChapter;

    /** 运行态：IDLE / RUNNING / PAUSED / STOPPED / COMPLETED */
    private String status;

    /** UNTIL_END 模式收尾评估是否已触发（只触发一次） */
    private Integer closingTriggered;

    /** 连续失败周期数，达到 3 停止任务 */
    private Integer consecutiveFailures;

    /** 累计执行轮次 */
    private Integer totalRuns;

    /** 本任务累计生成的章节数 */
    private Integer generatedChapters;

    private LocalDateTime lastRunAt;

    private String lastResult;

    private String stoppedReason;

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

    public boolean isEnabled() {
        return enabled != null && enabled == 1;
    }

    public boolean isRunning() {
        return STATUS_RUNNING.equals(status);
    }

    public boolean isUntilEnd() {
        return END_MODE_UNTIL_END.equals(endMode);
    }

    public boolean isClosingTriggered() {
        return closingTriggered != null && closingTriggered == 1;
    }

    public boolean isConsistencyAi() {
        return consistencyAi == null || consistencyAi == 1;
    }
}
