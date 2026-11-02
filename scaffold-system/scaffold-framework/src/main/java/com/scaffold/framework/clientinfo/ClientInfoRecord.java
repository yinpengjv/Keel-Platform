package com.scaffold.framework.clientinfo;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端信息快照：来源 IP、归属地、浏览器、操作系统。
 *
 * <p>用于日志落库。取自 {@link ClientInfo} 与 {@link IpLocationService}，
 * 在请求线程内一次性组装好，避免在异步/切面里再去读 request。</p>
 *
 * @param ip       客户端 IP
 * @param location IP 归属地
 * @param browser  浏览器名称
 * @param os       操作系统名称
 */
public record ClientInfoRecord(String ip, String location, String browser, String os) {

    /** 未知占位，避免数据库里出现空值导致前端显示空白 */
    private static final String UNKNOWN = "未知";

    /** 空快照：没有请求上下文时使用 */
    public static ClientInfoRecord empty() {
        return new ClientInfoRecord(UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
    }

    /**
     * 从请求组装客户端信息。
     *
     * @param request  当前请求，可为 null
     * @param locator 归属地服务，可为 null（此时归属地为「未知」）
     */
    public static ClientInfoRecord of(HttpServletRequest request, IpLocationService locator) {
        if (request == null) {
            return empty();
        }
        String ip = ClientInfo.ip(request);
        String location = locator == null ? UNKNOWN : locator.locate(ip);
        return new ClientInfoRecord(ip, location, ClientInfo.browser(request), ClientInfo.os(request));
    }
}
