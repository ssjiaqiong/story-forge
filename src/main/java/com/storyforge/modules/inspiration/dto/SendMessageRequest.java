package com.storyforge.modules.inspiration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送灵感消息请求。
 */
@Data
@Schema(description = "发送消息")
public class SendMessageRequest {

    @Schema(description = "用户消息正文", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 8000, message = "单条消息最长 8000 字")
    private String content;
}
