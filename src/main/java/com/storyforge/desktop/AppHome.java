package com.storyforge.desktop;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * 数据根目录解析：把「所有可变状态」收敛到 exe 同级的 {@code data/} 下。
 *
 * <p>不能依赖进程工作目录——从开始菜单、快捷方式或资源管理器双击启动时，CWD 是不确定的。
 * jpackage 打包的启动器会注入 {@code jpackage.app-path} 系统属性指向 exe 自身，
 * 这是唯一可靠的锚点。开发态（{@code java -jar}）没有该属性，回落到当前目录的 {@code ./data}，
 * 与既有行为完全一致。</p>
 *
 * <p>本类只在桌面 / SQLite 单机模式下由 {@link DesktopBootstrap#init(String[])} 调用，
 * PostgreSQL / MySQL 服务器模式下不会执行，服务端行为零变化。</p>
 */
public final class AppHome {

    /** 数据根目录的系统属性名，application*.yml 通过 ${storyforge.home:./data} 引用。 */
    public static final String HOME_PROPERTY = "storyforge.home";

    private static Path home;

    private AppHome() {
    }

    /**
     * 解析数据根目录并写入系统属性，同时创建标准子目录。
     *
     * @return 数据根目录（……/data）
     */
    public static Path init() {
        String appPath = System.getProperty("jpackage.app-path");
        Path base;
        if (appPath != null && !appPath.isBlank()) {
            // jpackage 启动器注入：exe 的绝对路径，取父目录即应用根
            base = Path.of(appPath).toAbsolutePath().normalize().getParent();
        } else {
            base = Path.of("").toAbsolutePath().normalize();
        }
        Path data = base.resolve("data");
        home = data;

        System.setProperty(HOME_PROPERTY, data.toString());
        // sqlite-jdbc 首次连接要把原生库解压到临时目录，默认落在系统 temp，
        // 各种「清理工具」最爱删那里，导致第二次启动莫名失败；指到 data/tmp 自管。
        System.setProperty("org.sqlite.tmpdir", data.resolve("tmp").toString());
        // Tomcat 工作目录、 multipart 上传临时文件同样走 java.io.tmpdir。
        // 此处早于 Spring / Tomcat 初始化，改写属性对它们可见。
        System.setProperty("java.io.tmpdir", data.resolve("tmp").toString());

        try {
            Files.createDirectories(data);
            Files.createDirectories(data.resolve("tmp"));
            Files.createDirectories(data.resolve("covers"));
            Files.createDirectories(data.resolve("files"));
            Files.createDirectories(data.resolve("logs"));
        } catch (IOException e) {
            throw new IllegalStateException("无法创建数据目录：" + data + "（请确认安装目录可写）", e);
        }
        return data;
    }

    /** 数据根目录；{@link #init()} 之前调用返回 null。 */
    public static Path home() {
        return home;
    }

    /** 单实例锁文件路径（data/.instance.lock），只做互斥、不写内容。 */
    public static Path lockFile() {
        return home.resolve(".instance.lock");
    }

    /**
     * 端口信息文件（data/.instance.port）。
     *
     * <p>刻意与锁文件分开：锁文件被 {@link InstanceLock} 整区独占，Windows 上
     * 被锁定的区域连读取都会被拒绝，第二个实例就读不到运行中实例的端口。
     * 端口文件不加锁、可自由读取，进程退出时删除。</p>
     */
    public static Path portFile() {
        return home.resolve(".instance.port");
    }

    /** 写入本实例监听的端口，供第二个实例读取后直达。 */
    public static void writePortFile(int port) {
        try {
            Files.writeString(portFile(), String.valueOf(port),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[desktop] 警告：无法写入端口文件（" + e.getMessage() + "）");
        }
    }

    /** 读取端口文件；不存在或内容非法时返回空。 */
    public static OptionalInt readPortFile() {
        try {
            Path file = portFile();
            if (!Files.exists(file)) {
                return OptionalInt.empty();
            }
            String text = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(Integer.parseInt(text));
        } catch (IOException | NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    /** 删除端口文件（进程正常退出时调用；崩溃残留无碍——读取方只在抢不到锁时才会看它）。 */
    public static void deletePortFile() {
        try {
            Files.deleteIfExists(portFile());
        } catch (IOException ignored) {
            // 删不掉不影响下次启动：拿到锁的实例会覆写
        }
    }
}
