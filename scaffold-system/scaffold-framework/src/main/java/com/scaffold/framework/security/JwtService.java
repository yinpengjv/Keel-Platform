package com.scaffold.framework.security;

import com.scaffold.framework.config.DynamicConfig;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private final String secret;
    private final long expiresSecondsDefault;
    private final DynamicConfig dynamicConfig;

    public JwtService(
            @Value("${scaffold.jwt.secret}") String secret,
            @Value("${scaffold.jwt.expires-seconds}") long expiresSeconds,
            DynamicConfig dynamicConfig
    ) {
        if (secret == null || secret.isBlank() || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT_SECRET 必须配置为至少 32 字节的非空白密钥");
        }
        this.secret = secret;
        this.expiresSecondsDefault = expiresSeconds;
        this.dynamicConfig = dynamicConfig;
    }

    public String generateToken(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim("userId", userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expiresSeconds())))
                .signWith(secretKey())
                .compact();
    }

    public String parseUsername(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (JwtException | IllegalArgumentException ex) {
            log.debug("JWT解析失败: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 令牌有效期（秒）。运行期可调：参数 {@code sys.security.jwtExpiresSeconds}，
     * 未配置时回落启动值；改后只对新签发的令牌生效，存量令牌不受影响。
     */
    public long expiresSeconds() {
        return dynamicConfig.longOf("sys.security.jwtExpiresSeconds", expiresSecondsDefault);
    }

    private SecretKey secretKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
