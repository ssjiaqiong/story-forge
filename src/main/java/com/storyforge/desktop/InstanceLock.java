package com.storyforge.desktop;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 单实例文件锁：{@code data/.instance.lock}。
 *
 * <p>桌面版双击两次不应该起两个进程——两个进程写同一个 SQLite 文件，
 * 即便有 busy_timeout 也会互相拖慢，更会让用户困惑「我改的东西怎么没保存」。
 * 拿不到锁的进程读取锁文件里记录的端口、直接打开浏览器指向已运行的实例，然后安静退出。</p>
 *
 * <p>Windows 上 FileChannel.tryLock 走 LockFileEx，跨进程互斥是强制的；
 * 进程退出（无论正常还是崩溃）时操作系统会自动释放，不会留下需要清理的僵尸锁。</p>
 */
public final class InstanceLock implements AutoCloseable {

    private final Path file;
    private final FileChannel channel;
    private final FileLock lock;

    private InstanceLock(Path file, FileChannel channel, FileLock lock) {
        this.file = file;
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * 尝试获取单实例锁。
     *
     * @return 成功返回持有锁的对象；已被其它实例持有（或 IO 失败）返回 null
     */
    public static InstanceLock tryAcquire(Path file) {
        try {
            Files.createDirectories(file.getParent());
            FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException | IOException e) {
                closeQuietly(channel);
                return null;
            }
            if (lock == null) {
                closeQuietly(channel);
                return null;
            }
            return new InstanceLock(file, channel, lock);
        } catch (IOException e) {
            // 锁文件不可写（只读介质等）时退化为「不互斥」，不阻塞用户启动
            System.err.println("[desktop] 警告：无法创建单实例锁文件 " + file + "（" + e.getMessage() + "），跳过单实例检查");
            return null;
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // 释放失败无需处理：进程退出时系统会强制回收
        } finally {
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // 同上
        }
    }
}
