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
 * 自动写书执行流水：每次定时触发落一条，用于观测与排查。保留最近 50 轮。
 */
@Data
@TableName("t_auto_write_run")
public class AutoWriteRunEntity implements Serializable {

    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    /** 上一轮还没跑完，本次跳过 */
    public static final String STATUS_SKIPPED = "SKIPPED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private Long taskId;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    /** SUCCEEDED / FAILED / SKIPPED */
    private String status;

    /** 本轮写成章数 */
    private Integer chaptersWritten;

    /** 本轮补的章节大纲数 */
    private Integer outlinesCreated;

    /** 本轮补的总纲数 */
    private Integer masterOutlinesCreated;

    /** 本轮体检的 P0（硬伤）数；未体检为 null */
    private Integer consistencyHard;

    /** 摘要或失败原因 */
    private String message;

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
}
