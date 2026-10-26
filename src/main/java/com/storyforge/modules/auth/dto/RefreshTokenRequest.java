package com.storyforge.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 刷新令牌入参。
 */
@Data
public class RefreshTokenRequest implements Serializable {

    @Schema(description = "登录时返回的 refreshToken", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;
}
