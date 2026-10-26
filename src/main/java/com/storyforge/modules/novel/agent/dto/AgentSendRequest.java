package com.storyforge.modules.novel.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 向小说智能助手发送一条消息。
 */
@Data
@Schema(description = "发送助手消息")
public class AgentSendRequest {

    @Schema(description = "用户指令，如「世界观里主角的能力设定前后矛盾，帮我改一下」")
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 8000, message = "单条消息最长 8000 字")
    private String content;
}
