package com.storyforge.modules.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;

/**
 * 修改本人登录口令入参。
 */
@Data
public class ChangePasswordRequest implements Serializable {

    @Schema(description = "当前口令（首次登录强制改密时填初始口令）", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "原口令不能为空")
    private String oldPassword;

    @Schema(description = "新口令：8~64 位且同时包含字母与数字", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "新口令不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$",
            message = "口令需 8~64 位且同时包含字母与数字")
    private String newPassword;
}
