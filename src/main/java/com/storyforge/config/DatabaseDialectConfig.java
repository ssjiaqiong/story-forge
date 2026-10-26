package com.storyforge.config;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.storyforge.infra.db.DbDialectProvider;
import com.storyforge.infra.db.type.SqliteLocalDateTypeHandler;
import com.storyforge.infra.db.type.SqliteLocalDateTimeTypeHandler;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.VendorDatabaseIdProvider;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Properties;

/**
 * 多数据库方言装配。
 *
 * <p>做两件事：</p>
 * <ol>
 *   <li>注册 {@link DatabaseIdProvider}，把 JDBC 的产品名映射成 {@code postgresql / mysql / sqlite}。
 *       Mapper XML 里的 {@code _databaseId} 就是这三个值，方言分支全部收敛在 XML 内。</li>
 *   <li>SQLite 下替换时间类型的 TypeHandler —— SQLite 没有原生时间类型，必须走文本读写。</li>
 * </ol>
 *
 * <p>PostgreSQL 与 MySQL 不需要任何额外注册：MyBatis 内置的时间处理器对两者都成立。</p>
 */
@Configuration
public class DatabaseDialectConfig {

    @Bean
    public DatabaseIdProvider databaseIdProvider() {
        VendorDatabaseIdProvider provider = new VendorDatabaseIdProvider();
        Properties properties = new Properties();
        properties.setProperty("PostgreSQL", "postgresql");
        properties.setProperty("MySQL", "mysql");
        // MariaDB 与 MySQL 的 SQL 方言一致，共用一套分支
        properties.setProperty("MariaDB", "mysql");
        properties.setProperty("SQLite", "sqlite");
        provider.setProperties(properties);
        return provider;
    }

    /**
     * SQLite 专用时间读写。用 {@link ConfigurationCustomizer} 而不是
     * {@code mybatis-plus.type-handlers-package}：后者对所有数据库全局生效，
     * 会把 PostgreSQL 下正常的 timestamp 读写也切到文本模式。
     */
    @Bean
    public ConfigurationCustomizer sqliteTypeHandlerCustomizer(DbDialectProvider dialectProvider) {
        return configuration -> {
            if (!dialectProvider.isSqlite()) {
                return;
            }
            TypeHandlerRegistry registry = configuration.getTypeHandlerRegistry();
            registry.register(LocalDateTime.class, new SqliteLocalDateTimeTypeHandler());
            registry.register(LocalDate.class, new SqliteLocalDateTypeHandler());
        };
    }
}
