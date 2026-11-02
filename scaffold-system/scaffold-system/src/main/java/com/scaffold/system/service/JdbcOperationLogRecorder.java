package com.scaffold.system.service;

import com.scaffold.framework.audit.OperationLogEntry;
import com.scaffold.framework.audit.OperationLogRecorder;
import com.scaffold.framework.clientinfo.IpLocationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcOperationLogRecorder implements OperationLogRecorder {

    private final JdbcTemplate jdbcTemplate;
    private final IpLocationService ipLocationService;

    public JdbcOperationLogRecorder(JdbcTemplate jdbcTemplate, IpLocationService ipLocationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.ipLocationService = ipLocationService;
    }

    @Override
    public void record(OperationLogEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO sys_oper_log (module, action, request_method, request_url, request_params,
                    oper_name, dept_name, ip, location, browser, os, cost, result, error_msg)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                entry.module(),
                entry.action(),
                entry.requestMethod(),
                entry.requestUrl(),
                entry.requestParams(),
                entry.operator(),
                null,
                entry.ip(),
                ipLocationService.locate(entry.ip()),
                entry.browser(),
                entry.os(),
                entry.cost(),
                entry.result(),
                entry.errorMessage()
        );
    }
}
