package com.storyforge.modules.inspiration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 发送消息结果：同时返回刚落库的用户消息与助手消息。
 *
 * <p>AI 调用失败时接口仍返回成功，助手消息 {@code status = FAILED} 并带 {@code errorMessage}，
 * 前端渲染成错误气泡，不丢失用户输入。</p>
 */
@Data
@Schema(description = "发送消息结果")
public class SendMessageResult {

    private MessageVO userMessage;

    private MessageVO assistantMessage;

    private SessionVO session;

    @Schema(description = "AI 调用失败原因；成功时为空")
    private String errorMessage;
}
