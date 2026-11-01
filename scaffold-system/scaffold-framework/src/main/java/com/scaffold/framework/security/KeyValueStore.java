package com.scaffold.framework.security;

/**
 * 键值存储抽象。用于登录令牌与验证码这类带过期时间的短生命周期数据。
 * 默认由内存实现承载，便于本地在不部署 Redis 的情况下直接运行；
 * 将 scaffold.cache 配置为 redis 时切换为 Redis 实现。
 */
public interface KeyValueStore {

    /**
     * 写入键值并设置过期时间。
     *
     * @param key            键
     * @param value          值
     * @param expireSeconds  过期秒数，小于等于 0 表示不过期
     */
    void set(String key, String value, long expireSeconds);

    /**
     * 读取值，不存在或已过期时返回 null。
     */
    String get(String key);

    /**
     * 删除键，键不存在时不做任何处理。
     */
    void delete(String key);

    /**
     * 按前缀枚举未过期的键。
     *
     * <p>用于在线用户这类「按用户反查令牌」的场景。当前令牌量级很小
     * （单实例几百以内），全量枚举可接受；令牌规模显著增长时应改为
     * 维护 username → tokens 的反向索引。</p>
     *
     * @param prefix 键前缀，null 或空串表示枚举全部
     * @return 匹配的键集合（不含值）；不返回 null
     */
    java.util.Set<String> keys(String prefix);
}
