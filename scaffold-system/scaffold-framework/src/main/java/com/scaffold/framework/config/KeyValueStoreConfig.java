package com.scaffold.framework.config;

import com.scaffold.framework.security.InMemoryKeyValueStore;
import com.scaffold.framework.security.KeyValueStore;
import com.scaffold.framework.security.RedisKeyValueStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 令牌与验证码存储的装配策略。
 * <p>
 * 默认使用进程内实现，无需部署 Redis 即可启动；将 scaffold.cache 设为 redis 后改用 Redis，
 * 适用于多实例部署或需要重启后保留会话的场景。
 */
@Configuration
public class KeyValueStoreConfig {

    @Bean
    @ConditionalOnProperty(name = "scaffold.cache", havingValue = "local", matchIfMissing = true)
    public KeyValueStore inMemoryKeyValueStore() {
        return new InMemoryKeyValueStore();
    }

    @Bean
    @ConditionalOnProperty(name = "scaffold.cache", havingValue = "redis")
    public KeyValueStore redisKeyValueStore(StringRedisTemplate redisTemplate) {
        return new RedisKeyValueStore(redisTemplate);
    }
}
