package com.storyforge.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置（{@code /v3/api-docs}，可导入 Apifox）。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI storyForgeOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("StoryForge API")
                        .description("AI 长篇小说创作平台接口。单用户空间（用户即隔离单元）+ 超级管理员平台功能。")
                        .version("1.0.0")
                        .contact(new Contact().name("StoryForge")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录后返回的 accessToken，格式：Bearer {token}")));
    }
}
