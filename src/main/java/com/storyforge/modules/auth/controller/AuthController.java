package com.storyforge.modules.auth.controller;

import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.common.util.WebUtils;
import com.storyforge.modules.auth.dto.ChangePasswordRequest;
import com.storyforge.modules.auth.dto.LoginRequest;
import com.storyforge.modules.auth.dto.LoginResponse;
import com.storyforge.modules.auth.dto.LogoutRequest;
import com.storyforge.modules.auth.dto.RefreshTokenRequest;
import com.storyforge.modules.auth.service.AuthService;
import com.storyforge.modules.user.dto.UserVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 *
 * <p>平台关闭自助注册：无注册入口，唯一开号路径是超管在「用户管理」中创建。</p>
 */
@Tag(name = "认证", description = "登录、令牌刷新、登出、当前用户、修改口令")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    @RateLimit(dimension = RateLimit.Dimension.IP, limit = 10, windowSeconds = 60)
    public R<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        return R.ok(authService.login(request, WebUtils.clientIp(servletRequest)));
    }

    @Operation(summary = "刷新访问令牌")
    @PostMapping("/refresh")
    public R<LoginResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return R.ok(authService.refresh(request));
    }

    @Operation(summary = "登出")
    @PostMapping("/logout")
    public R<Void> logout(@RequestBody(required = false) LogoutRequest request, HttpServletRequest servletRequest) {
        authService.logout(WebUtils.resolveBearerToken(servletRequest), request);
        return R.ok();
    }

    @Operation(summary = "当前登录用户")
    @GetMapping("/me")
    public R<UserVO> me() {
        return R.ok(authService.currentUser());
    }

    @Operation(summary = "修改本人登录口令")
    @PostMapping("/password")
    @Audit(action = "AUTH_PASSWORD_CHANGE", targetType = "USER")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 10, windowSeconds = 60)
    public R<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return R.ok();
    }

    /**
     * 自助注册：需求明确关闭。保留该端点用于对外明确返回业务码 {@code 30030}，
     * 便于前端与运维区分「功能不存在」与「功能被策略关闭」。
     */
    @Operation(summary = "自助注册（已关闭）", deprecated = true)
    @PostMapping("/register")
    public R<Void> register() {
        throw BizException.of(ErrorCode.REGISTRATION_CLOSED);
    }
}
