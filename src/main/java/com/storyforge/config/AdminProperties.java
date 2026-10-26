package com.storyforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 初始超级管理员配置，前缀 {@code storyforge.admin}。
 *
 * <p>首次启动时若账号不存在则自动创建，避免在 SQL 中硬编码口令散列。</p>
 */
@Data
@ConfigurationProperties(prefix = "storyforge.admin")
public class AdminProperties {

    private String username = "admin";

    private String password = "Admin@123456";

    private String nickname = "超级管理员";

    /** 是否要求首次登录强制改密 */
    private boolean forceChangePassword = true;
}
