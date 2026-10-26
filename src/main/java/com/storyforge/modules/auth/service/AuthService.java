package com.storyforge.modules.auth.service;

import com.storyforge.modules.auth.dto.ChangePasswordRequest;
import com.storyforge.modules.auth.dto.LoginRequest;
import com.storyforge.modules.auth.dto.LoginResponse;
import com.storyforge.modules.auth.dto.LogoutRequest;
import com.storyforge.modules.auth.dto.RefreshTokenRequest;
import com.storyforge.modules.user.dto.UserVO;

/**
 * 认证服务。
 */
public interface AuthService {

    /** 账密登录（平台关闭自助注册，账号由超管创建）。 */
    LoginResponse login(LoginRequest request, String clientIp);

    /** 刷新令牌（refreshToken 一次性轮换）。 */
    LoginResponse refresh(RefreshTokenRequest request);

    /** 登出：吊销当前访问令牌与传入的刷新令牌。 */
    void logout(String accessToken, LogoutRequest request);

    /** 当前登录用户信息。 */
    UserVO currentUser();

    /** 修改本人登录口令（同时清除首登强制改密标记）。 */
    void changePassword(ChangePasswordRequest request);
}
