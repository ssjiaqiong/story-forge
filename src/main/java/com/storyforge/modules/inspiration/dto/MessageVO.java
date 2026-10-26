package com.storyforge.modules.inspiration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 灵感会话消息视图。
 */
@Data
@Schema(description = "灵感会话消息")
public class MessageVO {

    private Long id;

    @Schema(description = "USER / ASSISTANT / SYSTEM")
    private String role;

    private String content;

    @Schema(description = "OK / FAILED")
    private String status;

    @Schema(description = "失败原因，仅 FAILED 时有值")
    private String errorMessage;

    private String model;

    private LocalDateTime createdAt;
}
