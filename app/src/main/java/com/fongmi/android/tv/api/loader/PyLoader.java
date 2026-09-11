package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.proxy.ProxySubscriptionManager;
import com.fongmi.chaquo.Loader;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PyLoader {

    // 内置央视频 py 的固定 spider key：默认合并列表源（/ysp?list=live）不经过 setRecent，
    // /proxy?do=py&fun=cctv 播放时 recent 可能为 null，必须自举加载内置 live_ysp。
    private static final String YSP_KEY = "ysp_builtin";

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
                spider.init(App.get(), ProxySubscriptionManager.get().mergeExt(ext));
                return spider;
            } catch (Throwable e) {
                e.printStackTrace();
                return new SpiderNull();
            }
        });
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        // 内置央视频直播（/proxy?do=py&fun=cctv&id=xxx）：强制用内置 live_ysp，不依赖 recent。
        // app.py 的 spider() 支持纯模块名加载（import live_ysp → Spider()），首次懒加载并缓存。
        if ("cctv".equals(params.get("fun"))) {
            Spider spider = getSpider(YSP_KEY, "live_ysp", "");
            if (spider == null || spider instanceof SpiderNull) return new Object[]{404, "text/plain", new java.io.ByteArrayInputStream("ysp spider not loaded".getBytes()), null};
            return spider.proxy(params);
        }
        if (recent == null) return new Object[]{404, "text/plain", new java.io.ByteArrayInputStream("py spider not loaded".getBytes()), null};
        Spider spider = spiders.get(recent);
        if (spider == null) return new Object[]{404, "text/plain", new java.io.ByteArrayInputStream("py spider not found".getBytes()), null};
        return spider.proxy(params);
    }
}
