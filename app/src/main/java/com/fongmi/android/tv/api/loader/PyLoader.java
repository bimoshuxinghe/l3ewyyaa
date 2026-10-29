package com.fongmi.android.tv.api.loader;

import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Python 爬虫加载器，行为与 fix/danmaku-fongmi-port 分支保持一致：
 * 构造时即启动 Chaquopy（官方 17.0.0 插件、Python 3.10 解释器），
 * 单个站源加载失败时降级为 {@link SpiderNull}，不影响 JS / JAR(csp_) 爬虫。
 */
public class PyLoader {

    private static final String TAG = "PyLoader";

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
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
                Spider spider = loader.spider(api);
                spider.siteKey = key;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                // 站源加载失败时降级为空爬虫；把原因打到 logcat，方便定位
                // （例如站源缺少标准接口、依赖模块不存在等）。
                Log.e(TAG, "py load failed, key=" + key + " api=" + api, e);
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
