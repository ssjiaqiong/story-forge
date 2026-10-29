package com.storyforge.modules.system.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 部署信息：当前运行实例的版本号与构建时间。
 *
 * <p>版本号唯一来源是 {@code pom.xml} 的 {@code <version>}，经 spring-boot-maven-plugin 的
 * {@code build-info} 写入 {@code META-INF/build-info.properties}，这里只做只读暴露，
 * 不允许在前端或配置里另写一份常量。</p>
 */
@Schema(description = "部署信息")
public record SystemInfoVO(
        @Schema(description = "构建版本号，取自 pom.xml 的 version", example = "1.2.5")
        String version,

        @Schema(description = "构建时间（yyyy-MM-dd HH:mm:ss）；直接在 IDE 里跑源码时可能为空", example = "2026-10-29 17:20:11")
        String buildTime) {
}
