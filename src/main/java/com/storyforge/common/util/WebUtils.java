package com.storyforge.common.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端信息工具。
 */
public final class WebUtils {

    private static final String UNKNOWN = "unknown";

    private WebUtils() {
    }

    /** 获取客户端 IP（兼容反向代理常见头）。 */
    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String[] headers = {"X-Forwarded-For", "X-Real-IP", "Proxy-Client-IP", "WL-Proxy-Client-IP"};
        for (String header : headers) {
            String value = request.getHeader(header);
            if (value != null && !value.isBlank() && !UNKNOWN.equalsIgnoreCase(value)) {
                int comma = value.indexOf(',');
                return (comma > 0 ? value.substring(0, comma) : value).trim();
            }
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? UNKNOWN : remote;
    }

    /** 从 {@code Authorization: Bearer xxx} 中提取令牌。 */
    public static String resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (header.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String token = header.substring(prefix.length()).trim();
            return token.isEmpty() ? null : token;
        }
        return null;
    }
}
