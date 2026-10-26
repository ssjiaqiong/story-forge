package com.storyforge.common.context;

import java.util.function.Supplier;

/**
 * 用户级数据隔离的临时放行开关。
 *
 * <p>默认所有请求都按「当前登录用户」隔离业务数据。平台治理场景（如超管查看全部用户的小说）
 * 需要在**单次调用**内读取他人数据，可在此显式放行：</p>
 *
 * <pre>{@code
 * PageResult<AdminNovelVO> page = DataPermissionContext.withoutIsolation(
 *         () -> novelMapper.selectAdminNovelPage(...));
 * }</pre>
 *
 * <p>开关只作用于当前线程且自动还原，禁止在 Controller 层或过滤器里长期打开；调用方必须自行
 * 通过 {@code @PreAuthorize} 保证只有超管能走到这里。</p>
 */
public final class DataPermissionContext {

    private static final ThreadLocal<Boolean> IGNORED = new ThreadLocal<>();

    private DataPermissionContext() {
    }

    /** 当前线程是否已放行数据隔离。 */
    public static boolean isIgnored() {
        return Boolean.TRUE.equals(IGNORED.get());
    }

    /** 在放行数据隔离的上下文中执行并返回结果，执行完毕（含异常）后恢复原状态。 */
    public static <T> T withoutIsolation(Supplier<T> action) {
        Boolean previous = IGNORED.get();
        IGNORED.set(Boolean.TRUE);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                IGNORED.remove();
            } else {
                IGNORED.set(previous);
            }
        }
    }

    /** 无返回值的放行执行。 */
    public static void withoutIsolation(Runnable action) {
        withoutIsolation(() -> {
            action.run();
            return null;
        });
    }
}
