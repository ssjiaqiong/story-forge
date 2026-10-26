package com.storyforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 进程内限流配置，前缀 {@code storyforge.ratelimit}。
 */
@Data
@ConfigurationProperties(prefix = "storyforge.ratelimit")
public class RateLimitProperties {

    private boolean enabled = true;
}
