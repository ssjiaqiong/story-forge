package com.storyforge.modules.inspiration.entity;

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
 * 灵感会话消息。
 *
 * <p>按 id 升序即时间序；{@code status = FAILED} 的助手消息不参与上下文组装。</p>
 */
@Data
@TableName("t_inspiration_message")
public class InspirationMessageEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long sessionId;

    /** USER / ASSISTANT / SYSTEM */
    private String role;

    private String content;

    /** 服务端实际使用的模型 */
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    /** OK / FAILED */
    private String status;

    private String errorMessage;

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
