package com.agentops.config;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL 驱动（pgjdbc）无法推断 {@link java.time.Instant} 参数的 SQL 类型，
 * 统一在参数绑定前转换为 {@link java.sql.Timestamp}，Repo 层可继续直接传 Instant。
 * 仅覆盖本项目用到的 varargs 重载（update/query/queryForObject/queryForList/queryForMap）。
 */
public class InstantAwareJdbcTemplate extends JdbcTemplate {

    public InstantAwareJdbcTemplate(DataSource dataSource) {
        super(dataSource);
    }

    /** 递归转换顶层参数数组中的 Instant（不处理嵌套结构，本项目无嵌套时间参数）。 */
    private static Object[] convert(Object... args) {
        if (args == null) return null;
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            out[i] = (a instanceof Instant ti) ? Timestamp.from(ti) : a;
        }
        return out;
    }

    @Override
    public int update(String sql, Object... args) throws DataAccessException {
        return super.update(sql, convert(args));
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) throws DataAccessException {
        return super.query(sql, rowMapper, convert(args));
    }

    @Override
    public <T> T query(String sql, ResultSetExtractor<T> rse, Object... args) throws DataAccessException {
        return super.query(sql, rse, convert(args));
    }

    @Override
    public <T> T queryForObject(String sql, RowMapper<T> rowMapper, Object... args) throws DataAccessException {
        return super.queryForObject(sql, rowMapper, convert(args));
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) throws DataAccessException {
        return super.queryForObject(sql, requiredType, convert(args));
    }

    @Override
    public <T> List<T> queryForList(String sql, Class<T> elementType, Object... args) throws DataAccessException {
        return super.queryForList(sql, elementType, convert(args));
    }
}
