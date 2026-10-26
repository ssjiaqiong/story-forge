package com.storyforge.common.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 进程内限流（令牌桶，不依赖 Redis）。
 *
 * <p>可重复标注，用于对同一接口叠加多档限流，例如：</p>
 * <pre>{@code
 * @RateLimit(dimension = IP,   limit = 10,  windowSeconds = 60)
 * @RateLimit(dimension = USER, limit = 120, windowSeconds = 60)
 * }</pre>
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(RateLimits.class)
public @interface RateLimit {

    /** 限流维度 */
    Dimension dimension() default Dimension.USER;

    /** 时间窗口内允许的请求数 */
    long limit() default 120;

    /** 时间窗口（秒） */
    long windowSeconds() default 60;

    /** 自定义标识；留空则按「类名.方法名」聚合 */
    String key() default "";

    /** 限流维度 */
    enum Dimension {
        /** 按客户端 IP */
        IP,
        /** 按登录用户 */
        USER,
        /** 全局（进程级） */
        GLOBAL
    }
}
