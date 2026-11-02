package com.scaffold.framework.security;

import com.scaffold.framework.config.DynamicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.OptionalLong;

/**
 * 登录验证码挑战与失败锁定。
 *
 * <p><b>验证码窗口</b>：默认不要求验证码；某账号登录失败后进入窗口期，
 * 窗口内再次登录必须先过验证码，过期或登录成功后自动解除。
 * 窗口时长运行期可调：参数 {@code sys.login.captchaWindowSeconds}（秒），
 * 未配置时回落启动值 {@code SCAFFOLD_LOGIN_CAPTCHA_WINDOW}（默认 600）。</p>
 *
 * <p><b>失败锁定</b>：同一账号连续失败达到 {@code sys.security.maxLoginFailures}（默认 5）次
 * 即锁定 {@code sys.security.lockMinutes}（默认 15）分钟，锁定期间登录直接拒绝；
 * 0 或负数表示不启用。成功登录清零计数。失败计数窗口与锁定时长同步滑动。</p>
 *
 * <p>状态存在 {@link KeyValueStore}：本地内存实现随进程生命周期，切 Redis 时多实例共享。</p>
 */
@Service
public class LoginChallengeService {

    private static final String CHALLENGE_PREFIX = "login:challenge:";
    private static final String FAIL_PREFIX = "login:fail:";
    private static final String LOCK_PREFIX = "login:lock:";

    private final KeyValueStore keyValueStore;
    private final DynamicConfig dynamicConfig;
    private final long windowSecondsDefault;
    private final int maxFailuresDefault;
    private final int lockMinutesDefault;

    public LoginChallengeService(KeyValueStore keyValueStore,
                                 DynamicConfig dynamicConfig,
                                 @Value("${scaffold.login.captcha-window-seconds:600}") long windowSecondsDefault,
                                 @Value("${scaffold.security.max-login-failures:5}") int maxFailuresDefault,
                                 @Value("${scaffold.security.lock-minutes:15}") int lockMinutesDefault) {
        this.keyValueStore = keyValueStore;
        this.dynamicConfig = dynamicConfig;
        this.windowSecondsDefault = windowSecondsDefault;
        this.maxFailuresDefault = maxFailuresDefault;
        this.lockMinutesDefault = lockMinutesDefault;
    }

    /* ── 验证码窗口 ── */

    /** 该账号当前是否需要验证码 */
    public boolean isRequired(String username) {
        String key = keyOf(CHALLENGE_PREFIX, username);
        return key != null && keyValueStore.get(key) != null;
    }

    /** 验证码窗口时长（秒），前端提示文案用 */
    public long windowSeconds() {
        return dynamicConfig.longOf("sys.login.captchaWindowSeconds", windowSecondsDefault);
    }

    /* ── 失败锁定 ── */

    /** 锁定剩余秒数；未锁定返回 empty。锁定阈值 ≤0 表示功能关闭，恒为 empty */
    public OptionalLong lockRemainingSeconds(String username) {
        String key = keyOf(LOCK_PREFIX, username);
        if (key == null) {
            return OptionalLong.empty();
        }
        String raw = keyValueStore.get(key);
        if (raw == null) {
            return OptionalLong.empty();
        }
        try {
            long expireAt = Long.parseLong(raw);
            long remaining = expireAt - System.currentTimeMillis();
            if (remaining <= 0) {
                keyValueStore.delete(key);
                return OptionalLong.empty();
            }
            return OptionalLong.of(remaining / 1000 + 1);
        } catch (NumberFormatException exception) {
            keyValueStore.delete(key);
            return OptionalLong.empty();
        }
    }

    /**
     * 记录一次登录失败：开启（或顺延）验证码窗口，并累计失败次数。
     * 达到阈值时写入锁定到期时间并清零计数。
     *
     * @return 本次失败后账号是否被锁定
     */
    public boolean recordFailure(String username) {
        String key = keyOf(CHALLENGE_PREFIX, username);
        if (key == null) {
            return false;
        }
        long window = Math.max(1, windowSeconds());
        keyValueStore.set(key, "1", window);

        int maxFailures = dynamicConfig.intOf("sys.security.maxLoginFailures", maxFailuresDefault);
        if (maxFailures <= 0) {
            return false;
        }
        String failKey = keyOf(FAIL_PREFIX, username);
        int count = 1;
        String raw = keyValueStore.get(failKey);
        if (raw != null) {
            try {
                count = Integer.parseInt(raw) + 1;
            } catch (NumberFormatException exception) {
                count = 1;
            }
        }
        if (count >= maxFailures) {
            lock(username);
            return true;
        }
        long lockMinutes = dynamicConfig.intOf("sys.security.lockMinutes", lockMinutesDefault);
        // 失败计数与锁定时长同步滑动，长期零星失败不会累积成锁定
        keyValueStore.set(failKey, String.valueOf(count), Math.max(60, lockMinutes * 60));
        return false;
    }

    /** 登录成功：解除验证码要求并清零失败计数 */
    public void clear(String username) {
        String challenge = keyOf(CHALLENGE_PREFIX, username);
        String fail = keyOf(FAIL_PREFIX, username);
        if (challenge != null) {
            keyValueStore.delete(challenge);
        }
        if (fail != null) {
            keyValueStore.delete(fail);
        }
    }

    /** 锁定账号（登录流程在达到阈值时自动调用；也可作为管理动作预留） */
    public void lock(String username) {
        String key = keyOf(LOCK_PREFIX, username);
        if (key == null) {
            return;
        }
        int lockMinutes = dynamicConfig.intOf("sys.security.lockMinutes", lockMinutesDefault);
        if (lockMinutes <= 0) {
            lockMinutes = lockMinutesDefault;
        }
        long expireAt = System.currentTimeMillis() + lockMinutes * 60_000L;
        keyValueStore.set(key, String.valueOf(expireAt), lockMinutes * 60L);
        String failKey = keyOf(FAIL_PREFIX, username);
        if (failKey != null) {
            keyValueStore.delete(failKey);
        }
    }

    /** 解除锁定（管理员手动解锁时调用） */
    public void unlock(String username) {
        String key = keyOf(LOCK_PREFIX, username);
        if (key != null) {
            keyValueStore.delete(key);
        }
    }

    /** 当前锁定时长（分钟），供排障参考 */
    public int lockMinutes() {
        return dynamicConfig.intOf("sys.security.lockMinutes", lockMinutesDefault);
    }

    /** 失败锁定阈值（次）；≤0 表示功能关闭。用于向用户提示「连续失败 N 次将锁定 M 分钟」 */
    public int maxFailures() {
        return dynamicConfig.intOf("sys.security.maxLoginFailures", maxFailuresDefault);
    }

    /** 账号大小写不敏感；空账号不记录，避免匿名请求互相影响 */
    private String keyOf(String prefix, String username) {
        String trimmed = username == null ? "" : username.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return prefix + trimmed.toLowerCase(Locale.ROOT);
    }
}
