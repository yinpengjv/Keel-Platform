package com.scaffold.framework.config;

import com.scaffold.framework.security.KeyValueStore;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Swagger 接口文档的访问闸门。
 *
 * <p>Spring Security 对非 /api 路径整体放行（静态资源需要匿名可达），
 * swagger-ui 与 api-docs 因此不经过令牌认证——本过滤器补上这道门：</p>
 *
 * <ul>
 *   <li>路径命中 /swagger-ui、/v3/api-docs、/swagger-ui.html 时才工作，其余请求直通；</li>
 *   <li>URL 带 {@code ticket} 参数：校验一次性票据（60 秒有效、用后即焚，
 *       由 {@code GET /api/auth/swagger-ticket} 签发、需 {@code system:swagger:view} 权限），
 *       通过后 {@code Set-Cookie} 下发 1 小时会话；</li>
 *   <li>无票据则要求会话 Cookie，Cookie 对应的会话必须仍存在于 KV；</li>
 *   <li>都没有 → 403。</li>
 * </ul>
 *
 * <p>Cookie 仅在本过滤器里被消费，SameSite=Lax 且场景为同源（页面直开或系统内 iframe），安全边界足够。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class SwaggerAccessFilter extends OncePerRequestFilter {

    private static final String[] PROTECTED_PREFIXES = {
            "/swagger-ui", "/v3/api-docs", "/swagger-ui.html", "/doc.html", "/webjars/"
    };

    private static final String TICKET_PREFIX = "swagger:ticket:";
    private static final String SESSION_PREFIX = "swagger:session:";
    private static final String COOKIE_NAME = "SWAGGER_TICKET";
    private static final long SESSION_TTL_SECONDS = 3600;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyValueStore keyValueStore;

    public SwaggerAccessFilter(KeyValueStore keyValueStore) {
        this.keyValueStore = keyValueStore;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        for (String prefix : PROTECTED_PREFIXES) {
            if (uri.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        // ① 一次性票据：校验通过后换会话 Cookie，后续请求凭 Cookie 直达
        String ticket = request.getParameter("ticket");
        if (ticket != null && !ticket.isBlank()
                && keyValueStore.get(TICKET_PREFIX + ticket) != null) {
            keyValueStore.delete(TICKET_PREFIX + ticket);
            byte[] raw = new byte[24];
            RANDOM.nextBytes(raw);
            String session = HexFormat.of().formatHex(raw);
            keyValueStore.set(SESSION_PREFIX + session, "1", SESSION_TTL_SECONDS);
            Cookie cookie = new Cookie(COOKIE_NAME, session);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            cookie.setMaxAge((int) SESSION_TTL_SECONDS);
            response.addCookie(cookie);
            filterChain.doFilter(request, response);
            return;
        }

        // ② 会话 Cookie
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (COOKIE_NAME.equals(cookie.getName())
                        && keyValueStore.get(SESSION_PREFIX + cookie.getValue()) != null) {
                    filterChain.doFilter(request, response);
                    return;
                }
            }
        }

        response.sendError(HttpServletResponse.SC_FORBIDDEN,
                "接口文档需要授权访问：请从系统内「系统管理 → 接口文档」进入");
    }
}
