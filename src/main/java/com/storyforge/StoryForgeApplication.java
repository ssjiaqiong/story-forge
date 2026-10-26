/*
 * StoryForge - AI 长篇小说创作平台
 * Copyright (C) 2026 Jiaqiong
 *
 * 本程序是自由软件：你可以依据 GNU Affero 通用公共许可证第 3 版
 * （或你选择的任何更新版本）的条款再分发和/或修改它。
 * 协议全文见仓库根目录 LICENSE；本程序不提供任何担保。
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.storyforge;

import com.storyforge.desktop.DesktopBootstrap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * StoryForge 启动入口。
 *
 * <p>AI 长篇小说创作平台：单用户空间（用户即隔离单元）+ 超级管理员平台功能。</p>
 *
 * <p>鉴权完全由 JWT 过滤器驱动，不需要 Spring Boot 默认的内存用户，
 * 故排除 {@link UserDetailsServiceAutoConfiguration}，避免启动时生成无意义的随机口令。</p>
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class StoryForgeApplication {

    public static void main(String[] args) {
        // 桌面 / SQLite 单机模式：解析 data 目录、端口顺延、单实例检查。
        // 服务器模式（PG / MySQL）下是 no-op，不改变任何既有行为。
        DesktopBootstrap.init(args);
        SpringApplication.run(StoryForgeApplication.class, args);
        // run() 返回时 ApplicationReadyEvent 已发布，此时开浏览器用户看到的就是就绪页面
        DesktopBootstrap.openBrowserWhenReady();
    }
}
