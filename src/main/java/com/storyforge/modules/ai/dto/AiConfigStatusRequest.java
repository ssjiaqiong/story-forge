package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 启用 / 停用 AI 接口配置。
 */
@Data
@Schema(description = "启用 / 停用")
public class AiConfigStatusRequest {

    @Schema(description = "true 启用，false 停用")
    @NotNull(message = "请指定启用状态")
    private Boolean enabled;
}
