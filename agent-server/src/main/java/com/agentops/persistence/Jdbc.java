package com.agentops.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * JDBC 公共助手：统一时间/UUID 的取值方式。
 * JSON 列一律以 String 返回，由调用方通过 Json 组件解析，避免静态依赖。
 */
public final class Jdbc {

    private Jdbc() {
    }

    public static Instant instant(ResultSet rs, String col) throws SQLException {
        var ts = rs.getTimestamp(col);
        return ts == null ? null : ts.toInstant();
    }

    public static UUID uuid(ResultSet rs, String col) throws SQLException {
        return rs.getObject(col, UUID.class);
    }

    public static <T> RowMapperBridge<T> reader(RsReader<T> reader) {
        return new RowMapperBridge<>(reader);
    }

    @FunctionalInterface
    public interface RsReader<T> {
        T read(ResultSet rs) throws SQLException;
    }

    /** 薄封装，方便 lambda 中使用 Jdbc 静态助手。 */
    public record RowMapperBridge<T>(RsReader<T> fn)
            implements org.springframework.jdbc.core.RowMapper<T> {
        @Override
        public T mapRow(java.sql.ResultSet rs, int rowNum) throws SQLException {
            return fn.read(rs);
        }
    }
}
