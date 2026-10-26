package com.storyforge.infra.db.type;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/**
 * SQLite 专用 {@link LocalDate} 读写，配合 {@link SqliteLocalDateTimeTypeHandler} 使用。
 *
 * <p>查询条件里的日期（如超管小说列表的起止日期）会以 {@code yyyy-MM-dd} 文本与
 * {@code yyyy-MM-dd HH:mm:ss.SSS} 的时间列做字符串比较，字典序即时间序，语义正确。</p>
 */
@MappedTypes(LocalDate.class)
public class SqliteLocalDateTypeHandler extends BaseTypeHandler<LocalDate> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDate parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, SqliteTimeFormat.format(parameter));
    }

    @Override
    public LocalDate getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return SqliteTimeFormat.parseDate(rs.getObject(columnName));
    }

    @Override
    public LocalDate getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return SqliteTimeFormat.parseDate(rs.getObject(columnIndex));
    }

    @Override
    public LocalDate getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return SqliteTimeFormat.parseDate(cs.getObject(columnIndex));
    }
}
