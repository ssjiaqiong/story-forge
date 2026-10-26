package com.storyforge.modules.user.dto;

import com.storyforge.modules.user.entity.UserEntity;
import com.storyforge.modules.user.entity.UserRole;
import com.storyforge.modules.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户视图对象。不包含任何口令散列。
 */
@Data
public class UserVO implements Serializable {

    private Long id;
    private String username;
    private String nickname;
    private UserRole role;
    private UserStatus status;

    @Schema(description = "是否需在下次登录时强制修改口令")
    private Boolean mustChangePassword;

    private String remark;
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
    private LocalDateTime prevLoginAt;
    private String prevLoginIp;

    @Schema(description = "1 表示已逻辑删除（保留 30 天可恢复）")
    private Integer deleted;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserVO from(UserEntity entity) {
        if (entity == null) {
            return null;
        }
        UserVO vo = new UserVO();
        vo.setId(entity.getId());
        vo.setUsername(entity.getUsername());
        vo.setNickname(entity.getNickname());
        vo.setRole(entity.getRole());
        vo.setStatus(entity.getStatus());
        vo.setMustChangePassword(entity.getMustChangePassword());
        vo.setRemark(entity.getRemark());
        vo.setLastLoginAt(entity.getLastLoginAt());
        vo.setLastLoginIp(entity.getLastLoginIp());
        vo.setPrevLoginAt(entity.getPrevLoginAt());
        vo.setPrevLoginIp(entity.getPrevLoginIp());
        vo.setDeleted(entity.getDeleted());
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }
}
