package com.storyforge.common.audit;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审计日志（平台公共表，不参与 user_id 自动隔离）。
 */
@Data
@TableName("t_audit_log")
public class AuditLogEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String username;

    /** 动作编码 */
    private String action;

    /** 目标资源类型 */
    private String targetType;

    /** 目标资源 ID */
    private String targetId;

    /** 详情（脱敏后的摘要） */
    private String detail;

    private String ip;

    /** SUCCESS / FAIL */
    private String result;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
