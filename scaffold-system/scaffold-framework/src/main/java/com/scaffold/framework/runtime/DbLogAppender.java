package com.scaffold.framework.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把日志事件收进有界队列、由单独线程批量落库的 logback appender。
 *
 * <p>采集三类：访问日志（logger 固定为 {@code scaffold.access}）、WARN 及以上
 * （异常与告警）、mapper 的 DEBUG（SQL 与参数，需显式开启）。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>不阻塞业务</b>：{@code offer} 失败即丢弃并计数，绝不等待；</li>
 *   <li><b>不自激</b>：写日志表产生的 SQL、查看日志页产生的请求都过滤掉；</li>
 *   <li><b>不抛异常</b>：任何一个环节出错都只计数，日志系统本身不能拖垮应用。</li>
 * </ol>
 */
public class DbLogAppender extends AppenderBase<ILoggingEvent> {

    /** 访问日志的 logger 名，与 RequestAccessLogFilter 对应 */
    public static final String ACCESS_LOGGER = "scaffold.access";

    /** 采集器自身的日志前缀，避免 sink 报错时自我循环 */
    private static final String SELF_LOGGER_PREFIX = "scaffold.runtime";

    private static final String RUNTIME_TABLE = "sys_runtime_log";
    /** 同时匹配页面路由 /monitor/runtime-log 与接口 /api/monitor/runtime-logs：看日志这个动作本身不该产生日志 */
    private static final String RUNTIME_PATH = "/monitor/runtime-log";

    private static final int MAX_MESSAGE = 500;
    private static final int MAX_DETAIL = 4000;

    private final RuntimeLogSink sink;
    private final boolean captureSql;
    private final int queueCapacity;
    private final int batchSize;
    private final long flushIntervalMillis;

    private BlockingQueue<RuntimeLogEntry> queue;
    private Thread worker;
    private volatile boolean running;
    private final AtomicLong dropped = new AtomicLong();

    public DbLogAppender(RuntimeLogSink sink, boolean captureSql,
                         int queueCapacity, int batchSize, long flushIntervalMillis) {
        this.sink = sink;
        this.captureSql = captureSql;
        this.queueCapacity = queueCapacity;
        this.batchSize = batchSize;
        this.flushIntervalMillis = flushIntervalMillis;
    }

    @Override
    public void start() {
        queue = new ArrayBlockingQueue<>(queueCapacity);
        running = true;
        worker = new Thread(this::drain, "runtime-log-sink");
        worker.setDaemon(true);
        worker.start();
        super.start();
    }

    @Override
    public void stop() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            try {
                worker.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flushRemaining();
        super.stop();
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!running || queue == null || !accept(event)) {
            return;
        }
        RuntimeLogEntry entry;
        try {
            entry = convert(event);
        } catch (RuntimeException e) {
            return;
        }
        if (entry != null && !queue.offer(entry)) {
            dropped.incrementAndGet();
        }
    }

    /** 被丢弃的条数（队列满），页面用来判断采集是否跟得上 */
    public long droppedCount() {
        return dropped.get();
    }

    public boolean captureSql() {
        return captureSql;
    }

    private boolean accept(ILoggingEvent event) {
        String logger = event.getLoggerName();
        if (logger == null) {
            return false;
        }
        if (logger.startsWith(SELF_LOGGER_PREFIX)) {
            return false;
        }
        String message = safeMessage(event);
        if (message != null && message.contains(RUNTIME_TABLE)) {
            return false;
        }
        if (ACCESS_LOGGER.equals(logger)) {
            String uri = mdc(event, "req.uri");
            return uri == null || !uri.contains(RUNTIME_PATH);
        }
        if (event.getLevel().isGreaterOrEqual(Level.WARN)) {
            return true;
        }
        // MyBatis 的 logger 名是 statement id（com.scaffold.system.mapper.XxxMapper.selectUsers），
        // 不以 Mapper 结尾，用包路径判断才收得到
        return captureSql && event.getLevel() == Level.DEBUG && isMapperLogger(logger);
    }

    private boolean isMapperLogger(String logger) {
        return logger.endsWith("Mapper") || logger.contains(".mapper.");
    }

    private RuntimeLogEntry convert(ILoggingEvent event) {
        String full = event.getFormattedMessage();
        String detail = throwableDetail(event);
        if (detail == null && full != null && full.length() > MAX_MESSAGE) {
            detail = truncate(full, MAX_DETAIL);
        }
        return new RuntimeLogEntry(
                event.getLevel().toString(),
                truncate(event.getLoggerName(), 120),
                truncate(mdc(event, "traceId"), 40),
                truncate(mdc(event, "req.method"), 8),
                truncate(mdc(event, "req.uri"), 300),
                toInt(mdc(event, "req.status")),
                toInt(mdc(event, "req.cost")),
                truncate(mdc(event, "req.user"), 64),
                truncate(mdc(event, "req.ip"), 64),
                truncate(full, MAX_MESSAGE),
                detail
        );
    }

    private void drain() {
        List<RuntimeLogEntry> batch = new ArrayList<>(Math.max(batchSize, 1));
        while (running) {
            try {
                RuntimeLogEntry first = queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, Math.max(batchSize - 1, 0));
                write(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ignored) {
                batch.clear();
            }
        }
    }

    private void flushRemaining() {
        if (queue == null) {
            return;
        }
        List<RuntimeLogEntry> batch = new ArrayList<>();
        queue.drainTo(batch);
        if (!batch.isEmpty()) {
            write(batch);
        }
    }

    private void write(List<RuntimeLogEntry> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            sink.write(new ArrayList<>(batch));
        } catch (RuntimeException ignored) {
            // 写日志失败不能反过来影响业务，只丢弃这一批
        } finally {
            batch.clear();
        }
    }

    private String safeMessage(ILoggingEvent event) {
        try {
            return event.getFormattedMessage();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String throwableDetail(ILoggingEvent event) {
        IThrowableProxy proxy = event.getThrowableProxy();
        if (proxy == null) {
            return null;
        }
        return truncate(ThrowableProxyUtil.asString(proxy), MAX_DETAIL);
    }

    private String mdc(ILoggingEvent event, String key) {
        Map<String, String> map = event.getMDCPropertyMap();
        return map == null ? null : map.get(key);
    }

    private Integer toInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
