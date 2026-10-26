package com.storyforge.infra.storage;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * 文件存储抽象。
 *
 * <p>本平台为单实例部署，实现为本地文件夹（通过 docker-compose 挂载宿主机目录持久化），
 * 不引入对象存储 / 云存储。</p>
 */
public interface FileStorage {

    /**
     * 保存文件。
     *
     * @param relativePath 相对路径（以 {@code /} 分隔，禁止 {@code ..}）
     * @param content      内容流
     * @return 相对路径
     */
    String save(String relativePath, InputStream content);

    /** 读取文件资源。 */
    Resource load(String relativePath);

    /** 删除文件（不存在时返回 false）。 */
    boolean delete(String relativePath);

    /** 判断文件是否存在。 */
    boolean exists(String relativePath);
}
