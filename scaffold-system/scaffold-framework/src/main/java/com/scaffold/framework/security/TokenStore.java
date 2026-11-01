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

    private String key(String token) {
        return TOKEN_KEY_PREFIX + token;
    }
}
