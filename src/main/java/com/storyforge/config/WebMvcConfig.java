package com.storyforge.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;

/**
 * Web MVC 配置。
 *
 * <p>前后端同源部署：前端（Vue）构建产物置于 {@code classpath:/static/}，由后端统一托管；
 * 非 {@code /api} 的未知路径回退到 {@code index.html}，支持前端路由刷新。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private static final String STATIC_LOCATION = "classpath:/static/";
    private static final String INDEX_HTML = "/static/index.html";

    /** 不做 SPA 回退的路径前缀（这些前缀下的未知路径应返回 404 JSON） */
    private static final String[] NON_SPA_PREFIXES = {
            "api/", "actuator/", "v3/api-docs", "swagger-ui", "error"
    };

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC_LOCATION)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        for (String prefix : NON_SPA_PREFIXES) {
                            if (resourcePath.startsWith(prefix)) {
                                return null;
                            }
                        }
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        Resource index = new ClassPathResource(INDEX_HTML);
                        return index.exists() ? index : null;
                    }
                });
    }
}
