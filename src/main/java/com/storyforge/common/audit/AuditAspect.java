package com.storyforge.common.audit;

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
import java.util.Arrays;

/**
 * 审计切面：方法成功或失败后写入 {@code t_audit_log}。
 */
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditService auditService;

    @Around("@annotation(audit)")
    public Object around(ProceedingJoinPoint joinPoint, Audit audit) throws Throwable {
        String targetId = resolveTargetId(joinPoint, audit);
        String ip = currentIp();
        try {
            Object result = joinPoint.proceed();
            auditService.record(audit.action(), audit.targetType(), targetId,
                    detail(joinPoint, audit), ip, AuditService.RESULT_SUCCESS);
            return result;
        } catch (Throwable throwable) {
            auditService.record(audit.action(), audit.targetType(), targetId,
                    detail(joinPoint, audit) + " | error=" + throwable.getMessage(), ip, AuditService.RESULT_FAIL);
            throw throwable;
        }
    }

    private String resolveTargetId(ProceedingJoinPoint joinPoint, Audit audit) {
        int index = audit.targetIdIndex();
        Object[] args = joinPoint.getArgs();
        if (index < 0 || index >= args.length || args[index] == null) {
            return null;
        }
        return String.valueOf(args[index]);
    }

    private String detail(ProceedingJoinPoint joinPoint, Audit audit) {
        if (!audit.logParams()) {
            Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
            return method.getDeclaringClass().getSimpleName() + "." + method.getName();
        }
        return Arrays.toString(joinPoint.getArgs());
    }

    private String currentIp() {
        ServletRequestAttributes attributes =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        return WebUtils.clientIp(request);
    }
}
