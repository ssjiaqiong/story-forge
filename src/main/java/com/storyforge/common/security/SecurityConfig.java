package com.storyforge.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.api.R;
import com.storyforge.config.SecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security 配置：无状态 JWT、方法级鉴权、统一 JSON 鉴权失败响应。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final SecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt cost 10
        return new BCryptPasswordEncoder(10);
    }

    /**
     * {@link JwtAuthenticationFilter} 是 Spring Bean，若不禁用自动注册，
     * 它会被 Servlet 容器再注册一次（等于每个请求执行两遍）。
     * 这里显式关闭容器注册，只保留 Spring Security 过滤链中的一次执行。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration(
            JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(registry -> registry
                        // 认证入口：登录 / 刷新令牌 / 已关闭的自助注册（需放行才能返回业务码 30030）
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/register").permitAll()
                        // 运维与文档
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 超管平台功能
                        .requestMatchers("/api/admin/**").hasRole("SUPER_ADMIN")
                        // 其余业务接口需登录
                        .requestMatchers("/api/**").authenticated()
                        // 前端静态资源与 SPA 路由
                        .anyRequest().permitAll())
                .exceptionHandling(handler -> handler
                        .authenticationEntryPoint((request, response, ex) -> writeJson(
                                response, ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.getMessage()))
                        .accessDeniedHandler((request, response, ex) -> writeJson(
                                response, ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.getMessage())))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private CorsConfigurationSource corsConfigurationSource() {
        return request -> {
            List<String> origins = securityProperties.getCorsAllowedOrigins();
            if (origins == null || origins.isEmpty()) {
                // 未配置白名单：视为同源部署，不做跨域处理
                return null;
            }
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOriginPatterns(origins);
            config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            config.setExposedHeaders(List.of("X-Trace-Id"));
            config.setAllowCredentials(true);
            config.setMaxAge(3600L);
            return config;
        };
    }

    private void writeJson(jakarta.servlet.http.HttpServletResponse response, ErrorCode errorCode, String message)
            throws java.io.IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(errorCode.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(errorCode.getCode(), message)));
    }
}
