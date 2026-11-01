package com.scaffold.framework.security;

import jakarta.annotation.PreDestroy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 进程内键值存储实现，用于替代本地开发时的 Redis。
 * 数据仅存在于当前 JVM，重启即失效；多实例部署时各自的令牌互不可见，
 * 因此仅适用于单机开发或演示环境。
 */
public class InMemoryKeyValueStore implements KeyValueStore {

    private static final long SWEEP_INTERVAL_SECONDS = 60;

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    private final ScheduledExecutorService sweeper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "kv-store-sweeper");
        thread.setDaemon(true);
        return thread;
    });

    public InMemoryKeyValueStore() {
        sweeper.scheduleWithFixedDelay(this::sweepExpired, SWEEP_INTERVAL_SECONDS, SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void set(String key, String value, long expireSeconds) {
        if (key == null) {
            return;
        }
        long expireAt = expireSeconds > 0
                ? System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(expireSeconds)
                : Long.MAX_VALUE;
        entries.put(key, new Entry(value, expireAt));
    }

    @Override
    public String get(String key) {
        if (key == null) {
            return null;
        }
        Entry entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired()) {
            entries.remove(key, entry);
            return null;
        }
        return entry.value();
    }

    @Override
    public void delete(String key) {
        if (key != null) {
            entries.remove(key);
        }
    }

    @Override
    public java.util.Set<String> keys(String prefix) {
        String match = prefix == null ? "" : prefix;
        java.util.Set<String> result = new java.util.HashSet<>();
        entries.forEach((key, entry) -> {
            if (!entry.isExpired() && key.startsWith(match)) {
                result.add(key);
            }
        });
        return result;
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        entries.entrySet().removeIf(item -> item.getValue().expireAt() <= now);
    }

    @PreDestroy
    public void shutdown() {
        sweeper.shutdownNow();
        entries.clear();
    }

    private record Entry(String value, long expireAt) {

        boolean isExpired() {
            return expireAt <= System.currentTimeMillis();
        }
    }
}
