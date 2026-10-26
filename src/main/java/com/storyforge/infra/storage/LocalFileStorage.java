package com.storyforge.infra.storage;

import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;
import com.storyforge.config.StorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * 本地文件夹存储实现。
 *
 * <p>路径安全：所有相对路径都会被规范化并校验是否仍在根目录内，防止目录穿越。</p>
 */
@Slf4j
@Component
public class LocalFileStorage implements FileStorage {

    private final Path root;

    public LocalFileStorage(StorageProperties properties) {
        this.root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("[storage] local file storage root = {}", root);
        } catch (IOException ex) {
            throw new IllegalStateException("初始化本地文件存储目录失败: " + root, ex);
        }
    }

    @Override
    public String save(String relativePath, InputStream content) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
            return relativePath;
        } catch (IOException ex) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "保存文件失败: " + relativePath);
        }
    }

    @Override
    public Resource load(String relativePath) {
        return new FileSystemResource(resolve(relativePath));
    }

    @Override
    public boolean delete(String relativePath) {
        try {
            return Files.deleteIfExists(resolve(relativePath));
        } catch (IOException ex) {
            log.warn("[storage] delete failed: {}", relativePath, ex);
            return false;
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    /** 解析并校验相对路径。 */
    private Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "文件路径不能为空");
        }
        Path target = root.resolve(relativePath).normalize();
        if (!target.startsWith(root)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法的文件路径");
        }
        return target;
    }
}
