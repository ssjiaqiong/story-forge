package com.storyforge.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配。
 *
 * <p>顺序约定（不可颠倒）：数据权限 → 分页 → 乐观锁 → 防全表更新删除。</p>
 */
@Configuration
@RequiredArgsConstructor
public class MybatisPlusConfig {

    private final DataPermissionProperties dataPermissionProperties;

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 1) 用户级数据隔离：放在分页之前，保证 count 语句同样被改写
        interceptor.addInnerInterceptor(
                new DataPermissionInterceptor(new UserDataPermissionHandler(dataPermissionProperties.getManagedTables())));

        // 2) 分页：DbType 不写死，由 MyBatis-Plus 按 JDBC URL 自动识别，
        //    否则切换到 MySQL / SQLite 后仍会生成 PostgreSQL 的 LIMIT/OFFSET 写法
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor();
        pagination.setMaxLimit(500L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        // 3) 乐观锁
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 4) 防全表更新与删除
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());

        return interceptor;
    }

    @Bean
    public MetaObjectHandler auditMetaObjectHandler() {
        return new AuditMetaObjectHandler();
    }
}
