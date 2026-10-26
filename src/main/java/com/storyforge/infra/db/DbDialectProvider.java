package com.storyforge.infra.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * 当前运行的数据库方言。
 *
 * <p>在启动阶段解析一次并缓存：方言是部署期属性，运行期不会变化，没必要每次取连接。
 * 解析失败直接让容器启动失败——带着错误的方言跑起来只会产出更难排查的问题。</p>
 */
@Slf4j
@Component
public class DbDialectProvider {

    private final DbDialect dialect;

    public DbDialectProvider(DataSource dataSource) {
        this.dialect = resolve(dataSource);
        log.info("检测到数据库方言：{}（databaseId={}）", dialect, dialect.databaseId());
    }

    private DbDialect resolve(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DbDialect byProductName;
            try {
                byProductName = DbDialect.fromProductName(connection.getMetaData().getDatabaseProductName());
            } catch (SQLException e) {
                // 少数驱动不支持 getMetaData，退回 URL 判断
                byProductName = DbDialect.fromJdbcUrl(connection.getMetaData().getURL());
            }
            validate(connection, byProductName);
            return byProductName;
        } catch (SQLException e) {
            throw new IllegalStateException("连接数据库失败，无法确定数据库方言：" + e.getMessage(), e);
        }
    }

    /** 版本下限校验：低于下限的库缺少本工程依赖的 DDL 能力（函数索引 / CHECK / 部分索引）。 */
    private void validate(Connection connection, DbDialect dialect) throws SQLException {
        String version = connection.getMetaData().getDatabaseProductVersion();
        int major = parseMajor(version);
        if (major <= 0) {
            return;
        }
        switch (dialect) {
            case POSTGRESQL -> {
                if (major < 12) {
                    throw new IllegalStateException("PostgreSQL 版本过低（" + version + "），要求 12 及以上");
                }
            }
            case MYSQL -> {
                // MySQL 8.0.13 起支持函数索引（部分唯一索引的替代实现）
                String product = connection.getMetaData().getDatabaseProductName();
                boolean mariadb = product != null && product.toLowerCase().contains("mariadb");
                if (!mariadb && (major < 8 || (major == 8 && parseMinor(version) < 13))) {
                    throw new IllegalStateException("MySQL 版本过低（" + version + "），要求 8.0.13 及以上");
                }
                if (mariadb && major < 10) {
                    throw new IllegalStateException("MariaDB 版本过低（" + version + "），要求 10.2 及以上");
                }
            }
            case SQLITE -> {
                if (major < 3 || (major == 3 && parseMinor(version) < 35)) {
                    throw new IllegalStateException("SQLite 版本过低（" + version + "），要求 3.35 及以上");
                }
            }
        }
    }

    private int parseMajor(String version) {
        return parseLeadingInt(version, 0);
    }

    private int parseMinor(String version) {
        return parseLeadingInt(version, 1);
    }

    private int parseLeadingInt(String version, int index) {
        if (version == null) {
            return -1;
        }
        String[] parts = version.split("[.\\-]");
        if (parts.length <= index) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[index].replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public DbDialect current() {
        return dialect;
    }

    public boolean isPostgres() {
        return dialect == DbDialect.POSTGRESQL;
    }

    public boolean isMysql() {
        return dialect == DbDialect.MYSQL;
    }

    public boolean isSqlite() {
        return dialect == DbDialect.SQLITE;
    }
}
