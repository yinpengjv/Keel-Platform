package com.scaffold.framework.clientinfo;

import org.lionsoul.ip2region.service.Config;
import org.lionsoul.ip2region.service.ConfigBuilder;
import org.lionsoul.ip2region.service.Ip2Region;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 离线 IP 归属地查询。
 *
 * <p>基于 ip2region 3.x 的 xdb 引擎，数据文件随 jar 一起打包
 * （{@code classpath:ip2region/ip2region_v4.xdb} 与 {@code ip2region_v6.xdb}），
 * 无需联网、无外部服务依赖。</p>
 *
 * <p>容错策略：数据文件缺失或初始化失败时<b>不阻断应用启动</b>，
 * 查询一律回落为「内网」或「未知」，只影响归属地这一列的展示。</p>
 */
@Service
public class IpLocationService {

    private static final Logger log = LoggerFactory.getLogger(IpLocationService.class);

    private static final String V4_RESOURCE = "ip2region/ip2region_v4.xdb";
    private static final String V6_RESOURCE = "ip2region/ip2region_v6.xdb";

    /** 内网与回环地址统一显示，避免查库得到无意义的「0.0.0.0」 */
    private static final String LOCAL = "内网";

    private final AtomicReference<Ip2Region> searcher = new AtomicReference<>();

    public IpLocationService() {
        try {
            Ip2Region region = Ip2Region.create(buildConfig(V4_RESOURCE, true), buildConfig(V6_RESOURCE, false));
            searcher.set(region);
            log.info("ip2region 离线库初始化完成");
        } catch (Exception ex) {
            log.warn("ip2region 离线库初始化失败，归属地查询将回落为内网/未知：{}", ex.getMessage());
        }
    }

    /**
     * 构造 xdb 配置。
     *
     * <p>走 InputStream 分支时，ip2region 会在 asV4()/asV6() 内部把内容完整读入内存
     * （{@code Searcher.loadContentFromInputStream}），因此此处关闭流是安全的。</p>
     */
    private Config buildConfig(String resource, boolean v4) throws Exception {
        ClassPathResource res = new ClassPathResource(resource);
        try (InputStream in = res.getInputStream()) {
            ConfigBuilder builder = Config.custom()
                    // setXdbInputStream 只支持 BufferCache（整库载入内存）；
                    // xdb 约 11MB/18MB，内存占用可接受
                    .setCachePolicy(Config.BufferCache)
                    .setCacheSliceBytes(8 * 1024 * 1024)
                    .setSearchers(4)
                    .setXdbInputStream(in);
            return v4 ? builder.asV4() : builder.asV6();
        }
    }

    /**
     * 查询 IP 归属地。
     *
     * @param ip 客户端 IP，可为 null
     * @return 形如「广东省深圳市」或「中国」的归属地；内网返回「内网」，
     *         无法识别返回「未知」
     */
    public String locate(String ip) {
        if (ip == null || ip.isBlank()) {
            return "未知";
        }
        if (isPrivate(ip)) {
            return LOCAL;
        }
        Ip2Region region = searcher.get();
        if (region == null) {
            return "未知";
        }
        try {
            String raw = region.search(ip);
            if (raw == null || raw.isBlank()) {
                return "未知";
            }
            return format(raw);
        } catch (Exception ex) {
            log.debug("IP 归属地查询失败 ip={}: {}", ip, ex.getMessage());
            return "未知";
        }
    }

    /**
     * 把 ip2region 的「国家|区域|省份|城市|ISP」整理成可读归属地。
     *
     * <p>例如 {@code 中国|0|广东省|深圳市|电信} → {@code 广东省深圳市}；
     * 省市相同或为空时自动去重，避免出现「广东省广东省」。</p>
     */
    private String format(String raw) {
        String[] parts = raw.split("\\|");
        String country = parts.length > 0 ? parts[0].trim() : "";
        String region = parts.length > 1 ? parts[1].trim() : "";
        String province = parts.length > 2 ? parts[2].trim() : "";
        String city = parts.length > 3 ? parts[3].trim() : "";

        StringBuilder sb = new StringBuilder();
        // 0 表示该字段无有效值
        if (!region.isEmpty() && !"0".equals(region)) {
            sb.append(region);
        }
        if (!province.isEmpty() && !"0".equals(province)) {
            if (sb.length() > 0 && !province.startsWith(sb.toString())) {
                sb.append(province);
            } else if (sb.length() == 0) {
                sb.append(province);
            }
        }
        if (!city.isEmpty() && !"0".equals(city)) {
            if (sb.length() > 0 && !city.equals(sb.toString()) && !sb.toString().endsWith(city)) {
                sb.append(city);
            } else if (sb.length() == 0) {
                sb.append(city);
            }
        }
        if (sb.length() == 0) {
            return country.isEmpty() ? "未知" : country;
        }
        // 直辖市/境外场景 province 为国家名时，去掉重复前缀
        String result = sb.toString();
        if (!country.isEmpty() && !result.startsWith(country) && country.length() <= 3) {
            result = country + result;
        }
        return result;
    }

    /** 判断是否内网/回环/容器网段地址 */
    public boolean isPrivate(String ip) {
        if (ip == null || ip.isBlank()) {
            return true;
        }
        String value = ip.trim();
        if ("localhost".equalsIgnoreCase(value) || "::1".equals(value)) {
            return true;
        }
        if (value.startsWith("127.") || value.startsWith("10.") || value.startsWith("192.168.")
                || value.startsWith("169.254.")) {
            return true;
        }
        // 172.16.0.0 ~ 172.31.255.255
        if (value.startsWith("172.")) {
            try {
                int second = Integer.parseInt(value.split("\\.")[1]);
                if (second >= 16 && second <= 31) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 解析不了就按公网处理
            }
        }
        // Docker 默认网段 172.17~172.31 已覆盖；fc00::/7 与 fe80::/10 为 IPv6 内网
        String lower = value.toLowerCase();
        if (lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80")) {
            return true;
        }
        return false;
    }

    @PreDestroy
    public void close() {
        Ip2Region region = searcher.getAndSet(null);
        if (region != null) {
            try {
                region.close();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
