package com.storyforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户级数据隔离配置，前缀 {@code storyforge.data-permission}。
 */
@Data
@ConfigurationProperties(prefix = "storyforge.data-permission")
public class DataPermissionProperties {

    /**
     * 需要自动追加 {@code user_id = ?} 的业务表白名单。
     *
     * <p>平台公共表（{@code t_user} / {@code t_audit_log} / {@code t_token_blacklist}）不纳入。
     * 后续新增业务模块（小说、章节、人物…）时在此登记表名即可自动获得隔离能力。</p>
     */
    private List<String> managedTables = new ArrayList<>();
}
