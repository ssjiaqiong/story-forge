package com.storyforge.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录入参。
 */
@Data
public class LoginRequest implements Serializable {

    @Schema(description = "登录名", example = "admin", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "登录名不能为空")
    @Size(max = 64, message = "登录名长度不合法")
    private String username;

    @Schema(description = "口令", example = "Admin@123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "口令不能为空")
    @Size(max = 128, message = "口令长度不合法")
    private String password;
}
