package com.storyforge.modules.ai.service;

import com.storyforge.modules.ai.entity.AiUsageEntity;
import com.storyforge.modules.ai.mapper.AiUsageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用量落库器。
 *
 * <p>单独成 Bean 是为了让 {@link Propagation#REQUIRES_NEW} 真正生效：
 * 用量行必须独立提交——业务事务回滚时 token 已经真实消耗掉了，
 * 统计不能跟着一起回滚。</p>
 */
@Component
@RequiredArgsConstructor
public class AiUsageRecorder {

    private final AiUsageMapper aiUsageMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void save(AiUsageEntity entity) {
        aiUsageMapper.insert(entity);
    }
}
