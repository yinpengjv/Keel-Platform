package com.scaffold.admin.controller;

import com.scaffold.common.domain.R;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class HealthController {

    private final ResourceLoader resourceLoader;
    /** 兜底版本：未打包 VERSION 资源时（IDE 直接运行）可用配置项覆盖 */
    private final String configuredVersion;

    public HealthController(ResourceLoader resourceLoader,
                             @Value("${keel.version:}") String configuredVersion) {
        this.resourceLoader = resourceLoader;
        this.configuredVersion = configuredVersion;
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("version", resolveVersion());
        data.put("time", OffsetDateTime.now().toString());
        return R.success(data);
    }

    /**
     * 版本来源优先级：classpath:/keel/VERSION（项目根 VERSION 打包进来）> 配置项 keel.version > vdev。
     * 与前端界面显示、镜像 tag 同源，避免三处版本漂移。
     */
    private String resolveVersion() {
        try {
            Resource resource = resourceLoader.getResource("classpath:keel/VERSION");
            if (resource.exists()) {
                String content = resource.getContentAsString(StandardCharsets.UTF_8).trim();
                if (!content.isEmpty()) {
                    return content.startsWith("v") ? content : "v" + content;
                }
            }
        } catch (IOException ignored) {
            // 读不到就回落配置项
        }
        String fallback = configuredVersion == null ? "" : configuredVersion.trim();
        if (fallback.isEmpty()) {
            return "vdev";
        }
        return fallback.startsWith("v") ? fallback : "v" + fallback;
    }
}
