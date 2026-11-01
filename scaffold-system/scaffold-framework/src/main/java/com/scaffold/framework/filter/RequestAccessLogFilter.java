package com.scaffold.framework.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求访问日志：每个请求结束时打一行，含方法、路径、状态码、耗时、登录用户与客户端地址。
 *
 * <p>默认开启，{@code ACCESS_LOG_ENABLED=false} 关闭；耗时超过
 * {@code ACCESS_LOG_SLOW_MS}（默认 1000ms）记 warn，状态码 ≥500 记 error。
 *
 * <p>静态资源与健康检查不记录，否则前端 chunk 与容器探针会把日志刷满。
 *
 * <p>顺序必须排在 {@link TraceIdFilter} 之内（order 更大 = 更内层），
 * 否则 TraceIdFilter 会在 finally 里先清掉 MDC，日志行上的 traceId 变成空值，
 * 也就无法把同一请求的多条 SQL 串起来。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestAccessLogFilter extends OncePerRequestFilter {

    /** 登录用户名由 JwtAuthenticationFilter 写入请求属性，避免依赖 SecurityContext 的清理时机 */
    public static final String USER_ATTRIBUTE = "scaffold.access.user";

    private static final Logger log = LoggerFactory.getLogger("scaffold.access");

    private static final String[] SKIP_PREFIXES = {"/assets/", "/api/health"};

    private static final String[] SKIP_SUFFIXES = {
            ".js", ".css", ".map", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp",
            ".woff", ".woff2", ".ttf", ".eot"
    };

    private final boolean enabled;
    private final long slowMillis;

    public RequestAccessLogFilter(
            @Value("${scaffold.log.access.enabled:true}") boolean enabled,
            @Value("${scaffold.log.access.slow-ms:1000}") long slowMillis) {
        this.enabled = enabled;
        this.slowMillis = slowMillis;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) {
            return true;
        }
        String uri = request.getRequestURI();
        for (String prefix : SKIP_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return true;
            }
        }
        for (String suffix : SKIP_SUFFIXES) {
            if (uri.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            long costMillis = (System.nanoTime() - start) / 1_000_000;
            String message = describe(request, response, costMillis);
            if (response.getStatus() >= 500) {
                log.error(message);
            } else if (costMillis >= slowMillis) {
                log.warn(message + " [慢请求]");
            } else {
                log.info(message);
            }
        }
    }

    private String describe(HttpServletRequest request, HttpServletResponse response, long costMillis) {
        StringBuilder line = new StringBuilder(96);
        line.append(request.getMethod()).append(' ').append(request.getRequestURI());
        String query = request.getQueryString();
        if (query != null && !query.isBlank()) {
            line.append('?').append(query.length() > 120 ? query.substring(0, 120) + "…" : query);
        }
        line.append(' ').append(response.getStatus()).append(' ').append(costMillis).append("ms");
        Object user = request.getAttribute(USER_ATTRIBUTE);
        line.append(" user=").append(user == null ? "-" : user);
        line.append(" ip=").append(clientIp(request));
        return line.toString();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
