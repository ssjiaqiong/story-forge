package com.storyforge.modules.system.service;

import com.storyforge.modules.system.dto.SystemInfoVO;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 部署信息读取。
 *
 * <p>{@link BuildProperties} 由 {@code META-INF/build-info.properties} 提供，该文件在
 * {@code mvn package} 期间由 spring-boot-maven-plugin 的 {@code build-info} 目标生成。
 * 因此「构建产物里冻结的版本号」= 发版时的版本号，部署后一眼能看出跑的是哪个版本。</p>
 *
 * <p>用 {@link ObjectProvider} 注入而不是强依赖：在 IDE 里直接跑源码、或跑单元测试时
 * 往往没有经过 Maven 的 build-info 阶段，此时降级返回 unknown，不让页面因此报错。</p>
 */
@Service
public class SystemInfoService {

    private static final String UNKNOWN = "unknown";

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final ObjectProvider<BuildProperties> buildProperties;

    public SystemInfoService(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    public SystemInfoVO info() {
        BuildProperties build = buildProperties.getIfAvailable();
        if (build == null) {
            return new SystemInfoVO(UNKNOWN, null);
        }
        String version = build.getVersion();
        if (version == null || version.isBlank()) {
            version = UNKNOWN;
        }
        String buildTime = build.getTime() == null ? null : TIME_FORMAT.format(build.getTime());
        return new SystemInfoVO(version, buildTime);
    }
}
