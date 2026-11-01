package com.scaffold.framework.security;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class CaptchaService {

    private static final String KEY_PREFIX = "captcha:";
    private static final long EXPIRE_SECONDS = 300;
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final KeyValueStore keyValueStore;

    public CaptchaService(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
    }

    public Map<String, String> generate() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        StringBuilder code = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            code.append(CHARS.charAt(ThreadLocalRandom.current().nextInt(CHARS.length())));
        }
        keyValueStore.set(KEY_PREFIX + uuid, code.toString(), EXPIRE_SECONDS);
        Map<String, String> result = new LinkedHashMap<>();
        result.put("uuid", uuid);
        result.put("code", code.toString());
        return result;
    }

    public boolean verify(String uuid, String code) {
        if (uuid == null || code == null) return false;
        String key = KEY_PREFIX + uuid;
        String stored = keyValueStore.get(key);
        if (stored == null) return false;
        keyValueStore.delete(key);
        return stored.equalsIgnoreCase(code);
    }
}
