package com.storyforge.desktop;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * 端口选择：优先尊重显式配置，未指定时从 8080 起顺延。
 *
 * <p>桌面版是「双击即用」的产品，不能因为用户本机 8080 被别的程序占了就打不开。
 * 显式指定的来源（环境变量 {@code SERVER_PORT}、{@code -Dserver.port}、命令行
 * {@code --server.port=}）一律优先，保证脚本与开发者既有用法不变。</p>
 */
public final class PortPicker {

    /** 桌面版默认端口。 */
    public static final int DEFAULT_PORT = 8080;

    /** 最多顺延尝试的候选端口数量（8080 ~ 8089）。 */
    public static final int MAX_CANDIDATES = 10;

    private PortPicker() {
    }

    /**
     * 读取显式指定的端口。
     *
     * @return 端口号；未显式指定返回 -1
     */
    public static int explicitPort(String[] args) {
        String fromEnv = System.getenv("SERVER_PORT");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return parse(fromEnv.trim(), "环境变量 SERVER_PORT");
        }
        String fromProperty = System.getProperty("server.port");
        if (fromProperty != null && !fromProperty.isBlank()) {
            return parse(fromProperty.trim(), "系统属性 server.port");
        }
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg == null) {
                    continue;
                }
                if (arg.startsWith("--server.port=")) {
                    return parse(arg.substring("--server.port=".length()).trim(), "命令行参数");
                }
                if ("--server.port".equals(arg) && i + 1 < args.length && args[i + 1] != null) {
                    return parse(args[i + 1].trim(), "命令行参数");
                }
            }
        }
        return -1;
    }

    /**
     * 从 {@link #DEFAULT_PORT} 起找到第一个可绑定端口。
     *
     * @return 可用端口；全部被占用返回 -1
     */
    public static int pick() {
        for (int port = DEFAULT_PORT; port < DEFAULT_PORT + MAX_CANDIDATES; port++) {
            if (isFree(port)) {
                return port;
            }
        }
        return -1;
    }

    /**
     * 探测端口是否可绑定（只绑 127.0.0.1：既覆盖「别的进程占了 0.0.0.0」，
     * 也避免误判仅绑了其它网卡的同类服务）。探测用 Socket 立即关闭，
     * 与随后 Tomcat 的正式绑定之间存在极小竞态，单用户桌面场景可接受。
     */
    public static boolean isFree(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))) {
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static int parse(String raw, String source) {
        try {
            int port = Integer.parseInt(raw);
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("端口越界：" + raw);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalStateException(source + " 中的端口不是合法端口号：" + raw, e);
        }
    }
}
