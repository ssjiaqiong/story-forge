package com.storyforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 小说封面目录配置，前缀 {@code storyforge.cover}。
 *
 * <p>与 {@code storyforge.storage}（普通附件）分开放：封面是高频读取的静态资源，
 * 单独挂载便于备份、迁移与设置缓存策略。</p>
 */
@Data
@ConfigurationProperties(prefix = "storyforge.cover")
public class CoverProperties {

    /** 封面根目录（容器内通过 volume 挂载宿主机目录） */
    private String root = "./data/covers";
}
