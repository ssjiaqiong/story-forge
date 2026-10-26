package com.storyforge.infra.db;

import java.util.Locale;

/**
 * 支持的数据库方言。
 *
 * <p>本工程不绑定单一数据库：同一套业务代码要能在 PostgreSQL（服务端默认）、
 * MySQL（含 MariaDB）与 SQLite（桌面单机版）上运行。三者的差异被收敛到两处：</p>
 *
 * <ol>
 *   <li><b>DDL</b>：{@code db/migration/{postgresql,mysql,sqlite}/} 各一份基线脚本，
 *       由 {@code spring.flyway.locations} 按 profile 选择，代码不参与。</li>
 *   <li><b>DML</b>：Mapper XML 里凡是用到方言语法的地方，一律写成
 *       {@code <if test="_databaseId == 'xxx'">} 分支，值为本枚举的 {@link #databaseId()}。</li>
 * </ol>
 *
 * <p>之所以用 MyBatis 的 {@code _databaseId} 而不是在 Java 里拼字符串：SQL 仍然完整地写在
 * XML 中，可以被静态检查、可以被日志完整打印，也避免了运行期字符串拼接的注入面。</p>
 */
public enum DbDialect {

    /** PostgreSQL 12+，服务端部署的默认方言。 */
    POSTGRESQL("postgresql"),

    /** MySQL 8.0.13+ / MariaDB 10.2+，函数索引与 CHECK 约束是硬性下限。 */
    MYSQL("mysql"),

    /** SQLite 3.35+，桌面单机版（见 docs/05 方案）。 */
    SQLITE("sqlite");

    /** 与 MyBatis {@code databaseId} 一致的小写标识，供 XML 的 {@code _databaseId} 比较。 */
    private final String databaseId;

    DbDialect(String databaseId) {
        this.databaseId = databaseId;
    }

    public String databaseId() {
        return databaseId;
    }

    /**
     * 按 JDBC 驱动的 {@code DatabaseMetaData.getDatabaseProductName()} 识别方言。
     *
     * <p>识别不到时抛异常而不是回退到 PostgreSQL：静默回退会让一套方言的 SQL 打到
     * 另一种库上，故障点会漂移到很远的业务逻辑里。</p>
     */
    public static DbDialect fromProductName(String productName) {
        if (productName == null || productName.isBlank()) {
            throw new IllegalStateException("无法识别数据库类型：DatabaseMetaData 未返回产品名");
        }
        String name = productName.toLowerCase(Locale.ROOT);
        if (name.contains("postgres")) {
            return POSTGRESQL;
        }
        if (name.contains("mysql") || name.contains("mariadb")) {
            return MYSQL;
        }
        if (name.contains("sqlite")) {
            return SQLITE;
        }
        throw new IllegalStateException("暂不支持的数据库类型：" + productName
                + "（仅支持 PostgreSQL / MySQL / SQLite）");
    }

    /** 按 JDBC URL 前缀识别，用于日志与早期校验（连接建立前的快速判断）。 */
    public static DbDialect fromJdbcUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("无法识别数据库类型：JDBC URL 为空");
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:postgresql:")) {
            return POSTGRESQL;
        }
        if (lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:")) {
            return MYSQL;
        }
        if (lower.startsWith("jdbc:sqlite:")) {
            return SQLITE;
        }
        throw new IllegalStateException("暂不支持的 JDBC URL：" + url);
    }
}
