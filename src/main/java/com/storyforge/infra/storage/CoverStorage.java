package com.storyforge.infra.storage;

import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.exception.BizException;
import com.storyforge.config.CoverProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 封面图片的本地目录存储。
 *
 * <p>目录由 {@code storyforge.cover.root} 指定，容器内通过 docker-compose 卷挂载，
 * 相对路径规则固定为 {@code user_id/novel_id/{uuid}.jpg}（见 {@code NovelCoverServiceImpl}）。</p>
 *
 * <p>安全：所有相对路径都会规范化并校验是否仍在根目录内，防止目录穿越（{@code ../}）。</p>
 */
@Slf4j
@Component
public class CoverStorage {

    private final Path root;

    public CoverStorage(CoverProperties properties) {
        this.root = Paths.get(properties.getRoot()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            log.info("[cover] cover storage root = {}", root);
        } catch (IOException ex) {
            throw new IllegalStateException("初始化封面存储目录失败: " + root, ex);
        }
    }

    /** 保存字节，返回相对路径。 */
    public String save(String relativePath, byte[] content) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            return relativePath;
        } catch (IOException ex) {
            log.error("[cover] save failed path={}", relativePath, ex);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "保存封面图片失败");
        }
    }

    /** 读取文件资源；不存在时返回一个必然不存在的资源，由调用方判断 {@link #exists}。 */
    public Resource load(String relativePath) {
        return new FileSystemResource(resolve(relativePath));
    }

    public boolean exists(String relativePath) {
        return Files.isRegularFile(resolve(relativePath));
    }

    /** 删除单个文件，失败只记日志（封面行已删，孤儿文件不影响业务）。 */
    public void delete(String relativePath) {
        try {
            Files.deleteIfExists(resolve(relativePath));
        } catch (IOException ex) {
            log.warn("[cover] delete failed path={}: {}", relativePath, ex.getMessage());
        }
    }

    /** 删除一部小说的整个目录（删小说时调用）。返回目录是否真的存在过。 */
    public boolean deleteNovelDirectory(Long userId, Long novelId) {
        Path directory = resolve(userId + "/" + novelId);
        if (!Files.exists(directory)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(directory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    log.warn("[cover] delete failed path={}: {}", path, ex.getMessage());
                }
            });
        } catch (IOException ex) {
            log.warn("[cover] delete directory failed path={}: {}", directory, ex.getMessage());
            return false;
        }
        return true;
    }

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
