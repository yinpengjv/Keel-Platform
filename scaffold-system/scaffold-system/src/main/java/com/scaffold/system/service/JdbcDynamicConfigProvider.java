package com.scaffold.system.service;

import com.scaffold.framework.config.DynamicConfigProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * sys_config 动态参数实现：JdbcTemplate 直查（绕开 MyBatis，避免被运行日志采集器收走）
 * + 进程内缓存。
 *
 * <p>缓存策略：条目 15 秒 TTL；「参数配置」页面保存/删除参数时发布
 * {@link ConfigChangedEvent}，本类监听后立即清缓存，页面改完即生效。
 * 数据库不可用时抛出的异常由调用方（DynamicConfig）兜底回落启动默认值。</p>
 */
@Component
public class JdbcDynamicConfigProvider implements DynamicConfigProvider {

    private static final long CACHE_TTL_MILLIS = 15_000;

    private final JdbcTemplate jdbcTemplate;
    private final Map<String, CachedValue> cache = new ConcurrentHashMap<>();

    public JdbcDynamicConfigProvider(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        CachedValue cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now < cached.expireAt) {
            return cached.value;
        }
        String fresh = load(key);
        cache.put(key, new CachedValue(fresh, now + CACHE_TTL_MILLIS));
        return fresh;
    }

    /** 参数保存 / 删除后清缓存（事务提交后触发，避免清完又被未提交的旧读回填）；非事务上下文同样执行 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onConfigChanged(ConfigChangedEvent event) {
        if (event.key() == null || event.key().isBlank()) {
            cache.clear();
        } else {
            cache.remove(event.key());
        }
    }

    private String load(String key) {
        var rows = jdbcTemplate.queryForList(
                "SELECT config_value FROM sys_config WHERE config_key = ?", String.class, key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private record CachedValue(String value, long expireAt) {
    }
}
