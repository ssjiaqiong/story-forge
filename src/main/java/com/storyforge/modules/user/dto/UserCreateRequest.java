package com.storyforge.modules.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 超管创建账号入参。
 *
 * <p>平台关闭自助注册，账号只能由此入口创建。新账号首次登录强制改密。</p>
 */
@Data
public class UserCreateRequest implements Serializable {

    @Schema(description = "登录名：3~32 位字母/数字/下划线", example = "writer01", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "登录名不能为空")
    @Pattern(regexp = "^[A-Za-z0-9_]{3,32}$", message = "登录名需为 3~32 位字母、数字或下划线")
    private String username;

    @Schema(description = "昵称", example = "一号写手")
    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickname;

    @Schema(description = "角色", example = "USER", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "角色不能为空")
    private com.storyforge.modules.user.entity.UserRole role;

    @Schema(description = "初始口令：8~64 位且同时包含字母与数字；留空则使用系统默认口令")
    @Pattern(regexp = "^$|^(?=.*[A-Za-z])(?=.*\\d)[\\x21-\\x7E]{8,64}$",
            message = "口令需 8~64 位且同时包含字母与数字")
    private String initialPassword;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;
}
