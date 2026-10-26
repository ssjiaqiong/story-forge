package com.storyforge.desktop;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.OptionalInt;

/**
 * 桌面 / SQLite 单机模式的启动编排，在 Spring 上下文创建前完成四件事：
 *
 * <ol>
 *   <li>{@link AppHome#init()} —— 解析数据根目录（exe 同级的 {@code data/}），
 *       让 SQLite 库文件、封面、临时文件、日志全部落在可预期的位置；</li>
 *   <li>单实例检查 —— 抢不到 {@code data/.instance.lock} 说明已有一个实例在跑，
 *       读它记录的端口、打开浏览器指向那个实例，本进程安静退出；</li>
 *   <li>端口选择 —— 未显式指定时从 8080 顺延到 8089，把最终端口以系统属性
 *       {@code server.port} 注入（Spring 启动时读取）；</li>
 *   <li>启动完成后自动打开浏览器（{@link #openBrowserWhenReady()}）。</li>
 * </ol>
 *
 * <p>判定「桌面模式」的条件：被打包（{@code jpackage.app-path} 存在）或运行 profile
 * 含 sqlite。PostgreSQL / MySQL 服务器模式下 {@link #init(String[])} 直接返回，
 * 不碰端口、不建锁、不开浏览器，服务端部署行为与改造前完全一致。</p>
 */
public final class DesktopBootstrap {

    /** 设为 false 可关闭「启动后自动打开浏览器」。 */
    private static final String AUTO_OPEN_PROPERTY = "storyforge.desktop.auto-open";

    private static final long PORT_FILE_WAIT_MILLIS = 2_000L;
    private static final long PORT_FILE_POLL_MILLIS = 100L;

    private static boolean desktopMode;
    private static boolean portManaged;
    private static int port = -1;

    private DesktopBootstrap() {
    }

    /**
     * 在 {@code SpringApplication.run} 之前调用。非桌面模式为纯 no-op。
     */
    public static void init(String[] args) {
        desktopMode = isDesktopMode(args);
        if (!desktopMode) {
            return;
        }

        Path home = AppHome.init();

        InstanceLock lock = InstanceLock.tryAcquire(AppHome.lockFile());
        if (lock == null) {
            // 已有实例正在运行：把用户带到它那里，而不是再起一个进程抢同一个库
            OptionalInt runningPort = waitForRunningPort();
            String url = "http://127.0.0.1:" + runningPort.orElse(PortPicker.DEFAULT_PORT);
            System.out.println("[desktop] StoryForge 已在运行，正在打开已运行的实例：" + url);
            openBrowser(url);
            System.exit(0);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            lock.close();
            AppHome.deletePortFile();
        }, "storyforge-instance-lock"));

        int explicit = PortPicker.explicitPort(args);
        if (explicit > 0) {
            port = explicit;
        } else {
            int picked = PortPicker.pick();
            if (picked < 0) {
                fatal("8080 ~ 8089 端口全部被其它程序占用。\n请关闭占用端口的程序后重试，"
                        + "或通过 --server.port 指定其它端口。");
                return;
            }
            port = picked;
            portManaged = true;
            System.setProperty("server.port", String.valueOf(port));
        }
        // 端口写进独立的 .instance.port（不加文件锁）：锁文件是独占的，
        // 在 Windows 上被锁定的区域连读都读不了，第二个实例就永远拿不到端口
        AppHome.writePortFile(port);

        System.out.println("[desktop] 数据目录：" + home);
        if (portManaged && port != PortPicker.DEFAULT_PORT) {
            System.out.println("[desktop] 服务端口：" + port + "（8080 被占用，自动顺延）");
        } else if (portManaged) {
            System.out.println("[desktop] 服务端口：" + port);
        } else {
            System.out.println("[desktop] 服务端口：" + port + "（显式指定）");
        }
        System.out.println("[desktop] 启动完成后将自动打开浏览器");
    }

    /**
     * 在 {@code SpringApplication.run} 返回后（ApplicationReadyEvent 已发布）调用，
     * 打开本地地址。无头环境（无显示器 / 无默认浏览器）自动跳过。
     */
    public static void openBrowserWhenReady() {
        if (!desktopMode) {
            return;
        }
        if ("false".equalsIgnoreCase(System.getProperty(AUTO_OPEN_PROPERTY))) {
            return;
        }
        int effective = port > 0 ? port : PortPicker.explicitPort(new String[0]);
        if (effective <= 0) {
            return;
        }
        openBrowser("http://127.0.0.1:" + effective);
    }

    // ------------------------------------------------------------------ 内部

    private static boolean isDesktopMode(String[] args) {
        if (System.getProperty("jpackage.app-path") != null) {
            return true;
        }
        if (containsProfile(System.getenv("SPRING_PROFILES_ACTIVE"))
                || containsProfile(System.getProperty("spring.profiles.active"))) {
            return true;
        }
        return args != null && Arrays.stream(args).anyMatch(DesktopBootstrap::containsProfile);
    }

    private static boolean containsProfile(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .anyMatch("sqlite"::equalsIgnoreCase);
    }

    /** 等第一个实例把端口写进端口文件（它刚抢到锁、可能还没来得及写）。 */
    private static OptionalInt waitForRunningPort() {
        long deadline = System.currentTimeMillis() + PORT_FILE_WAIT_MILLIS;
        while (true) {
            OptionalInt runningPort = AppHome.readPortFile();
            if (runningPort.isPresent()) {
                return runningPort;
            }
            if (System.currentTimeMillis() >= deadline) {
                return OptionalInt.empty();
            }
            try {
                Thread.sleep(PORT_FILE_POLL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return OptionalInt.empty();
            }
        }
    }

    private static void openBrowser(String url) {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                System.out.println("[desktop] 当前环境不支持自动打开浏览器，请手动访问：" + url);
                return;
            }
            Desktop.getDesktop().browse(URI.create(url));
            System.out.println("[desktop] 已打开浏览器：" + url);
        } catch (Throwable e) {
            // 没有默认浏览器、权限限制等：提示地址即可，不影响服务本身
            System.out.println("[desktop] 自动打开浏览器失败（" + e.getMessage() + "），请手动访问：" + url);
        }
    }

    /** 启动前致命错误：控制台 + 图形对话框双通道提示，然后退出。 */
    private static void fatal(String message) {
        System.err.println("[desktop] 启动失败：" + message);
        try {
            if (Desktop.isDesktopSupported()) {
                javax.swing.JOptionPane.showMessageDialog(null, message,
                        "StoryForge 启动失败", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        } catch (Throwable ignored) {
            // 无头环境没有对话框，控制台信息已足够
        }
        System.exit(1);
    }
}
