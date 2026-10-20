package com.fongmi.chaquo;

import android.util.Log;

import com.chaquo.python.Python;
import com.github.catvod.Init;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 内置央视频直播网关（ysp-live v9.5，Python 版）启动钩子。
 * 应用 onCreate 调用 start()：Chaquopy 初始化 → import ysp_live → 后台线程常驻 127.0.0.1:9979 网关，
 * 同时启动局域网扫码绑定服务 0.0.0.0:9980（TMDB API Key，bind_server）。
 * 失败自动后台重试（Chaquopy 首次初始化需解压 assets，慢设备可达数秒~十几秒）。
 * 启动状态写入 files/ysp_status.txt，便于诊断。
 * 全频道订阅: http://127.0.0.1:9979/all.m3u（App 内经 Nano /ysp 转发，见 server.process.Ysp）
 */
public class YspServer {

    private static final String TAG = "YspServer";
    private static volatile boolean started = false;

    private YspServer() {
    }

    /** 幂等启动：全异步（首次初始化解压 assets 慢设备可达数秒~十几秒，不能在主线程阻塞开屏）。
     *  失败自动后台重试 2 次（间隔 3s）。列表请求侧用重试兜底（见 process.Ysp）。 */
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

    /** 网关线程是否已拉起（不代表端口已绑定，列表请求侧自行重试）。 */
    public static boolean isReady() {
        return started;
    }

    private static void doStart() {
        try {
            if (!Python.isStarted()) Python.start(Platform.create());
            Python.getInstance().getModule("ysp_live").callAttr("start", "9979", "127.0.0.1", dataDir().getAbsolutePath(), "8787");
            started = true;
            writeStatus("OK: YSP gateway http://127.0.0.1:9979/all.m3u");
            log("央视频网关线程已拉起 (9979)");
            Log.i(TAG, "ysp-live v9.5 网关已启动 (9979)");
        } catch (Throwable t) {
            started = false;
            writeStatus("FAIL: " + t);
            log("央视频网关启动失败: " + t);
            Log.e(TAG, "ysp-live 网关启动失败", t);
        }
        // 扫码绑定服务（TMDB）失败不影响直播，单独兜底
        try {
            Python.getInstance().getModule("bind_server").callAttr("start", "9980");
        } catch (Throwable t) {
            Log.e(TAG, "扫码绑定服务启动失败", t);
        }
    }

    /** 央视频网关持久化目录（设备状态/回看元数据落盘）。 */
    public static File dataDir() {
        File dir = new File(Init.context().getFilesDir(), "ysp");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Python 侧调用：读取扫码绑定页所在局域网 IPv4；找不到时回退 127.0.0.1。 */
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

    /** Python 侧调用：追加一行运行时日志（取址/拉流失败诊断用）。 */
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
}
