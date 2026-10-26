package com.storyforge.modules.novel.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 发送助手消息的结果。
 */
@Data
@Schema(description = "发送助手消息结果")
public class AgentSendResult {

    private AgentMessageVO userMessage;

    private AgentMessageVO assistantMessage;

    private AgentSessionVO session;
}
