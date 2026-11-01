package com.scaffold.system.service;

import com.scaffold.framework.runtime.RuntimeLogEntry;
import com.scaffold.framework.runtime.RuntimeLogSink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Types;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 用 JdbcTemplate 直插运行日志。
 *
 * <p>不走 MyBatis 是刻意的：MyBatis 的 DEBUG 日志本身就在被采集，写日志时若再经过
 * MyBatis，会产生"记录日志的日志"。JdbcTemplate 不经过 MyBatis 的 logger，
 * 与 {@code JdbcOperationLogRecorder} 是同一套思路。
 *
 * <p>顺带做保留期限清理：每写入 {@value #PURGE_INTERVAL} 条触发一次，只删过期数据，
 * 单批上限 5000 行，避免长事务锁表。
 */
@Component
public class JdbcRuntimeLogSink implements RuntimeLogSink {

    private static final String INSERT_SQL = """
            INSERT INTO sys_runtime_log (level, logger, trace_id, request_method, request_url,
                http_status, cost, username, ip, message, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String PURGE_SQL = "DELETE FROM sys_runtime_log WHERE create_time < NOW() - INTERVAL ? DAY LIMIT 5000";

    private static final int PURGE_INTERVAL = 2000;

    private final JdbcTemplate jdbcTemplate;
    private final int retainDaysDefault;
    private final com.scaffold.framework.config.DynamicConfig dynamicConfig;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public JdbcRuntimeLogSink(JdbcTemplate jdbcTemplate,
                              @Value("${scaffold.log.runtime.retain-days:7}") int retainDays,
                              com.scaffold.framework.config.DynamicConfig dynamicConfig) {
        this.jdbcTemplate = jdbcTemplate;
        this.retainDaysDefault = retainDays;
        this.dynamicConfig = dynamicConfig;
    }

    /** 保留天数，运行期可调：参数 sys.log.runtimeRetainDays，未配置回落启动值 */
    public int retainDays() {
        return dynamicConfig.intOf("sys.log.runtimeRetainDays", retainDaysDefault);
    }

    @Override
    public void write(List<RuntimeLogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        try {
            jdbcTemplate.batchUpdate(INSERT_SQL, entries, entries.size(), (ps, entry) -> {
                ps.setString(1, entry.level());
                ps.setString(2, entry.logger());
                ps.setString(3, entry.traceId());
                ps.setString(4, entry.requestMethod());
                ps.setString(5, entry.requestUrl());
                setInt(ps, 6, entry.httpStatus());
                setInt(ps, 7, entry.cost());
                ps.setString(8, entry.username());
                ps.setString(9, entry.ip());
                ps.setString(10, entry.message());
                ps.setString(11, entry.detail());
            });
            if (retainDays() > 0 && written.addAndGet(entries.size()) >= PURGE_INTERVAL) {
                written.set(0);
                purgeExpired();
            }
        } catch (RuntimeException e) {
            failed.incrementAndGet();
            throw e;
        }
    }

    @Override
    public long failedBatches() {
        return failed.get();
    }

    /** 按保留天数删过期日志；保留天数 <= 0 表示永久保留，由调用方判断 */
    public int purgeExpired() {
        int days = retainDays();
        if (days <= 0) {
            return 0;
        }
        try {
            return jdbcTemplate.update(PURGE_SQL, days);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private void setInt(java.sql.PreparedStatement ps, int index, Integer value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }
}
