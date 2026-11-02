package com.scaffold.admin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * 单镜像部署时由后端托管前端静态文件。
 *
 * <p>默认关闭：只有配置了 {@code app.static-dir}（容器里是 {@code APP_STATIC_DIR=/app/static}）才生效；
 * 本地开发时前端走 Vite，行为完全不变。
 *
 * <p>前端是 vue-router history 模式，找不到的路径回落到 {@code index.html}；
 * 但 {@code /api/**} 不参与回落，接口不存在时仍然返回 404，不会被吞成 HTML。
 */
@Configuration
public class SpaStaticConfig implements WebMvcConfigurer {

    private final String staticDir;

    public SpaStaticConfig(@Value("${app.static-dir:}") String staticDir) {
        this.staticDir = staticDir;
    }

    private boolean enabled() {
        return staticDir != null && !staticDir.isBlank();
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (!enabled()) {
            return;
        }
        // 根路径显式转发，否则会被 Spring Boot 的欢迎页映射拦成 404
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!enabled()) {
            return;
        }
        String location = staticDir.endsWith("/") ? staticDir : staticDir + "/";
        registry.addResourceHandler("/**")
                .addResourceLocations("file:" + location)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        if (resourcePath.startsWith("api/") || resourcePath.equals("api")) {
                            return null;
                        }
                        if (resourcePath.isEmpty() || resourcePath.endsWith("/")) {
                            return indexOf(location);
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        // 带扩展名的是构建产物（js/css/图片/字体等）：文件不存在就老实返回 404。
                        // 若把它们回落到 index.html，浏览器会拿到 Content-Type: text/html 的 HTML
                        // 去执行动态 import()，抛出的错误既不是 404 也不是业务错误，
                        // 表现为「登录接口成功但页面进不去」，且无法从服务端日志定位。
                        if (looksLikeStaticAsset(resourcePath)) {
                            return null;
                        }
                        return indexOf(location);
                    }

                    /** 路径最后一段是否带文件扩展名（排除目录名里的点，如 a.b/c） */
                    private boolean looksLikeStaticAsset(String resourcePath) {
                        int slash = resourcePath.lastIndexOf('/');
                        String last = slash >= 0 ? resourcePath.substring(slash + 1) : resourcePath;
                        int dot = last.lastIndexOf('.');
                        return dot > 0 && dot < last.length() - 1;
                    }

                    private Resource indexOf(Resource location) throws IOException {
                        Resource index = location.createRelative("index.html");
                        return index.exists() && index.isReadable() ? index : null;
                    }
                });
    }
}
