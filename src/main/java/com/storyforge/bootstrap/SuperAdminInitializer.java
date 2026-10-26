package com.storyforge.bootstrap;

import com.storyforge.config.AdminProperties;
import com.storyforge.modules.user.entity.UserEntity;
import com.storyforge.modules.user.entity.UserRole;
import com.storyforge.modules.user.entity.UserStatus;
import com.storyforge.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 初始超级管理员种子。
 *
 * <p>账号在数据库中不存在时自动创建，口令由配置 / 环境变量提供，避免在 SQL 迁移脚本中
 * 硬编码口令散列。默认要求首次登录强制改密。</p>
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SuperAdminInitializer implements ApplicationRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdminProperties adminProperties;

    @Override
    public void run(ApplicationArguments args) {
        String username = adminProperties.getUsername();
        if (username == null || username.isBlank()) {
            log.warn("[init] storyforge.admin.username 未配置，跳过超级管理员种子创建");
            return;
        }
        UserEntity existing = userMapper.selectAnyByUsername(username);
        if (existing != null) {
            log.info("[init] 超级管理员 '{}' 已存在（deleted={}），跳过种子创建", username, existing.getDeleted());
            return;
        }

        UserEntity admin = new UserEntity();
        admin.setUsername(username.trim());
        admin.setNickname(adminProperties.getNickname());
        admin.setRole(UserRole.SUPER_ADMIN);
        admin.setStatus(UserStatus.ACTIVE);
        admin.setPasswordHash(passwordEncoder.encode(adminProperties.getPassword()));
        admin.setMustChangePassword(adminProperties.isForceChangePassword());
        admin.setRemark("系统初始化创建");
        admin.setDeleted(0);
        admin.setVersion(0);
        userMapper.insert(admin);

        log.warn("[init] 已创建超级管理员 '{}'（id={}），请登录后立即修改初始口令；" +
                        "forceChangePassword={}（true 时首个可访问接口仅限修改口令）",
                username, admin.getId(), adminProperties.isForceChangePassword());
    }
}
