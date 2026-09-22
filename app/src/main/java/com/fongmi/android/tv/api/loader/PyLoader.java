package com.fongmi.android.tv.api.loader;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Python 爬虫加载器（已停用）。
 *
 * <p>本类保留原有公开 API，但不再使用 Chaquopy 解释器。原因：Chaquopy 全栈
 * （libpython3.10.so 及全部 C 扩展）依赖 {@code __register_atfork}，该符号自
 * Android 6.0（API 23）起才由 bionic 提供，在 Android 5.0/5.1（API 21/22）上
 * 加载必然失败。为把 minSdk 降到 21，此处改为空实现。
 *
 * <p>调用方行为：{@link #getSpider} 返回 {@link SpiderNull}（与原实现加载失败时
 * 的降级路径完全一致），{@link #proxy} 返回 404。JS / JAR(csp_) 爬虫不受影响。
 */
public class PyLoader {

    private final ConcurrentHashMap<String, Spider> spiders;

    public PyLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    public void clear() {
        spiders.clear();
    }

    public void setRecent(String recent) {
        // Python 爬虫已停用，无需记录最近使用。
    }

    public Spider getSpider(String key, String api, String ext) {
        return new SpiderNull();
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        return new Object[]{404, "text/plain", new ByteArrayInputStream("py spider not supported on this build".getBytes()), null};
    }
}
