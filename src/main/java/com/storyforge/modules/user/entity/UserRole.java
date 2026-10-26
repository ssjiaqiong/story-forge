package com.storyforge.modules.user.entity;

/**
 * 账号角色。
 *
 * <ul>
 *   <li>{@link #SUPER_ADMIN} —— 超级管理员：普通用户能力 + 用户管理 + 小说管理（跨用户只读）</li>
 *   <li>{@link #USER}        —— 普通用户：仅本空间数据</li>
 * </ul>
 */
public enum UserRole {

    /** 超级管理员 */
    SUPER_ADMIN,

    /** 普通用户 */
    USER;

    public static boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        for (UserRole role : values()) {
            if (role.name().equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    public static UserRole ofNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
