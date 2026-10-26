package com.storyforge.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 登出入参。refreshToken 可选：传入时一并吊销，避免残留可用令牌。
 */
@Data
public class LogoutRequest implements Serializable {

    @Schema(description = "登录时返回的 refreshToken（可选）")
    private String refreshToken;
}
