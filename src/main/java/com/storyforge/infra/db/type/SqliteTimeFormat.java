package com.storyforge.infra.db.type;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * SQLite 文本时间的解析器。
 *
 * <p>SQLite 没有原生日期类型，xerial JDBC 驱动把时间列当字符串返回，MyBatis 内置的
 * {@code LocalDateTimeTypeHandler} 走 {@code ResultSet.getObject(col, LocalDateTime.class)}
 * 会直接抛「不支持」。因此桌面版把时间统一按文本存，读写都由本类的 TypeHandler 负责。</p>
 *
 * <p>落库格式固定为 {@link #OUTPUT}（带毫秒，字典序与时序一致，可直接用于字符串比较和
 * {@code substr(col, 1, 10)} 按天分组）；读取时宽容接受历史上可能出现的几种写法。</p>
 */
public final class SqliteTimeFormat {

    /** 落库格式：与 PostgreSQL 的 timestamp 文本表示一致，便于导出后比对。 */
    public static final DateTimeFormatter OUTPUT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** 纯日期落库格式。 */
    public static final DateTimeFormatter OUTPUT_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private static final DateTimeFormatter TIME = new DateTimeFormatterBuilder()
            .appendPattern("HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .optionalEnd()
            .toFormatter();

    private SqliteTimeFormat() {
    }

    /**
     * 解析时间文本，无法识别时返回 {@code null} 而不是抛异常。
     *
     * <p>理由：时间列出现在大量列表查询里，一条脏数据不应该让整个页面 500。
     * 真正的格式错误会在写入侧被 {@link #OUTPUT} 规整，读取侧理论上不会出现。</p>
     */
    public static LocalDateTime parseDateTime(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDateTime value) {
            return value;
        }
        if (raw instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        String text = raw.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        text = normalize(text);
        int space = text.indexOf(' ');
        if (space < 0) {
            // 只有日期：补零点
            LocalDate date = parseDate(text);
            return date == null ? null : date.atStartOfDay();
        }
        LocalDate date = parseDate(text.substring(0, space));
        if (date == null) {
            return null;
        }
        String timePart = text.substring(space + 1).trim();
        if (timePart.length() == 5) {
            timePart = timePart + ":00";
        }
        try {
            LocalTime time = LocalTime.parse(timePart, TIME);
            return LocalDateTime.of(date, time);
        } catch (RuntimeException e) {
            return date.atStartOfDay();
        }
    }

    public static LocalDate parseDate(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDate value) {
            return value;
        }
        if (raw instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        String text = normalize(raw.toString().trim());
        if (text.isEmpty()) {
            return null;
        }
        int space = text.indexOf(' ');
        if (space > 0) {
            text = text.substring(0, space);
        }
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static String format(LocalDateTime value) {
        return value == null ? null : OUTPUT.format(value);
    }

    public static String format(LocalDate value) {
        return value == null ? null : OUTPUT_DATE.format(value);
    }

    /** 去掉 ISO 的 'T' 分隔符与尾部时区标记，保留本地时间语义。 */
    private static String normalize(String text) {
        String value = text.replace('T', ' ');
        if (value.endsWith("Z")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        // 形如 2026-10-23 13:00:00+08:00 / +0800
        int offset = indexOfTimeZone(value);
        if (offset > 0) {
            value = value.substring(0, offset).trim();
        }
        return value;
    }

    private static int indexOfTimeZone(String value) {
        for (int i = 10; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '+' || (c == '-' && i > 10)) {
                return i;
            }
        }
        return -1;
    }
}
