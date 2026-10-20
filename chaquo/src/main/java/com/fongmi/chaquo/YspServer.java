package com.fongmi.chaquo;

import android.util.Log;

import com.chaquo.python.Python;
import com.github.catvod.Init;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 内置央视频直播（ysp-live v9.5）启动钩子。
 * 应用 onCreate 主线程调用 start()：Chaquopy 初始化 → import ysp_server →
 * 内嵌启动 v9.5 代理（127.0.0.1:8767，/all.m3u 订阅、/<slug>.m3u8 播放）
 * 并同时启动 9980 局域网扫码绑定服务（TMDB API Key）。
 * 失败自动后台重试（Chaquopy 首次初始化需解压 assets，慢设备可达数秒~十几秒）。
 * 启动状态写入 files/ysp_status.txt，便于诊断。
 * 直播订阅地址：http://127.0.0.1:8767/all.m3u
 */
public class YspServer {

    private static final String TAG = "YspServer";
    private static final String HEALTH = "http://127.0.0.1:8767/health";
    private static volatile boolean started = false;

    private YspServer() {
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
        }, "ysp-server-start").start();
    }

    /** 是否已就绪（8767 可服务）。 */
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
            Python.getInstance().getModule("ysp_server").callAttr("start");
            // python start() 返回时 v9.5 已绑定 8767；再探活一次确认可服务
            if (!healthOk(6000)) throw new IllegalStateException("8767 health check failed");
            started = true;
            writeStatus("OK: ysp-live v9.5 http://127.0.0.1:8767/all.m3u");
            appendBoot();
            Log.i(TAG, "ysp-live v9.5 已启动 (8767)");
        } catch (Throwable t) {
            started = false;
            writeStatus("FAIL: " + t);
            log("启动失败: " + t);
            Log.e(TAG, "ysp-live v9.5 启动失败", t);
        }
    }

    /** 探活：GET /health，2xx 即就绪。 */
    private static boolean healthOk(long timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(HEALTH).openConnection();
            conn.setConnectTimeout((int) timeoutMs);
            conn.setReadTimeout((int) timeoutMs);
            int code = conn.getResponseCode();
            try (InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
                if (in != null) in.read(new byte[512]);
            }
            return code >= 200 && code < 400;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 启动成功计数（跨进程持久，用于诊断服务是否反复重启）。 */
    private static void appendBoot() {
        try {
            File dir = Init.context().getFilesDir();
            if (dir != null) {
                try (PrintWriter w = new PrintWriter(new FileOutputStream(new File(dir, "ysp_boot.txt"), true), false, StandardCharsets.UTF_8)) {
                    w.println(System.currentTimeMillis());
                }
            }
        } catch (Throwable ignored) {
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

    /** Python 侧调用：追加一行运行时日志（启动/绑定诊断用）。 */
    public static synchronized void log(String msg) {
        try {
            File dir = Init.context().getFilesDir();
            if (dir != null) {
                File f = new File(dir, "ysp_runtime.log");
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
                try (PrintWriter w = new PrintWriter(new FileOutputStream(new File(dir, "ysp_status.txt")), false, StandardCharsets.UTF_8)) {
                    w.println(System.currentTimeMillis());
                    w.println(msg);
                }
            }
        } catch (Throwable ignored) {
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
}
