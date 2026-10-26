package com.storyforge.modules.user.dto;

import com.storyforge.common.api.PageQuery;
import com.storyforge.modules.user.entity.UserRole;
import com.storyforge.modules.user.entity.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 超管用户列表查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PageQuery {

    @Schema(description = "关键字：登录名或昵称模糊匹配")
    private String keyword;

    @Schema(description = "角色筛选：SUPER_ADMIN / USER")
    private UserRole role;

    @Schema(description = "状态筛选：ACTIVE / DISABLED")
    private UserStatus status;

    @Schema(description = "是否包含已删除账号（用于恢复）")
    private Boolean includeDeleted = Boolean.FALSE;
}
