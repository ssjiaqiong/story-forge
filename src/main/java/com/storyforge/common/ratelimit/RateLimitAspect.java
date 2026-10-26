package com.storyforge.common.ratelimit;

import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.util.WebUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

/**
 * 限流切面。读取 {@link RateLimit}（或重复标注形成的 {@link RateLimits}），逐档校验令牌桶。
 */
@Aspect
@Component
@Order(0)
@RequiredArgsConstructor
public class RateLimitAspect {

    private final RateLimiterRegistry registry;

    @Around("within(com.storyforge.modules..*)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        RateLimit[] limits = method.getAnnotationsByType(RateLimit.class);
        if (limits.length == 0) {
            limits = method.getDeclaringClass().getAnnotationsByType(RateLimit.class);
        }
        if (limits.length == 0) {
            return joinPoint.proceed();
        }
        for (RateLimit rateLimit : limits) {
            String key = buildKey(rateLimit, method, rateLimit.key());
            if (!registry.tryAcquire(key, rateLimit.limit(), rateLimit.windowSeconds())) {
                throw BizException.of(ErrorCode.RATE_LIMITED);
            }
        }
        return joinPoint.proceed();
    }

    private String buildKey(RateLimit rateLimit, Method method, String customKey) {
        String bizKey = (customKey != null && !customKey.isBlank())
                ? customKey
                : method.getDeclaringClass().getSimpleName() + "." + method.getName();
        String subject = switch (rateLimit.dimension()) {
            case IP -> "ip:" + currentIp();
            case USER -> "user:" + UserContextHolder.getUserIdOrNull();
            case GLOBAL -> "global";
        };
        return rateLimit.dimension().name() + ":" + subject + ":" + bizKey;
    }

    private String currentIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return "unknown";
        }
        HttpServletRequest request = attributes.getRequest();
        return WebUtils.clientIp(request);
    }
}
