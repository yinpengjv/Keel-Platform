package com.scaffold.framework.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 登录验证码挑战。
 *
 * <p>默认不要求验证码：首次登录只需账号密码。一旦某个账号登录失败（账号密码错误、账号停用等），
 * 该账号进入「验证码窗口」，窗口期内再次登录必须先通过验证码；窗口过期（默认 10 分钟）或
 * 登录成功后自动解除，回到免验证码状态。</p>
 *
 * <p>窗口状态存在 {@link KeyValueStore} 里：本地内存实现随进程生命周期，切到 Redis 时多实例共享。</p>
 */
@Service
public class LoginChallengeService {

    private static final String KEY_PREFIX = "login:challenge:";

    private final KeyValueStore keyValueStore;
    private final long windowSeconds;

    public LoginChallengeService(KeyValueStore keyValueStore,
                                 @Value("${scaffold.login.captcha-window-seconds:600}") long windowSeconds) {
        this.keyValueStore = keyValueStore;
        this.windowSeconds = windowSeconds;
    }

    /** 该账号当前是否需要验证码 */
    public boolean isRequired(String username) {
        String key = keyOf(username);
        return key != null && keyValueStore.get(key) != null;
    }

    /** 记录一次登录失败：从此刻起在该账号上开启验证码窗口（重复失败会顺延窗口） */
    public void markFailure(String username) {
        String key = keyOf(username);
        if (key != null) {
            keyValueStore.set(key, "1", windowSeconds);
        }
    }

    /** 登录成功：立即解除验证码要求 */
    public void clear(String username) {
        String key = keyOf(username);
        if (key != null) {
            keyValueStore.delete(key);
        }
    }

    /** 验证码窗口时长（秒），前端用于提示文案 */
    public long windowSeconds() {
        return windowSeconds;
    }

    /** 账号大小写不敏感；空账号不记录，避免匿名请求互相影响 */
    private String keyOf(String username) {
        String trimmed = username == null ? "" : username.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return KEY_PREFIX + trimmed.toLowerCase(Locale.ROOT);
    }
}
