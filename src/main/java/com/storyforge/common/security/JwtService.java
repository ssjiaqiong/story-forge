package com.storyforge.common.security;

import com.storyforge.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析。
 *
 * <p>Claims 约定：{@code sub=userId}、{@code uname}、{@code role}、{@code typ=access|refresh}、{@code jti}。</p>
 */
@Component
@RequiredArgsConstructor
public class JwtService {

    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties properties;

    private SecretKey secretKey;

    @PostConstruct
    void init() {
        String secret = properties.getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "storyforge.jwt.secret 未配置或长度不足 32 字节，请通过环境变量 STORYFORGE_JWT_SECRET 注入");
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String createAccessToken(Long userId, String username, String role) {
        return create(userId, username, role, TYPE_ACCESS, properties.getAccessTokenTtl());
    }

    public String createRefreshToken(Long userId, String username, String role) {
        return create(userId, username, role, TYPE_REFRESH, properties.getRefreshTokenTtl());
    }

    public long accessTokenTtlSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }

    private String create(Long userId, String username, String role, String type, java.time.Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(properties.getIssuer())
                .subject(String.valueOf(userId))
                .claim("uname", username)
                .claim("role", role)
                .claim("typ", type)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并校验令牌。
     *
     * @throws BizException 令牌无效 / 过期 / 类型不符
     */
    public ParsedToken parse(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(properties.getIssuer())
                    .build()
                    .parseSignedClaims(token);
            Claims claims = jws.getPayload();
            String type = claims.get("typ", String.class);
            if (expectedType != null && !expectedType.equals(type)) {
                throw BizException.of(ErrorCode.UNAUTHORIZED, "令牌类型不正确");
            }
            return new ParsedToken(
                    Long.valueOf(claims.getSubject()),
                    claims.get("uname", String.class),
                    claims.get("role", String.class),
                    type,
                    claims.getId(),
                    claims.getExpiration() == null ? null : claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException ex) {
            throw BizException.of(ErrorCode.UNAUTHORIZED, "登录状态已失效，请重新登录");
        }
    }

    /** 解析结果。 */
    public record ParsedToken(Long userId, String username, String role, String type, String jti, Instant expiresAt) {
    }
}
