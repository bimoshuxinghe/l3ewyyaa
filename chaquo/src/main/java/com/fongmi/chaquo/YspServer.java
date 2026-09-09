package com.fongmi.chaquo;

import android.util.Log;

import com.chaquo.python.Python;

/**
 * YSP 央视频代理（Python 版）启动钩子。
 * 应用启动后异步调用：Chaquopy 初始化 → import ysp_server → start()（内部起 127.0.0.1:9979 HTTP 服务）。
 * 播放器直播源地址：http://127.0.0.1:9979/ysp?list=live
 */
public class YspServer {

    private static final String TAG = "YspServer";
    private static volatile boolean started = false;

    private YspServer() {
    }

    /** 幂等启动（线程安全）：Python 首次初始化较慢（1~3s），应在后台线程调用。 */
    public static synchronized void start() {
        if (started) return;
        try {
            if (!Python.isStarted()) Python.start(Platform.create());
            Python.getInstance().getModule("ysp_server").callAttr("start");
            started = true;
            Log.i(TAG, "YSP Python 代理已启动 (http://127.0.0.1:9979/ysp?list=live)");
        } catch (Throwable t) {
            Log.e(TAG, "YSP Python 代理启动失败", t);
        }
    }
}
