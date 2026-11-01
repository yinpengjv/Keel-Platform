package com.scaffold.system.service;

import com.scaffold.common.domain.PageQuery;
import com.scaffold.common.domain.PageResult;
import com.scaffold.common.exception.BusinessException;
import com.scaffold.framework.runtime.RuntimeLogAppenderRegistrar;
import com.scaffold.framework.runtime.RuntimeLogEntry;
import com.scaffold.system.mapper.RuntimeLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行日志：查询、概览、导出与清理。
 *
 * <p>与「操作日志」「登录日志」的区别：那两张表记业务审计（谁改了什么），
 * 这里记技术诊断（哪个请求慢、哪条 SQL、哪段堆栈）。
 */
@Service
public class RuntimeLogService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int EXPORT_LIMIT = 5000;

    private final RuntimeLogMapper mapper;
    private final JdbcRuntimeLogSink sink;
    private final RuntimeLogAppenderRegistrar registrar;

    public RuntimeLogService(RuntimeLogMapper mapper, JdbcRuntimeLogSink sink, RuntimeLogAppenderRegistrar registrar) {
        this.mapper = mapper;
        this.sink = sink;
        this.registrar = registrar;
    }

    public PageResult<Map<String, Object>> logs(PageQuery query, String level, String keyword, String traceId, String range) {
        PageQuery effective = query == null ? new PageQuery(null, null, null, null) : query;
        int pageNum = effective.normalizedPageNum();
        int pageSize = effective.normalizedPageSize();
        Integer sinceHours = hoursOf(range);
        long total = mapper.countRuntimeLogs(level, keyword, traceId, sinceHours);
        List<Map<String, Object>> rows = mapper.selectRuntimeLogs(
                level, keyword, traceId, sinceHours, (pageNum - 1) * pageSize, pageSize);
        return PageResult.of(rows, total, pageNum, pageSize);
    }

    public Map<String, Object> detail(Long id) {
        Map<String, Object> row = mapper.selectRuntimeLogDetail(id);
        if (row == null) {
            throw new BusinessException("运行日志不存在");
        }
        return row;
    }

    public Map<String, Object> stats(String range) {
        Integer sinceHours = hoursOf(range);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> levels = mapper.countByLevel(sinceHours);
        long total = levels.stream().mapToLong(row -> number(row.get("total"))).sum();
        result.put("total", total);
        result.put("levels", levels);
        result.put("errors", mapper.countServerErrors(sinceHours));
        result.put("slow", mapper.countSlowRequests(sinceHours));
        result.put("topSlow", mapper.topSlowRequests(sinceHours));
        result.put("capturing", registrar.active());
        result.put("captureSql", registrar.captureSql());
        result.put("dropped", registrar.dropped());
        result.put("failedBatches", sink.failedBatches());
        result.put("retainDays", sink.retainDays());
        return result;
    }

    public String export(String level, String keyword, String traceId, String range) {
        Integer sinceHours = hoursOf(range);
        List<Map<String, Object>> rows = mapper.selectRuntimeLogs(level, keyword, traceId, sinceHours, 0, EXPORT_LIMIT);
        List<List<Object>> data = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            data.add(Arrays.asList(
                    formatTime(row.get("createTime")),
                    row.get("level"),
                    row.get("requestMethod"),
                    row.get("requestUrl"),
                    row.get("httpStatus"),
                    row.get("cost"),
                    row.get("username"),
                    row.get("ip"),
                    row.get("logger"),
                    row.get("message")
            ));
        }
        return csv(List.of("时间", "级别", "请求方法", "请求地址", "状态码", "耗时(ms)", "账号", "IP", "来源", "摘要"), data);
    }

    public void delete(Long id) {
        if (mapper.deleteRuntimeLog(id) == 0) {
            throw new BusinessException("运行日志不存在");
        }
    }

    public void clear() {
        mapper.clearRuntimeLogs();
    }

    /**
     * 手动清理 N 天前的日志；0 表示全部清空。
     *
     * <p>清理本身必须留痕：这是会删数据的动作，事后要能回答「谁在什么时候删了多少」。
     * 记录写在清理之后，所以不会被这次清理带走（清空时表里会剩下这一条）。
     */
    public int cleanup(int days) {
        int removed = days <= 0 ? mapper.clearRuntimeLogs() : mapper.deleteRuntimeLogsBefore(days);
        audit(days <= 0
                ? "清空运行日志，共删除 %d 条".formatted(removed)
                : "清理运行日志：删除 %d 天前共 %d 条".formatted(days, removed));
        return removed;
    }

    /**
     * 写一条「运维动作」记录到运行日志。
     *
     * <p>走 {@link JdbcRuntimeLogSink} 直插，不经过 MyBatis，否则这条 INSERT 会被采集器再收一次；
     * 请求本身（/api/monitor/runtime-logs/**）已被 appender 的路径过滤排除。
     */
    private void audit(String message) {
        HttpServletRequest request = currentRequest();
        sink.write(List.of(new RuntimeLogEntry(
                "INFO",
                "scaffold.audit",
                MDC.get("traceId"),
                request == null ? null : request.getMethod(),
                request == null ? null : request.getRequestURI(),
                200,
                null,
                currentUsername(),
                clientIp(request),
                message,
                null
        )));
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest();
        }
        return null;
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : String.valueOf(authentication.getPrincipal());
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp;
        }
        return request.getRemoteAddr();
    }

    /** range → 小时数；all 或未知值表示不限时间 */
    private Integer hoursOf(String range) {
        if (range == null || range.isBlank() || "all".equalsIgnoreCase(range)) {
            return null;
        }
        return switch (range) {
            case "24h" -> 24;
            case "7d" -> 24 * 7;
            case "30d" -> 24 * 30;
            default -> null;
        };
    }

    private long number(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        return 0;
    }

    private String formatTime(Object value) {
        if (value instanceof LocalDateTime time) {
            return time.format(TIME_FORMAT);
        }
        return value == null ? "" : String.valueOf(value);
    }

    private String csv(List<String> headers, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", headers)).append('\n');
        for (List<Object> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(cell(row.get(i)));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private String cell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (text.contains("\"")) {
            text = text.replace("\"", "\"\"");
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text + "\"";
        }
        return text;
    }
}
