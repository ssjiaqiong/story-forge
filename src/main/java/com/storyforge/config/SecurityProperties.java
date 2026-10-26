package com.storyforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全与账号策略，前缀 {@code storyforge.security}。
 */
@Data
@ConfigurationProperties(prefix = "storyforge.security")
public class SecurityProperties {

    /** 自助注册开关。需求约束：固定关闭，账号仅能由超级管理员创建 */
    private boolean registrationEnabled = false;

    /** 跨域白名单（仅本地前后端分离调试需要；同源部署留空即可） */
    private List<String> corsAllowedOrigins = new ArrayList<>();

    /** 超管建号时未指定初始口令所使用的默认口令（新账号首登强制改密） */
    private String defaultInitialPassword = "Story@123456";
}
