package com.scaffold.framework.config;

/**
 * 动态参数提供者：从数据库 sys_config 读取运行期可调参数。
 *
 * <p>framework 模块不依赖具体存储，由 system 模块提供 JdbcTemplate 实现
 * （与 {@code OperationLogRecorder}、{@code RuntimeLogSink} 同一套依赖倒置模式）。
 * 实现需自带短 TTL 缓存，保证每次读取不做额外查询。</p>
 */
public interface DynamicConfigProvider {

    /**
     * 读取参数当前值。
     *
     * @param key sys_config.config_key
     * @return 配置值；未配置（或参数已被删除）时返回 null，由调用方回落到启动默认值
     */
    String get(String key);
}
