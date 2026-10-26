package com.storyforge.common.security;

import com.storyforge.modules.user.entity.UserRole;

/**
 * Spring Security 认证主体。
 */
public record LoginUser(Long userId, String username, UserRole role) {

    public boolean isSuperAdmin() {
        return role == UserRole.SUPER_ADMIN;
    }

    public String authority() {
        return "ROLE_" + role.name();
    }
}
