package com.scaffold.framework.security;

import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class TokenStore {

    private static final String TOKEN_KEY_PREFIX = "auth:token:";

    private final KeyValueStore keyValueStore;

    public TokenStore(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
    }

    public void store(String token, String username, long expiresSeconds) {
        keyValueStore.set(key(token), username, expiresSeconds);
    }

    public Optional<String> usernameOf(String token) {
        return Optional.ofNullable(keyValueStore.get(key(token)));
    }

    public void revoke(String token) {
        keyValueStore.delete(key(token));
    }

    /**
     * 全部活跃令牌，按登录名分组。
     *
     * <p>在线用户管理用：键集合来自 {@link KeyValueStore#keys(String)}，
     * 每次 O(令牌数) 枚举，量级小（几百以内）时可接受。</p>
     *
     * @return username → 该用户当前有效的 token 列表；无活跃令牌的用户不出现
     */
    public java.util.Map<String, java.util.List<String>> tokensByUsername() {
        java.util.Map<String, java.util.List<String>> grouped = new java.util.LinkedHashMap<>();
        for (String fullKey : keyValueStore.keys(TOKEN_KEY_PREFIX)) {
            String token = fullKey.substring(TOKEN_KEY_PREFIX.length());
            String username = keyValueStore.get(fullKey);
            if (username != null) {
                grouped.computeIfAbsent(username, ignored -> new java.util.ArrayList<>()).add(token);
            }
        }
        return grouped;
    }

    /**
     * 撤销某登录名的全部令牌（强制下线）。
     *
     * @return 撤销的令牌数量
     */
    public int revokeAll(String username) {
        java.util.List<String> tokens = tokensByUsername().getOrDefault(username, java.util.List.of());
        tokens.forEach(this::revoke);
        return tokens.size();
    }

    private String key(String token) {
        return TOKEN_KEY_PREFIX + token;
    }
}
