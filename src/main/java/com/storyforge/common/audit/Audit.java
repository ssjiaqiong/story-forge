package com.storyforge.common.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计日志注解。标注在关键写操作与超管平台接口上。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    /** 动作编码，如 USER_CREATE / USER_DISABLE */
    String action();

    /** 目标资源类型，如 USER / NOVEL */
    String targetType() default "";

    /** 目标 ID 在方法参数中的下标（-1 表示不采集） */
    int targetIdIndex() default -1;

    /** 是否记录方法入参（敏感入参请保持 false） */
    boolean logParams() default false;
}
