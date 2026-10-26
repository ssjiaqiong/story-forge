package com.storyforge.common.security;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 令牌黑名单服务：校验 jti 是否已失效、写入失效记录、定时清理过期数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final TokenBlacklistMapper tokenBlacklistMapper;

    /** 判断令牌是否已被吊销。 */
    public boolean isRevoked(String jti) {
        if (jti == null || jti.isBlank()) {
            return false;
        }
        Long count = tokenBlacklistMapper.selectCount(
                Wrappers.<TokenBlacklistEntity>lambdaQuery().eq(TokenBlacklistEntity::getJti, jti));
        return count != null && count > 0;
    }

    /** 写入吊销记录（幂等）。 */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(String jti, Long userId, String tokenType, Instant expiresAt) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        if (isRevoked(jti)) {
            return;
        }
        TokenBlacklistEntity entity = new TokenBlacklistEntity();
        entity.setJti(jti);
        entity.setUserId(userId);
        entity.setTokenType(tokenType);
        entity.setExpiresAt(expiresAt == null
                ? LocalDateTime.now().plusDays(7)
                : LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()));
        tokenBlacklistMapper.insert(entity);
    }

    /** 每小时清理已过期记录。 */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT1M")
    public void cleanupExpired() {
        int removed = tokenBlacklistMapper.delete(
                Wrappers.<TokenBlacklistEntity>lambdaQuery()
                        .lt(TokenBlacklistEntity::getExpiresAt, LocalDateTime.now()));
        if (removed > 0) {
            log.info("[token-blacklist] cleanup expired records: {}", removed);
        }
    }
}
