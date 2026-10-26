package com.storyforge.common.context;

import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;

/**
 * 登录用户上下文的线程持有者。
 *
 * <p>由 {@code JwtAuthenticationFilter} 写入，请求结束后清理。Service / Mapper 层通过
 * {@link #requireUserId()} 获取归属用户，用于数据隔离。</p>
 */
public final class UserContextHolder {

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static void set(UserContext context) {
        HOLDER.set(context);
    }

    public static UserContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 当前登录用户 ID，未登录返回 {@code null}（用于系统初始化、定时任务等场景）。 */
    public static Long getUserIdOrNull() {
        UserContext context = HOLDER.get();
        return context == null ? null : context.userId();
    }

    /** 当前登录用户的角色编码，未登录返回 {@code null}。 */
    public static String getRoleOrNull() {
        UserContext context = HOLDER.get();
        return context == null ? null : context.role();
    }

    /** 当前登录用户 ID，未登录直接抛 401。 */
    public static Long requireUserId() {
        Long userId = getUserIdOrNull();
        if (userId == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    public static UserContext require() {
        UserContext context = HOLDER.get();
        if (context == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return context;
    }

    public static boolean isSuperAdmin() {
        UserContext context = HOLDER.get();
        return context != null && context.isSuperAdmin();
    }
}
