package com.fongmi.chaquo;

import android.util.Log;

import com.chaquo.python.Python;
import com.github.catvod.Init;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 咪咕代理（Python 版）启动钩子。
 * 应用 onCreate 主线程调用 start()：Chaquopy 初始化 → import migu_server → 启动 127.0.0.1:9979 HTTP 服务。
 * 失败自动后台重试（Chaquopy 首次初始化需解压 assets，慢设备可达数秒~十几秒）。
 * 启动状态写入 files/migu_status.txt，便于诊断。
 * 播放器直播源地址：http://127.0.0.1:9979/migu?list=live
 */
public class MiguServer {

    private static final String TAG = "MiguServer";
    private static volatile boolean started = false;

    private MiguServer() {
    }

    /** 幂等启动：全异步（首次初始化解压 assets 慢设备可达数秒~十几秒，不能在主线程阻塞开屏）。
     *  失败自动后台重试 2 次（间隔 3s）。请求侧用 {@link #awaitReady(long)} 等待就绪。 */
    public static synchronized void start() {
        if (started) return;
        new Thread(() -> {
            doStart();
            if (!started) {
                for (int i = 0; i < 2 && !started; i++) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }
                    doStart();
                }
            }
        }, "migu-server-start").start();
    }

    /** 是否已就绪（9979 可服务）。 */
    public static boolean isReady() {
        return started;
    }

    /** 等待服务就绪，最多 timeoutMs 毫秒；就绪返回 true。请求侧兜底用。 */
    public static boolean awaitReady(long timeoutMs) {
        if (started) return true;
        start();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!started && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return started;
            }
        }
        return started;
    }

    private static void doStart() {
        try {
            if (!Python.isStarted()) Python.start(Platform.create());
            Python.getInstance().getModule("migu_server").callAttr("start");
            started = true;
            writeStatus("OK: MIGU Python server http://127.0.0.1:9979/migu?list=live");
            log("启动成功 (9979)");
            appendBoot();
            Log.i(TAG, "MIGU Python 代理已启动 (9979)");
        } catch (Throwable t) {
            started = false;
            writeStatus("FAIL: " + t);
            log("启动失败: " + t);
            Log.e(TAG, "MIGU Python 代理启动失败", t);
        }
    }

    /** 启动成功计数（跨进程持久，用于在频道列表判断服务是否反复重启）。 */
    private static void appendBoot() {
        try {
            File dir = Init.context().getFilesDir();
            if (dir != null) {
                try (PrintWriter w = new PrintWriter(new FileOutputStream(new File(dir, "migu_boot.txt"), true), false, StandardCharsets.UTF_8)) {
                    w.println(System.currentTimeMillis());
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Python 侧调用：返回累计启动成功次数（无文件时 0）。 */
    public static synchronized int bootCount() {
        try {
            File dir = Init.context().getFilesDir();
            if (dir != null) {
                File f = new File(dir, "migu_boot.txt");
                if (f.exists()) {
                    int n = 0;
                    java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(f));
                    try {
                        while (r.readLine() != null) n++;
                    } finally {
                        r.close();
                    }
                    return n;
                }
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** Python 侧调用：读取咪咕账号（Prefers），格式 "uid|token"，无账号返回空串。 */
    public static synchronized String getAccount() {
        try {
            String uid = com.github.catvod.utils.Prefers.getString("migu_uid", "");
            String token = com.github.catvod.utils.Prefers.getString("migu_token", "");
            if (uid.isEmpty() || token.isEmpty()) return "";
            return uid + "|" + token;
        } catch (Throwable t) {
            return "";
        }
    }

    private static volatile String bindToken = null;

    /** 扫码绑定一次性 token（进程内生成一次，二维码携带、提交时校验）。 */
    public static synchronized String getBindToken() {
        if (bindToken == null) {
            java.security.SecureRandom r = new java.security.SecureRandom();
            byte[] b = new byte[8];
            r.nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            bindToken = sb.toString();
        }
        return bindToken;
    }

    /** 扫码绑定写入账号（手机端网页 POST 到本机 9980，Python 回调此方法）。 */
    public static synchronized String saveAccount(String uid, String token) {
        try {
            if (uid == null || token == null) return "EMPTY";
            uid = uid.trim();
            token = token.trim();
            if (uid.isEmpty() || token.isEmpty()) return "EMPTY";
            com.github.catvod.utils.Prefers.put("migu_uid", uid);
            com.github.catvod.utils.Prefers.put("migu_token", token);
            log("扫码绑定账号 uid=" + uid);
            return "OK";
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    /** 扫码绑定写入 TMDB API Key（手机端网页 POST 到本机 9980，Python 回调此方法）。 */
    public static synchronized String saveTmdbKey(String key) {
        try {
            if (key == null) return "EMPTY";
            key = key.trim();
            if (key.isEmpty()) return "EMPTY";
            com.github.catvod.utils.Prefers.put("tmdb_api_key", key);
            log("扫码绑定TMDB key=" + key.substring(0, Math.min(8, key.length())));
            return "OK";
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    /** 本机局域网 IPv4（供二维码绑定页使用）；找不到时回退 127.0.0.1。 */
    public static synchronized String getLocalIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                java.net.NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<java.net.InetAddress> ea = ni.getInetAddresses();
                while (ea.hasMoreElements()) {
                    java.net.InetAddress ia = ea.nextElement();
                    if (ia instanceof java.net.Inet4Address && !ia.isLoopbackAddress()) {
                        String h = ia.getHostAddress();
                        if (h.startsWith("192.168.") || h.startsWith("10.") || h.startsWith("172.")) {
                            return h;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return "127.0.0.1";
    }

    /** Python 侧调用：追加一行运行时日志（取址/拉流失败诊断用）。 */
    public static synchronized void log(String msg) {
        try {
            File dir = Init.context().getFilesDir();
            if (dir != null) {
                File f = new File(dir, "migu_runtime.log");
                try (PrintWriter w = new PrintWriter(new FileOutputStream(f, true), false, StandardCharsets.UTF_8)) {
                    w.println(System.currentTimeMillis() + " " + msg);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeStatus(String msg) {
        try {
            File dir = Init.context().getFilesDir();
            if (dir != null) {
                try (PrintWriter w = new PrintWriter(new FileOutputStream(new File(dir, "migu_status.txt")), false, StandardCharsets.UTF_8)) {
                    w.println(System.currentTimeMillis());
                    w.println(msg);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
