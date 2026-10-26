package com.storyforge.modules.user.entity;

/**
 * 账号状态。
 */
public enum UserStatus {

    /** 启用 */
    ACTIVE,

    /** 停用：令牌立即失效，无法登录 */
    DISABLED;

    public static UserStatus ofNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
