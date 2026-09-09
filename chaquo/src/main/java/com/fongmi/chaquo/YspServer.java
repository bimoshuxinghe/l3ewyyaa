package com.fongmi.chaquo;

import android.util.Log;

import com.chaquo.python.Python;
import com.github.catvod.Init;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * YSP 央视频代理（Python 版）启动钩子。
 * 应用 onCreate 主线程调用 start()：Chaquopy 初始化 → import ysp_server → 启动 127.0.0.1:9979 HTTP 服务。
 * 失败自动后台重试（Chaquopy 首次初始化需解压 assets，慢设备可达数秒~十几秒）。
 * 启动状态写入 files/ysp_status.txt，便于诊断。
 * 播放器直播源地址：http://127.0.0.1:9979/ysp?list=live
 */
public class YspServer {

    private static final String TAG = "YspServer";
    private static volatile boolean started = false;

    private YspServer() {
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
            }, "ysp-server-retry").start();
        }
    }

    private static void doStart() {
        try {
            if (!Python.isStarted()) Python.start(Platform.create());
            Python.getInstance().getModule("ysp_server").callAttr("start");
            started = true;
            writeStatus("OK: YSP Python server http://127.0.0.1:9979/ysp?list=live");
            Log.i(TAG, "YSP Python 代理已启动 (9979)");
        } catch (Throwable t) {
            writeStatus("FAIL: " + t);
            Log.e(TAG, "YSP Python 代理启动失败", t);
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
