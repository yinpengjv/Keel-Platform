package com.scaffold.framework.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 运行期动态参数读取入口。
 *
 * <p>参数优先级：<b>sys_config 数据库值 → 启动配置默认值</b>。
 * 页面「系统管理 → 参数配置」里修改或删除参数后，最多延迟一个缓存周期生效
 * （实现默认 15 秒；保存参数会主动发事件清缓存，通常立即生效）。</p>
 *
 * <p>消费方写法：把原来的 {@code @Value} 字段改成每次使用时调用
 * {@code dynamicConfig.longOf("sys.xxx", this.xxxDefault)}，默认值字段保留 @Value。</p>
 */
@Component
public class DynamicConfig {

    private final ObjectProvider<DynamicConfigProvider> provider;

    public DynamicConfig(ObjectProvider<DynamicConfigProvider> provider) {
        this.provider = provider;
    }

    /** 读取原始值；无 provider 或未配置时返回 null */
    public String get(String key) {
        DynamicConfigProvider impl = provider == null ? null : provider.getIfAvailable();
        if (impl == null) {
            return null;
        }
        try {
            return impl.get(key);
        } catch (Exception exception) {
            // 参数读取失败不能拖垮业务：数据库短暂不可用时回落启动默认值
            return null;
        }
    }

    /** 读取 long 值；未配置或非法时返回 fallback */
    public long longOf(String key, long fallback) {
        String raw = get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** 读取 int 值；未配置或非法时返回 fallback */
    public int intOf(String key, int fallback) {
        return (int) longOf(key, fallback);
    }
}
