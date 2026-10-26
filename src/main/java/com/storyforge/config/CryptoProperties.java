package com.storyforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 敏感字段加密配置，前缀 {@code storyforge.crypto}。
 */
@Data
@ConfigurationProperties(prefix = "storyforge.crypto")
public class CryptoProperties {

    /**
     * 加密主密钥（任意长度字符串，内部经 SHA-256 派生出 256 位密钥）。
     *
     * <p>生产环境必须通过环境变量 {@code STORYFORGE_SECRET_KEY} 注入。
     * <b>该密钥不可随意更换</b>：更换后已落库的密文（AI API Key 等）将无法解密。</p>
     */
    private String secret;
}
