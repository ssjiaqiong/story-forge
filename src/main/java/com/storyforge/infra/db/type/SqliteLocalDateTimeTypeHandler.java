package com.storyforge.infra.db.type;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/**
 * SQLite 专用 {@link LocalDateTime} 读写：文本进、文本出。
 *
 * <p>只在 {@code sqlite} profile 下由 {@code DatabaseDialectConfig} 注册覆盖，
 * PostgreSQL / MySQL 仍使用 MyBatis 内置的时间处理器，行为不变。</p>
 */
@MappedTypes(LocalDateTime.class)
public class SqliteLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, SqliteTimeFormat.format(parameter));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return SqliteTimeFormat.parseDateTime(rs.getObject(columnName));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return SqliteTimeFormat.parseDateTime(rs.getObject(columnIndex));
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return SqliteTimeFormat.parseDateTime(cs.getObject(columnIndex));
    }
}
