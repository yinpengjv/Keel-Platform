package com.scaffold.framework.clientinfo;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端信息工具：统一提取「来源 IP」「归属地」「浏览器」「操作系统」。
 *
 * <p>项目里此前有 3 处各自实现的 clientIp（访问日志过滤器、操作日志切面、运行日志服务），
 * 取值口径不一致。这里收敛为唯一实现，新增调用方一律走这里。</p>
 *
 * <p>IP 取值优先级：{@code X-Forwarded-For} 首项 → {@code X-Real-IP} → 远端地址。
 * 反向代理（Nginx / 网关）场景下只有走代理头才能拿到真实客户端 IP。</p>
 */
public final class ClientInfo {

    private ClientInfo() {
    }

    /**
     * 取客户端 IP。
     *
     * @param request 当前请求，可为 null
     * @return 客户端 IP；无请求时返回 {@code "未知"}
     */
    public static String ip(HttpServletRequest request) {
        if (request == null) {
            return "未知";
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "未知" : remote;
    }

    /**
     * 取 User-Agent 原始串。
     *
     * @param request 当前请求，可为 null
     * @return User-Agent；无请求时返回空串
     */
    public static String userAgent(HttpServletRequest request) {
        if (request == null) {
            return "";
        }
        String ua = request.getHeader("User-Agent");
        return ua == null ? "" : ua;
    }

    /**
     * 解析浏览器名称。
     *
     * @param request 当前请求，可为 null
     * @return 浏览器名称；无法识别返回「未知」
     */
    public static String browser(HttpServletRequest request) {
        return UserAgentParser.browser(userAgent(request));
    }

    /**
     * 解析操作系统名称。
     *
     * @param request 当前请求，可为 null
     * @return 操作系统名称；无法识别返回「未知」
     */
    public static String os(HttpServletRequest request) {
        return UserAgentParser.os(userAgent(request));
    }
}
