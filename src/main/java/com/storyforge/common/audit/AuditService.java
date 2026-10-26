package com.storyforge.common.audit;

import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 审计落库服务。
 *
 * <p>审计失败不影响主流程，仅记录告警日志。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    public static final String RESULT_SUCCESS = "SUCCESS";
    public static final String RESULT_FAIL = "FAIL";

    private final AuditLogMapper auditLogMapper;

    public void record(String action, String targetType, String targetId, String detail, String ip, String result) {
        try {
            UserContext context = UserContextHolder.get();
            AuditLogEntity entity = new AuditLogEntity();
            entity.setUserId(context == null ? null : context.userId());
            entity.setUsername(context == null ? null : context.username());
            entity.setAction(action);
            entity.setTargetType(targetType);
            entity.setTargetId(targetId);
            entity.setDetail(truncate(detail));
            entity.setIp(ip);
            entity.setResult(result);
            entity.setCreatedAt(LocalDateTime.now());
            auditLogMapper.insert(entity);
        } catch (Exception ex) {
            log.warn("[audit] record failed, action={} target={}#{}: {}", action, targetType, targetId, ex.getMessage());
        }
    }

    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 2000 ? detail : detail.substring(0, 2000);
    }
}
