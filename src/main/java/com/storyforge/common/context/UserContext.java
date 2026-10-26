package com.storyforge.common.context;

/**
 * 当前请求的登录用户上下文。
 *
 * @param userId   用户 ID
 * @param username 登录名
 * @param role     角色编码（{@code SUPER_ADMIN} / {@code USER}）
 */
public record UserContext(Long userId, String username, String role) {

    public boolean isSuperAdmin() {
        return "SUPER_ADMIN".equalsIgnoreCase(role);
    }
}
