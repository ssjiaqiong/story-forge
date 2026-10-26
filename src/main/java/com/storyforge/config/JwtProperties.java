package com.storyforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 配置，前缀 {@code storyforge.jwt}。
 */
@Data
@ConfigurationProperties(prefix = "storyforge.jwt")
public class JwtProperties {

    /** 签发者 */
    private String issuer = "storyforge";

    /** HMAC 密钥，长度必须 ≥ 32 字节（256 bit），生产环境通过环境变量注入 */
    private String secret;

    /** 访问令牌有效期 */
    private Duration accessTokenTtl = Duration.ofMinutes(30);

    /** 刷新令牌有效期 */
    private Duration refreshTokenTtl = Duration.ofDays(7);
}
