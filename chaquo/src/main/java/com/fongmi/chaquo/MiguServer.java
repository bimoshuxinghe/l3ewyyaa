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

    /** 幂等启动：主线程一次，失败转后台重试 2 次（间隔 3s）。 */
    public static synchronized void start() {
        if (started) return;
        doStart();
        if (!started) {
            new Thread(() -> {
                for (int i = 0; i < 2 && !started; i++) {
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                    }
                    doStart();
                }
            }, "migu-server-retry").start();
        }
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
