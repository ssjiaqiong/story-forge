package com.storyforge.modules.user.dto;

import com.storyforge.modules.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 启用 / 停用账号入参。
 */
@Data
public class UserStatusRequest implements Serializable {

    @Schema(description = "目标状态", example = "DISABLED", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "状态不能为空")
    private UserStatus status;
}
