package com.scaffold.framework.runtime;

/**
 * 一条待落库的运行日志。
 *
 * <p>字段与 {@code sys_runtime_log} 一一对应；访问日志的结构化字段来自 MDC
 * （由 {@code RequestAccessLogFilter} 写入），SQL 与异常日志只有 traceId 与明细。
 */
public record RuntimeLogEntry(
        String level,
        String logger,
        String traceId,
        String requestMethod,
        String requestUrl,
        Integer httpStatus,
        Integer cost,
        String username,
        String ip,
        String message,
        String detail
) {
}
