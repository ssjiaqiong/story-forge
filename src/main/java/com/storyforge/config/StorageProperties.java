package com.storyforge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地文件存储配置，前缀 {@code storyforge.storage}。
 */
@Data
@ConfigurationProperties(prefix = "storyforge.storage")
public class StorageProperties {

    /** 本地文件根目录（容器内通过 volume 挂载宿主机目录） */
    private String root = "./data/files";
}
