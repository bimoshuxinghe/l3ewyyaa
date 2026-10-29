package com.fongmi.android.tv.api.loader;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Python 爬虫加载器。
 *
 * 关键改动：Chaquopy 不再在构造时启动。以前 BaseLoader 单例初始化时会同步
 * 执行 Python.start()，一旦解释器起不来（资产缺失、ABI 不匹配、loadLibrary
 * 失败等），BaseLoader 类初始化直接失败，连带配置解析中断 —— 表现为"打开
 * 软件没有任何数据"，连 JS / JAR 爬虫和首页分类都出不来。
 *
 * 现在改为首次用到 py 站源时才启动，启动失败只影响 py 站源（降级为空爬虫），
 * 并把原因打到 logcat。
 */
public class PyLoader {

    private static final String TAG = "PyLoader";
    private static final int MAX_RETRY = 3;

    private final ConcurrentHashMap<String, Spider> spiders;
    private volatile Loader loader;
    private volatile int retry;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    private Loader loader() {
        if (loader != null) return loader;
        synchronized (this) {
            if (loader != null) return loader;
            if (retry >= MAX_RETRY) return null;
            try {
                loader = new Loader();
                Log.i(TAG, "python started");
            } catch (Throwable e) {
                retry++;
                Log.e(TAG, "python start failed (" + retry + "/" + MAX_RETRY + ")", e);
                report("python start failed", e);
            }
            return loader;
        }
    }

    public void clear() {
        if (loader != null) spiders.values().forEach(Spider::destroy);
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        return spiders.computeIfAbsent(key, k -> {
            Loader local = loader();
            if (local == null) return new SpiderNull();
            try {
                Spider spider = local.spider(api);
                spider.siteKey = key;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                // 站源加载失败时降级为空爬虫；把原因打到 logcat，方便定位
                // （例如站源缺少标准接口、依赖模块不存在等）。
                Log.e(TAG, "py load failed, key=" + key + " api=" + api, e);
                report("py load failed: " + api, e);
                return new SpiderNull();
            }
        });
    }

    /**
     * 出错时除了 logcat，也把堆栈写到 /sdcard/TV/py_error.log 并弹一次 Toast，
     * 方便在没有 adb 的情况下拿到失败原因。
     */
    private void report(String title, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        String text = time + " " + title + "\n" + sw;
        try {
            File dir = Path.tv();
            if (dir != null && (dir.exists() || dir.mkdirs())) {
                try (FileOutputStream fos = new FileOutputStream(new File(dir, "py_error.log"), true)) {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(App.get(), "Python: " + title + " - " + reason, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
