package com.scaffold.framework.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 把 {@link DbLogAppender} 挂到 root logger 上。
 *
 * <p>用编程方式挂载而不是引入 {@code logback-spring.xml}：项目现有日志格式写在
 * {@code application.yml} 的 {@code logging.pattern} 里，换成 xml 会连带接管控制台
 * 输出，容易把现有格式改掉。这里只"追加"一个 appender，控制台行为完全不变。
 *
 * <p>在 {@link ApplicationReadyEvent} 挂载，此时数据源与日志系统都已就绪；
 * 启动阶段（Spring 初始化、连接池预热）的日志不会入表，仍只出现在控制台。
 */
@Component
public class RuntimeLogAppenderRegistrar {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger("scaffold.runtime.appender");

    private final RuntimeLogSink sink;
    private final boolean enabled;
    private final boolean captureSql;
    private final String sqlLoggers;
    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMillis;

    private DbLogAppender appender;

    public RuntimeLogAppenderRegistrar(
            RuntimeLogSink sink,
            @Value("${scaffold.log.runtime.enabled:true}") boolean enabled,
            @Value("${scaffold.log.runtime.capture-sql:false}") boolean captureSql,
            @Value("${scaffold.log.runtime.sql-loggers:com.scaffold.system.mapper,com.scaffold.generator.mapper}") String sqlLoggers,
            @Value("${scaffold.log.runtime.queue-capacity:2000}") int queueCapacity,
            @Value("${scaffold.log.runtime.batch-size:100}") int batchSize,
            @Value("${scaffold.log.runtime.flush-millis:500}") long flushIntervalMillis) {
        this.sink = sink;
        this.enabled = enabled;
        this.captureSql = captureSql;
        this.sqlLoggers = sqlLoggers;
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMillis = flushIntervalMillis;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void attach() {
        if (!enabled || appender != null) {
            return;
        }
        LoggerContext context = context();
        if (context == null) {
            return;
        }
        DbLogAppender created = new DbLogAppender(sink, captureSql, queueCapacity, batchSize, flushIntervalMillis);
        created.setContext(context);
        created.setName("RUNTIME_DB");
        created.start();
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(created);
        appender = created;
        // 开了 SQL 采集就把 mapper 的 logger 调到 debug，否则收不到 SQL 事件，
        // 会出现「开关打开了但页面没有 SQL」这种困惑
        if (captureSql && sqlLoggers != null && !sqlLoggers.isBlank()) {
            for (String name : sqlLoggers.split(",")) {
                String loggerName = name.trim();
                if (!loggerName.isEmpty()) {
                    context.getLogger(loggerName).setLevel(Level.DEBUG);
                }
            }
        }
        log.info("运行日志采集已启用 sql={} queue={} batch={}", captureSql, queueCapacity, batchSize);
    }

    @PreDestroy
    public synchronized void detach() {
        if (appender == null) {
            return;
        }
        LoggerContext context = context();
        if (context != null) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
        }
        appender.stop();
        appender = null;
    }

    /** 采集器是否在工作；关闭时不落库，页面据此提示 */
    public boolean active() {
        return appender != null && appender.isStarted();
    }

    public boolean captureSql() {
        return captureSql;
    }

    /** 队列满被丢弃的条数 */
    public long dropped() {
        return appender == null ? 0 : appender.droppedCount();
    }

    private LoggerContext context() {
        Object factory = LoggerFactory.getILoggerFactory();
        return factory instanceof LoggerContext ctx ? ctx : null;
    }
}
