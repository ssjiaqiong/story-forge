package com.storyforge.modules.user.dto;

import com.storyforge.modules.user.entity.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 超管编辑账号基本信息入参。
 */
@Data
public class UserUpdateRequest implements Serializable {

    @Schema(description = "昵称")
    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickname;

    @Schema(description = "角色：SUPER_ADMIN / USER")
    private UserRole role;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注长度不能超过 255")
    private String remark;
}
