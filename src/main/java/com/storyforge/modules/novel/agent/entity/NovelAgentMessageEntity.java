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
 * 小说智能助手会话消息。
 *
 * <p>按 id 升序即时间序；{@code status = FAILED} 的助手消息不参与上下文组装。
 * {@code operations} 存助手消息执行的操作摘要 JSON 数组（如
 * {@code [{"type":"UPDATE_CHARACTER","target":"沈默","success":true}]}）。</p>
 */
@Data
@TableName("t_novel_agent_message")
public class NovelAgentMessageEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private Long sessionId;

    /** USER / ASSISTANT / SYSTEM */
    private String role;

    private String content;

    /** 助手消息执行的操作摘要 JSON 数组，用户消息为空 */
    private String operations;

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
