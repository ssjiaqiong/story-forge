package com.storyforge.modules.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;

/**
 * 重置口令入参（超管操作，重置后强制下次登录改密）。
 */
@Data
public class ResetPasswordRequest implements Serializable {

    @Schema(description = "新口令：8~64 位且同时包含字母与数字", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "新口令不能为空")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$",
            message = "口令需 8~64 位且同时包含字母与数字")
    private String newPassword;
}
