package com.scaffold.system.service;

/**
 * 参数配置变更事件：保存或删除 sys_config 后发布，
 * {@link JdbcDynamicConfigProvider} 监听清缓存，使页面修改立即生效。
 *
 * @param key 变更的参数键；null 表示整表变化（全部清空缓存）
 */
public record ConfigChangedEvent(String key) {
}
