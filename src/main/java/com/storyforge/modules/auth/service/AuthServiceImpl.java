package com.storyforge.modules.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.security.JwtService;
import com.storyforge.common.security.TokenBlacklistService;
import com.storyforge.modules.auth.dto.ChangePasswordRequest;
import com.storyforge.modules.auth.dto.LoginRequest;
import com.storyforge.modules.auth.dto.LoginResponse;
import com.storyforge.modules.auth.dto.LogoutRequest;
import com.storyforge.modules.auth.dto.RefreshTokenRequest;
import com.storyforge.modules.user.dto.UserVO;
import com.storyforge.modules.user.entity.UserEntity;
import com.storyforge.modules.user.entity.UserStatus;
import com.storyforge.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 认证服务实现。
 *
 * <p>无 Redis 约束下的实现要点：</p>
 * <ul>
 *   <li>令牌吊销记录落 {@code t_token_blacklist}，按 {@code expires_at} 定时清理；</li>
 *   <li>账号状态每次请求从数据库校验，停用即刻生效，无需遍历令牌；</li>
 *   <li>改密后当前令牌保持有效（仅清除首登标记），避免用户被立刻踢出。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LoginResponse login(LoginRequest request, String clientIp) {
        String username = request.getUsername().trim();
        UserEntity user = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, username));

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // 不区分「账号不存在」与「口令错误」，避免账号枚举
            throw BizException.of(ErrorCode.BAD_CREDENTIALS);
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw BizException.of(ErrorCode.USER_DISABLED);
        }

        // 一条 UPDATE 完成「旧值转历史 + 写入本次」，并发登录也不会互相覆盖
        LocalDateTime loginAt = LocalDateTime.now();
        userMapper.touchLogin(user.getId(), loginAt, clientIp);
        user.setPrevLoginAt(user.getLastLoginAt());
        user.setPrevLoginIp(user.getLastLoginIp());
        user.setLastLoginAt(loginAt);
        user.setLastLoginIp(clientIp);

        log.info("[auth] login success userId={} username={} ip={}", user.getId(), user.getUsername(), clientIp);
        return issueTokens(user);
    }

    @Override
    public LoginResponse refresh(RefreshTokenRequest request) {
        JwtService.ParsedToken parsed = jwtService.parse(request.getRefreshToken(), JwtService.TYPE_REFRESH);
        if (tokenBlacklistService.isRevoked(parsed.jti())) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "刷新令牌已失效，请重新登录");
        }
        UserEntity user = userMapper.selectById(parsed.userId());
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "账号不存在或已删除");
        }
        if (user.getStatus() == UserStatus.DISABLED) {
            throw BizException.of(ErrorCode.USER_DISABLED);
        }

        // 一次性轮换：旧刷新令牌立即失效
        tokenBlacklistService.revoke(parsed.jti(), user.getId(), JwtService.TYPE_REFRESH, parsed.expiresAt());
        return issueTokens(user);
    }

    @Override
    public void logout(String accessToken, LogoutRequest request) {
        if (accessToken != null && !accessToken.isBlank()) {
            JwtService.ParsedToken access = jwtService.parse(accessToken, JwtService.TYPE_ACCESS);
            tokenBlacklistService.revoke(access.jti(), access.userId(), JwtService.TYPE_ACCESS, access.expiresAt());
        }
        if (request != null && request.getRefreshToken() != null && !request.getRefreshToken().isBlank()) {
            JwtService.ParsedToken refresh = jwtService.parse(request.getRefreshToken(), JwtService.TYPE_REFRESH);
            tokenBlacklistService.revoke(refresh.jti(), refresh.userId(), JwtService.TYPE_REFRESH, refresh.expiresAt());
        }
    }

    @Override
    public UserVO currentUser() {
        Long userId = UserContextHolder.requireUserId();
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        return UserVO.from(user);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(ChangePasswordRequest request) {
        Long userId = UserContextHolder.requireUserId();
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw BizException.of(ErrorCode.OLD_PASSWORD_MISMATCH);
        }
        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "新口令不能与原口令相同");
        }

        UserEntity update = new UserEntity();
        update.setId(user.getId());
        update.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        update.setMustChangePassword(false);
        update.setVersion(user.getVersion());
        userMapper.updateById(update);

        log.info("[auth] password changed userId={}", user.getId());
    }

    private LoginResponse issueTokens(UserEntity user) {
        LoginResponse response = new LoginResponse();
        response.setAccessToken(jwtService.createAccessToken(user.getId(), user.getUsername(), user.getRole().name()));
        response.setRefreshToken(jwtService.createRefreshToken(user.getId(), user.getUsername(), user.getRole().name()));
        response.setExpiresIn(jwtService.accessTokenTtlSeconds());
        response.setUser(UserVO.from(user));
        return response;
    }
}
