package com.scaffold.framework.clientinfo;

/**
 * User-Agent 解析：把浏览器标识串解析成「浏览器」与「操作系统」两个可读名称。
 *
 * <p>不引第三方库，纯字符串匹配。识别顺序按「越具体越靠前」排列，
 * 避免 Edge 被当成 Chrome、微信内置浏览器被当成 Safari 之类的误判。</p>
 *
 * <p>无法识别时返回 {@code "未知"}，不返回 null，便于直接落库展示。</p>
 */
public final class UserAgentParser {

    /** 无法识别时的占位文本 */
    public static final String UNKNOWN = "未知";

    private UserAgentParser() {
    }

    /**
     * 解析浏览器名称。
     *
     * @param userAgent User-Agent 请求头，可为 null
     * @return 浏览器名称，如 Chrome / Edge / Safari / 微信；无法识别返回 {@link #UNKNOWN}
     */
    public static String browser(String userAgent) {
        if (isBlank(userAgent)) {
            return UNKNOWN;
        }
        String ua = userAgent.toLowerCase();
        // 微信 / 企业微信 / 钉钉等内置浏览器要排在 Chrome 之前判断
        if (ua.contains("micromessenger")) {
            return "微信";
        }
        if (ua.contains("wxwork")) {
            return "企业微信";
        }
        if (ua.contains("dingtalk")) {
            return "钉钉";
        }
        if (ua.contains("alipayclient")) {
            return "支付宝";
        }
        if (ua.contains("qq/")) {
            return "QQ 浏览器";
        }
        if (ua.contains("mqqbrowser")) {
            return "QQ 浏览器";
        }
        // Chromium 系：Edge / Opera / Vivaldi 都要排在 Chrome 之前
        if (ua.contains("edg/") || ua.contains("edge/") || ua.contains("edga/") || ua.contains("edgios/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("vivaldi")) {
            return "Vivaldi";
        }
        if (ua.contains("firefox/") || ua.contains("fxios/")) {
            return "Firefox";
        }
        if (ua.contains("chrome/") || ua.contains("crios/")) {
            return "Chrome";
        }
        if (ua.contains("safari/") && ua.contains("version/")) {
            return "Safari";
        }
        if (ua.contains("safari/")) {
            return "Safari";
        }
        if (ua.contains("msie") || ua.contains("trident/")) {
            return "IE";
        }
        if (ua.contains("curl/")) {
            return "curl";
        }
        if (ua.contains("postman")) {
            return "Postman";
        }
        if (ua.contains("python-requests") || ua.contains("python-urllib")) {
            return "Python HTTP";
        }
        return UNKNOWN;
    }

    /**
     * 解析操作系统名称。
     *
     * @param userAgent User-Agent 请求头，可为 null
     * @return 操作系统名称，如 Windows 10 / macOS / Android / Linux；无法识别返回 {@link #UNKNOWN}
     */
    public static String os(String userAgent) {
        if (isBlank(userAgent)) {
            return UNKNOWN;
        }
        String ua = userAgent.toLowerCase();
        // Android 与 iOS 都含 Linux/Mac 关键字，必须放在前面判断
        if (ua.contains("android")) {
            return "Android";
        }
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) {
            return "iOS";
        }
        if (ua.contains("harmony") || ua.contains("openharmony")) {
            return "鸿蒙";
        }
        if (ua.contains("windows")) {
            return "Windows";
        }
        if (ua.contains("mac os") || ua.contains("macintosh")) {
            return "macOS";
        }
        if (ua.contains("linux") || ua.contains("x11")) {
            return "Linux";
        }
        return UNKNOWN;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
