package com.scaffold.framework.security;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Redis 键值存储实现。仅在 scaffold.cache=redis 时装配。
 */
public class RedisKeyValueStore implements KeyValueStore {

    private final StringRedisTemplate redisTemplate;

    public RedisKeyValueStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void set(String key, String value, long expireSeconds) {
        if (expireSeconds > 0) {
            redisTemplate.opsForValue().set(key, value, expireSeconds, TimeUnit.SECONDS);
        } else {
            redisTemplate.opsForValue().set(key, value);
        }
    }

    @Override
    public String get(String key) {
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(key);
    }

    @Override
    public java.util.Set<String> keys(String prefix) {
        String pattern = (prefix == null ? "" : prefix) + "*";
        // 令牌量级小（几百以内），直接 KEYS 可接受；规模上来后应换 SCAN 游标遍历
        java.util.Set<String> found = redisTemplate.keys(pattern);
        return found == null ? java.util.Set.of() : found;
    }
}
