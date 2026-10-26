package com.storyforge.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.api.R;
import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.util.WebUtils;
import com.storyforge.modules.user.entity.UserEntity;
import com.storyforge.modules.user.entity.UserStatus;
import com.storyforge.modules.user.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>职责：解析访问令牌 → 校验黑名单 → 校验账号状态 → 强制改密拦截 → 写入 SecurityContext 与
 * {@link UserContextHolder}。</p>
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 强制改密状态下仍可访问的接口 */
    private static final List<String> PASSWORD_CHANGE_ALLOWED_PATHS = List.of(
            "/api/auth/password", "/api/auth/logout", "/api/auth/me", "/api/auth/refresh");

    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = WebUtils.resolveBearerToken(request);
        if (token == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            JwtService.ParsedToken parsed = jwtService.parse(token, JwtService.TYPE_ACCESS);
            if (tokenBlacklistService.isRevoked(parsed.jti())) {
                writeError(response, ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
                return;
            }
            UserEntity user = userMapper.selectById(parsed.userId());
            if (user == null) {
                writeError(response, ErrorCode.UNAUTHORIZED, "账号不存在或已删除");
                return;
            }
            if (user.getStatus() == UserStatus.DISABLED) {
                writeError(response, ErrorCode.USER_DISABLED, ErrorCode.USER_DISABLED.getMessage());
                return;
            }
            if (Boolean.TRUE.equals(user.getMustChangePassword()) && !isPasswordChangeAllowed(request)) {
                writeError(response, ErrorCode.MUST_CHANGE_PASSWORD, ErrorCode.MUST_CHANGE_PASSWORD.getMessage());
                return;
            }

            LoginUser principal = new LoginUser(user.getId(), user.getUsername(), user.getRole());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(principal.authority())));
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            UserContextHolder.set(new UserContext(user.getId(), user.getUsername(), user.getRole().name()));

            chain.doFilter(request, response);
        } catch (BizException ex) {
            SecurityContextHolder.clearContext();
            writeError(response, ex.getErrorCode(), ex.getMessage());
        } finally {
            UserContextHolder.clear();
        }
    }

    private boolean isPasswordChangeAllowed(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return PASSWORD_CHANGE_ALLOWED_PATHS.stream().anyMatch(uri::startsWith);
    }

    private void writeError(HttpServletResponse response, ErrorCode errorCode, String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(errorCode.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(errorCode.getCode(), message)));
    }
}
