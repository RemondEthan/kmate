package com.glodon.mordor.kmate.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 本机只跑一个客户端。第二个进程唤醒已有窗口后退出，避免旧昵称会话还活着。
 *
 * 实现机制：TCP loopback 端口 18731 抢占。
 *   - 第一个进程：ServerSocket 绑定成功 → 自己是主实例，返回 true。
 *   - 第二个进程：bind 失败 → 连已有实例发一个 '!' 字节（信号）→ 自己退出，返回 false。
 *
 * 这种"探测"式的单实例锁比文件锁简单，缺点：跨用户不隔离（同机多用户会互相挤）。
 * 项目是单用户桌面聊天客户端，足够用。
 */
final class SingleInstance {

    // TCP loopback 端口。挑一个不太可能冲突的随机高端口。
    private static final int PORT = 18731;

    private SingleInstance() {}

    /**
     * 尝试声明"主实例"身份。
     *
     * @param onShow 已有实例收到唤醒信号时回调（用于把隐藏的窗口拉到前台）
     * @return true = 成功声明（本次进程是主实例）；false = 端口已被占（本进程应退出）
     */
    static boolean claim(Runnable onShow) {
        try {
            // backlog=1：只接受 1 个待处理连接；InetAddress.getLoopbackAddress() 只绑 127.0.0.1，不开外网。
            ServerSocket server = new ServerSocket(PORT, 1, InetAddress.getLoopbackAddress());
            Thread t = new Thread(() -> listen(server, onShow), "kmate-instance");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (IOException occupied) {
            // bind 失败说明已有实例在监听 → 唤醒它后退出。
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), PORT)) {
                // 任意写一个字节：服务端 accept 后会调 onShow.run()。
                socket.getOutputStream().write('!');
            } catch (IOException ignored) {
                // 唤醒失败也退出，避免再开一条会话
            }
            return false;
        }
    }

    /**
     * 在独立线程上循环 accept 唤醒连接。
     * 每次 accept 都会阻塞到有新连接；连接进来后调 onShow，再回到循环。
     */
    private static void listen(ServerSocket server, Runnable onShow) {
        while (!server.isClosed()) {
            try (Socket ignored = server.accept()) {
                if (onShow != null) {
                    onShow.run();
                }
            } catch (IOException ignored) {
                return;
            }
        }
    }
}
