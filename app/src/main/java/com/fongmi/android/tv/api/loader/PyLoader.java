package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Python 爬虫加载器。
 *
 * <p>基于 Chaquopy 15.0.1 内置的 Python 3.11 解释器。Chaquopy 15.0.1 配套的
 * CPython 3.11.6 原生库不依赖 {@code __register_atfork} 等 API 23 才引入的
 * libc 符号，因此可在 minSdk 21（Android 5.0）设备上正常加载运行。
 *
 * <p>解释器为<b>惰性启动</b>：只有真正出现 {@code .py} 站源时才初始化，
 * 避免拖慢 App 启动。初始化失败（设备缺少对应 ABI 原生库等）时会记录日志并
 * 降级为 {@link SpiderNull}，与原实现加载失败时的降级路径一致，
 * 不会影响 JS / JAR(csp_) 爬虫。
 */
public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private volatile Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    private Loader loader() {
        Loader local = loader;
        if (local == null) {
            synchronized (this) {
                local = loader;
                if (local == null) {
                    local = new Loader();
                    loader = local;
                }
            }
        }
        return local;
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext) {
        return spiders.computeIfAbsent(key, k -> {
            try {
                Spider spider = loader().spider(api);
                spider.siteKey = key;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                e.printStackTrace();
                return new SpiderNull();
            }
        });
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
