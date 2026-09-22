package com.fongmi.android.tv.live.cctv;

import android.util.Log;

import com.github.catvod.Init;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import okhttp3.OkHttpClient;
import org.json.JSONObject;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 央视频直播 / 回看内核——由 Python 版 {@code live_ysp.py} 的 {@code Spider} 层移植。
 *
 * <p>职责：
 * <ul>
 *   <li>取流：{@link CKeyManager} 生成 cKey → 请求 {@code bkliveinfo.ysp.cctv.cn} 拿 playurl</li>
 *   <li>m3u8 修复：替换易 403 的 CDN 节点、切片相对路径绝对化、统一固定测速最快域名</li>
 *   <li>健康校验：首切片 Range 探测，避免把 403 的流交给播放器（播放器遇 403 会卡死）</li>
 *   <li>缓存：playurl 文件缓存 300s（含失败计数）、m3u8 内容内存缓存 5s</li>
 *   <li>回看：{@code playseek=YYYYMMDDHHMMSS-YYYYMMDDHHMMSS}，失败自动降级到直播</li>
 * </ul>
 *
 * <p><b>移植约定</b>：请求参数、CDN 域名、替换规则逐字保留自 Python 版。
 */
public final class YspSpider {

    private static final String TAG = "YspSpider";

    private static final String API_HOST = "https://bkliveinfo.ysp.cctv.cn";
    private static final String UA_QQ = "qqlive";

    /** 回看 CDN 固定域名（对齐 Python `_process_playback_url`） */
    private static final String PLAYBACK_HOST = "tlivecloud-playback-cdn.ysp.cctv.cn";
    private static final String PLAYBACK_PATH_PREFIX = "/tcloud.cctv.com";

    /** 候选 CDN（测速兜底用；原域名优先） */
    private static final List<String> CDN_CANDIDATES = List.of(
            "hlslive-tx-cdn.ysp.cctv.cn",
            "hlsliveali-cdn.ysp.cctv.cn",
            "outlivecloud-cdn.ysp.cctv.cn");

    /** playurl 缓存有效期（秒） */
    private static final long CACHE_TTL = 300L;
    /** m3u8 内容缓存有效期（秒） */
    private static final long M3U8_CONTENT_CACHE_TTL = 5L;
    /** CDN 测速结果缓存（秒） */
    private static final long PROBE_CACHE_TTL = 30L;
    /** 同一 cacheKey 连续取流失败几次后清掉 playurl 缓存 */
    private static final int FAIL_THRESHOLD = 3;

    private static final String SPVCODE = "MSgzMDoyMTYwLDYwOjIxNjB8MzA6MjE2MCw2MDoyMTYwKTsyKDMwOjIxNjAsNjA6MjE2MHwzMDoyMTYwLDYwOjIxNjAp";
    private static final String APP_VER = "V8.22.1035.3031";

    /**
     * 回看模板：追加式回看参数。
     *
     * <p>{@code Catchup.append()} 在发现原 URL 已带 query 时会把结果里的 {@code ?} 换成
     * {@code &}，因此播放地址 {@code /ysp?fun=cctv&id=xxx} 会拼成
     * {@code /ysp?fun=cctv&id=xxx&playseek=YYYYMMDDHHmmss-YYYYMMDDHHmmss}。
     */
    private static final String CATCHUP_SOURCE = "?playseek=${(b)yyyyMMddHHmmss}-${(e)yyyyMMddHHmmss}";

    /**
     * 回看时间戳下限（秒）。
     *
     * <p>EPG 未加载时，{@code EpgData.startTime} 为 0，{@code Catchup.format()} 会把它
     * 渲染成 {@code 19700101080000}——格式合法但毫无意义。低于此阈值一律视为无效回看，
     * 降级到直播。
     */
    private static final long MIN_PLAYBACK_TIMESTAMP = 1262275200L; // 2010-01-01 00:00:00 UTC

    /** EPG 路径。后缀 {@code .xml} 是硬性要求，见 {@link #liveContent} 的说明。 */
    public static final String EPG_PATH = "/ysp?epg.xml";

    // ------------------------------------------------------------------ 单例

    private static final YspSpider INSTANCE = new YspSpider();

    public static YspSpider get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ 状态

    /** 每个 cacheKey 一把锁，避免并发重复取流 */
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    /** cacheKey -> 连续失败次数 */
    private final Map<String, Integer> failCount = new ConcurrentHashMap<>();
    /** cacheKey -> m3u8 内容（内存缓存） */
    private final Map<String, Object[]> m3u8Cache = new ConcurrentHashMap<>();
    /** CDN 测速结果：[List<String> domains, Long at] */
    private volatile Object[] probeCache;
    private final ReentrantLock probeLock = new ReentrantLock();

    private YspSpider() {
    }

    // ------------------------------------------------------------------ 工具

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** 缓存目录（Android 应用缓存目录，不再用 Python 时代的脚本同级目录）。 */
    private static File cacheDir() {
        try {
            File base = Init.context().getCacheDir();
            File dir = new File(base, "ysp_cache");
            if (!dir.exists() && !dir.mkdirs()) return null;
            return dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String cachePath(String cacheKey) {
        File dir = cacheDir();
        if (dir == null) return null;
        return new File(dir, md5(cacheKey) + ".cache").getAbsolutePath();
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }

    private ReentrantLock lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new ReentrantLock());
    }

    private void log(String msg) {
        Log.i(TAG, msg);
    }

    // ------------------------------------------------------------------ 缓存（playurl）

    /** 读 playurl 缓存；有效返回 [playurl, true]，否则 [null, false]。 */
    private Object[] getCachedPlayurl(String cacheKey) {
        String path = cachePath(cacheKey);
        if (path == null) return new Object[]{null, false};
        File f = new File(path);
        if (!f.exists()) return new Object[]{null, false};
        try {
            String text = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            JSONObject obj = new JSONObject(text);
            String playurl = obj.optString("playurl", "");
            long playurlTime = obj.optLong("playurl_time", 0L);
            if (playurl.isEmpty()) return new Object[]{null, false};
            if (nowSec() - playurlTime >= CACHE_TTL) return new Object[]{null, false};
            int fails = failCount.getOrDefault(cacheKey, 0);
            if (fails < FAIL_THRESHOLD) return new Object[]{playurl, true};
            clearCacheFile(cacheKey);
            return new Object[]{null, false};
        } catch (Throwable t) {
            return new Object[]{null, false};
        }
    }

    /** 写 playurl 缓存（先写 .tmp 再原子改名，避免读到半截文件）。 */
    private void setCachedPlayurl(String cacheKey, String playurl) {
        String path = cachePath(cacheKey);
        if (path == null) return;
        try {
            JSONObject obj = new JSONObject();
            obj.put("playurl", playurl);
            obj.put("playurl_time", nowSec());
            File tmp = new File(path + ".tmp");
            try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(obj.toString());
            }
            File dst = new File(path);
            if (!tmp.renameTo(dst)) {
                // renameTo 在部分机型跨目录失败，退化为覆盖写
                try (Writer w = new OutputStreamWriter(new FileOutputStream(dst), StandardCharsets.UTF_8)) {
                    w.write(obj.toString());
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            failCount.remove(cacheKey);
            cleanCache();
        } catch (Throwable ignored) {
        }
    }

    private void clearCacheFile(String cacheKey) {
        String path = cachePath(cacheKey);
        if (path == null) return;
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(path).delete();
        } catch (Throwable ignored) {
        }
    }

    /** 清理超过 2 倍 CACHE_TTL 未使用的缓存文件。 */
    private void cleanCache() {
        File dir = cacheDir();
        if (dir == null) return;
        try {
            File[] files = dir.listFiles();
            if (files == null) return;
            long cutoff = nowSec() - CACHE_TTL * 2;
            for (File f : files) {
                if (f.isFile() && f.lastModified() / 1000L < cutoff) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 缓存（m3u8 内容）

    private String getCachedM3u8(String cacheKey) {
        Object[] entry = m3u8Cache.get(cacheKey);
        if (entry == null) return null;
        if (nowSec() - ((Long) entry[1]) >= M3U8_CONTENT_CACHE_TTL) return null;
        return (String) entry[0];
    }

    private void setCachedM3u8(String cacheKey, String content) {
        m3u8Cache.put(cacheKey, new Object[]{content, nowSec()});
    }

    // ------------------------------------------------------------------ HTTP

    /** GET 返回 body 文本；非 200/206 或异常返回 null。 */
    private String httpGet(String url, Map<String, String> headers, long timeoutMs) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        OkHttpClient client = OkHttp.client(timeoutMs);
        try (Response res = client.newCall(builder.build()).execute()) {
            int code = res.code();
            ResponseBody body = res.body();
            if (code != 200 && code != 206) return null;
            return body == null ? null : body.string();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 返回 [code, body]；异常 code=-1。 */
    private Object[] httpGetRaw(String url, Map<String, String> headers, long timeoutMs) {
        Request.Builder builder = new Request.Builder().url(url);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        OkHttpClient client = OkHttp.client(timeoutMs);
        try (Response res = client.newCall(builder.build()).execute()) {
            ResponseBody body = res.body();
            return new Object[]{res.code(), body == null ? "" : body.string()};
        } catch (Throwable t) {
            return new Object[]{-1, ""};
        }
    }

    /** 首切片健康校验用的轻量 Range 请求，只判断状态码与是否有内容。 */
    private boolean probeUrl(String url, long timeoutMs) {
        Request req = new Request.Builder().url(url)
                .header("user-agent", UA_QQ)
                .header("Range", "bytes=0-1023")
                .build();
        OkHttpClient client = OkHttp.client(timeoutMs);
        try (Response res = client.newCall(req).execute()) {
            int code = res.code();
            ResponseBody body = res.body();
            if (code != 200 && code != 206) return false;
            // 不整块读，只确认有字节返回，避免拖慢测速
            return body != null && body.source().readByte() != -1;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 取流

    /**
     * 取播放地址。
     *
     * @param playbackTimestamp 非空则走回看，否则走直播
     * @return playurl，失败返回 null
     */
    public String getPlayUrl(String cnlid, String livepid, String defn, Long playbackTimestamp) {
        CKeyManager manager = new CKeyManager();
        long timestamp = playbackTimestamp != null ? playbackTimestamp : nowSec();
        manager.generateGuid();
        CKeyManager.CKeyResult ck = manager.generateCkey(cnlid, timestamp);
        if (ck == null) return null;

        String flowid = generateFlowId();

        java.util.LinkedHashMap<String, String> params = new java.util.LinkedHashMap<>();
        params.put("atime", "120");
        params.put("livepid", livepid);
        params.put("cnlid", cnlid);
        params.put("appVer", APP_VER);
        params.put("app_version", "300090");
        params.put("caplv", "1");
        params.put("cmd", "2");
        params.put("defn", defn);
        params.put("device", "iPhone");
        params.put("encryptVer", "4.2");
        params.put("getpreviewinfo", "0");
        params.put("hevclv", "33");
        params.put("lang", "zh-Hans_JP");
        params.put("livequeue", "0");
        params.put("logintype", "1");
        params.put("nettype", "1");
        params.put("newnettype", "1");
        params.put("newplatform", "4330403");
        params.put("platform", "4330403");
        params.put("sdtfrom", "v3021");
        params.put("spacode", "23");
        params.put("spaudio", "1");
        params.put("spdemuxer", "6");
        params.put("spdrm", "2");
        params.put("spdynamicrange", "7");
        params.put("spflv", "1");
        params.put("spflvaudio", "1");
        params.put("sphdrfps", "60");
        params.put("sphttps", "0");
        params.put("spvcode", SPVCODE);
        params.put("spvideo", "4");
        params.put("stream", "1");
        params.put("system", "1");
        params.put("sysver", "ios18.2.1");
        params.put("uhd_flag", "4");
        params.put("cKey", ck.ckey);
        params.put("guid", manager.getGuid());
        params.put("fntick", String.valueOf(timestamp));
        params.put("flowid", flowid);

        boolean playback = playbackTimestamp != null;
        if (playback) params.put("playbacktime", String.valueOf(playbackTimestamp));

        String playurl = sendRequest(params);
        if (playback && playurl == null) {
            // 回看失败 → 去掉 playbacktime 重试；成功则重写为回看 CDN 地址
            params.remove("playbacktime");
            String retry = sendRequest(params);
            if (retry != null) return processPlaybackUrl(retry, playbackTimestamp);
        }
        return playurl;
    }

    /** 组装请求并解析响应。 */
    private String sendRequest(java.util.LinkedHashMap<String, String> params) {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (q.length() > 0) q.append('&');
            q.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("User-Agent", UA_QQ);
        headers.put("Connection", "Keep-Alive");
        headers.put("Accept", "application/json");
        Object[] r = httpGetRaw(API_HOST + "?" + q, headers, 15000L);
        if (!Integer.valueOf(200).equals(r[0])) return null;
        try {
            JSONObject d = new JSONObject((String) r[1]);
            if (d.optInt("iretcode", -1) != 0) return null;
            String playurl = d.optString("playurl", "");
            return playurl.isEmpty() ? null : playurl;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 回看地址重写：固定 CDN 域名 + 路径前缀 + starttime（对齐 Python `_process_playback_url`）。 */
    static String processPlaybackUrl(String playurl, long playbackTimestamp) {
        try {
            java.net.URI uri = java.net.URI.create(playurl);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
            String url = "https://" + PLAYBACK_HOST + PLAYBACK_PATH_PREFIX + path;
            if (!query.isEmpty()) url += "?" + query;
            url += "&starttime=" + playbackTimestamp;
            return url;
        } catch (Throwable t) {
            return playurl;
        }
    }

    /** flowid：7 段 16 位十六进制，段间用 `-`，末段接 `_4330403`。 */
    private static String generateFlowId() {
        java.util.Random r = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) sb.append('-');
            sb.append(String.format("%04X", r.nextInt(0x10000)));
        }
        sb.append('-');
        for (int i = 0; i < 3; i++) sb.append(String.format("%04X", r.nextInt(0x10000)));
        sb.append("_4330403");
        return sb.toString();
    }

    /** URL 编码：只转义会破坏 query 的字符，保留 base64 里的 `+` `/` `=` 交由 OkHttp 处理。 */
    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ------------------------------------------------------------------ CDN 测速

    /**
     * 并发测速候选 CDN，返回按快慢排序的可用域名（最快在前）。
     *
     * <p>策略对齐 Python 版：<b>先同步测原域名</b>，可用就直接返回（起播最快）；
     * 原域名不可用才并发测全部候选，4s 上限，防止慢流拖死起播。结果缓存 30s，
     * 全失败不缓存（下次重试）。
     */
    private List<String> probeCdns(String sampleUrl) {
        probeLock.lock();
        try {
            Object[] cached = probeCache;
            if (cached != null && nowSec() - ((Long) cached[1]) < PROBE_CACHE_TTL) {
                return (List<String>) cached[0];
            }
            String orig = hostOf(sampleUrl);
            if (orig == null || orig.isEmpty()) return Collections.emptyList();

            List<String> order = new ArrayList<>();
            order.add(orig);
            for (String d : CDN_CANDIDATES) if (!d.equals(orig)) order.add(d);

            List<String> goods = new ArrayList<>();
            if (probeOne(orig, sampleUrl)) {
                goods.add(orig);
            } else {
                ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, order.size()));
                try {
                    List<Future<Object[]>> futures = new ArrayList<>();
                    for (String dom : order) {
                        futures.add(pool.submit((Callable<Object[]>) () -> {
                            long t0 = System.currentTimeMillis();
                            boolean ok = probeOne(dom, sampleUrl);
                            return ok ? new Object[]{System.currentTimeMillis() - t0, dom} : null;
                        }));
                    }
                    long deadline = System.currentTimeMillis() + 4000L;
                    List<Object[]> results = new ArrayList<>();
                    for (Future<Object[]> f : futures) {
                        long remain = deadline - System.currentTimeMillis();
                        if (remain <= 0) break;
                        try {
                            Object[] v = f.get(remain, TimeUnit.MILLISECONDS);
                            if (v != null) results.add(v);
                        } catch (Throwable ignored) {
                        }
                    }
                    results.sort((a, b) -> Long.compare((Long) a[0], (Long) b[0]));
                    for (Object[] v : results) goods.add((String) v[1]);
                } catch (Throwable ignored) {
                } finally {
                    pool.shutdownNow();
                }
            }
            if (!goods.isEmpty()) probeCache = new Object[]{goods, nowSec()};
            return goods;
        } finally {
            probeLock.unlock();
        }
    }

    /** 把 sampleUrl 的 host 换成 dom 后探一次。 */
    private boolean probeOne(String dom, String sampleUrl) {
        String u = replaceHost(sampleUrl, "http://" + dom);
        return u != null && probeUrl(u, 3000L);
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把 URL 的 scheme+host 段整体换成 replacement（对齐 Python `re.sub(r'https?://[^/]+', ...)`）。 */
    static String replaceHost(String url, String replacement) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^https?://[^/]+").matcher(url);
        return m.find() ? m.replaceFirst(java.util.regex.Matcher.quoteReplacement(replacement)) : null;
    }

    /**
     * 智能固定最优 CDN 域名：m3u8 所有切片统一用测速最快且当前可用的域名。
     *
     * <p>原因：播放器遇到 403 <b>不会重试</b>，固定刚测速 200 的域名让全批切片大概率 200，
     * 避免个别切片 403 卡死。探测失败则原样返回。
     */
    private String smartDomains(String content) {
        try {
            List<String> tsLines = new ArrayList<>();
            for (String line : content.split("\n")) {
                String t = line.trim();
                if (t.startsWith("http") && t.contains(".ts")) tsLines.add(t);
            }
            if (tsLines.isEmpty()) return content;
            List<String> goods = probeCdns(tsLines.get(tsLines.size() - 1));
            if (goods.isEmpty()) return content;
            String best = goods.get(0);
            return content.replaceAll("(https?://)[^/\\s]+(?=/)", "$1" + java.util.regex.Matcher.quoteReplacement(best));
        } catch (Throwable t) {
            return content;
        }
    }

    // ------------------------------------------------------------------ m3u8 获取与修复

    /**
     * 获取 m3u8 并修复：
     * <ol>
     *   <li>先替换易 403 的 CDN 节点（对齐 PHP 实现）</li>
     *   <li>切片相对路径转绝对路径（不附加任何查询参数）</li>
     *   <li>再次替换易 403 节点</li>
     *   <li>{@link #smartDomains} 固定最快域名</li>
     * </ol>
     *
     * @return 修复后的 m3u8 文本，失败返回 null
     */
    private String fetchAndFixM3u8(String playUrl) {
        try {
            String url = playUrl
                    .replaceAll("//mobilelive-[^.]+\\.ysp\\.cctv\\.cn", "//mobilelive-cnc-cdn.ysp.cctv.cn")
                    .replace("//outlivecloud-cdn.ysp.cctv.cn", "//hlsliveali-cdn.ysp.cctv.cn");
            Map<String, String> headers = new java.util.LinkedHashMap<>();
            headers.put("connection", "Keep-Alive");
            headers.put("Range", "bytes=0-");
            headers.put("accept-encoding", "gzip");
            headers.put("user-agent", UA_QQ);
            headers.put("Accept-Language", "zh-CN,zh;q=0.9");
            String content = httpGet(url, headers, 8000L);
            if (content == null || !content.contains("#EXTM3U")) return null;

            String base = baseOf(url);
            List<String> fixedLines = new ArrayList<>();
            for (String line : content.replace("\r", "").split("\n")) {
                String stripped = line.trim();
                if (!stripped.isEmpty() && !stripped.startsWith("#") && stripped.contains(".ts")) {
                    fixedLines.add(resolve(base, stripped));
                } else {
                    fixedLines.add(line);
                }
            }
            String fixed = String.join("\n", fixedLines);

            fixed = fixed.replaceAll("mobilelive-[^.]+\\.ysp\\.cctv\\.cn", "mobilelive-cnc-cdn.ysp.cctv.cn");
            fixed = fixed.replace("outlivecloud-cdn.ysp.cctv.cn", "hlsliveali-cdn.ysp.cctv.cn");
            return smartDomains(fixed);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * m3u8 所在目录作为基准 URL（对齐 Python 的
     * {@code f"{scheme}://{netloc}{path[:path.rfind('/')+1]}"}）。
     *
     * <p>用 URI 规范化：去 query/fragment，路径按最后一个 `/` 截断。
     */
    static String baseOf(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            int slash = path.lastIndexOf('/');
            String dir = slash >= 0 ? path.substring(0, slash + 1) : "/";
            String authority = uri.getRawAuthority() == null ? "" : uri.getRawAuthority();
            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            return java.net.URI.create(scheme + "://" + authority + dir).normalize().toString();
        } catch (Throwable t) {
            return url;
        }
    }

    /**
     * 把切片相对路径解析成绝对 URL（对齐 Python {@code urljoin(base, stripped)}）。
     *
     * <p>必须用 URI.resolve 而非字符串拼接：相对路径可能含 {@code ../} / {@code ./}，
     * 字符串拼接会把 {@code ../} 原样留在 URL 里导致切片 404；
     * 绝对路径（以 {@code /} 开头）拼接还会产生双斜杠 {@code //sub/1.ts}。
     */
    static String resolve(String base, String line) {
        if (line.startsWith("http://") || line.startsWith("https://")) return line;
        try {
            return java.net.URI.create(base).resolve(line).toString();
        } catch (Throwable t) {
            return line;
        }
    }

    /**
     * 用与播放器一致的 Range 请求验证首个切片：坏流（403/连接失败）返回 false。
     *
     * <p>确保交给播放器的流当前可拉，避免播放器遇 403 卡死。
     */
    private boolean verifyFirstTs(String m3u8Content) {
        try {
            for (String line : m3u8Content.split("\n")) {
                String u = line.trim();
                if (u.startsWith("http") && u.contains(".ts")) return probeUrl(u, 3000L);
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------ 直播 / 回看

    /** 频道直播：返回 [status, mime, body]。 */
    public Object[] live(String channelId) {
        YspChannels.Channel ch = YspChannels.get(channelId);
        if (ch == null) return error("频道 " + channelId + " 不存在");
        return handleLive(channelId, ch);
    }

    /** 频道回看：返回 [status, mime, body]。 */
    public Object[] playback(String channelId, String playseek) {
        YspChannels.Channel ch = YspChannels.get(channelId);
        if (ch == null) return error("频道 " + channelId + " 不存在");
        return handlePlayback(channelId, ch, playseek);
    }

    /** 直播取流（最多 4 个候选 playurl 轮换）。 */
    private Object[] handleLive(String channelId, YspChannels.Channel ch) {
        ReentrantLock lock = lockFor(channelId);
        lock.lock();
        try {
            String cachedM3u8 = getCachedM3u8(channelId);
            if (cachedM3u8 != null) return ok(cachedM3u8);

            List<String> candidates = new ArrayList<>();
            Object[] cached = getCachedPlayurl(channelId);
            if (Boolean.TRUE.equals(cached[1]) && cached[0] != null) candidates.add((String) cached[0]);

            String m3u8 = null;
            String used = null;
            String firstError = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                if (attempt >= candidates.size()) {
                    String np = getPlayUrl(ch.cnlid, ch.livepid, ch.defn, null);
                    if (np != null) candidates.add(np);
                    else if (firstError == null) firstError = "取流接口无返回";
                }
                String pl = attempt < candidates.size() ? candidates.get(attempt) : null;
                if (pl == null) {
                    sleep(300);
                    continue;
                }
                String mc = fetchAndFixM3u8(pl);
                if (mc != null && verifyFirstTs(mc)) {
                    m3u8 = mc;
                    used = pl;
                    break;
                }
                if (firstError == null) firstError = mc == null ? "m3u8 拉取失败" : "首切片不可用";
                sleep(300);
            }
            if (m3u8 == null) return error("获取 m3u8 失败" + (firstError == null ? "" : "（" + firstError + "）"));

            setCachedPlayurl(channelId, used);
            setCachedM3u8(channelId, m3u8);
            return ok(m3u8);
        } catch (Throwable t) {
            log("直播处理异常 " + channelId + ": " + t);
            return error("内部错误");
        } finally {
            lock.unlock();
        }
    }

    /** 回看取流；失败降级到直播（对齐 Python `_get_live_fallback`）。 */
    private Object[] handlePlayback(String channelId, YspChannels.Channel ch, String playseek) {
        try {
            String[] parts = playseek.split("-");
            if (parts.length != 2) return error("回看参数格式错误");
            SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.US);
            fmt.setLenient(false);
            Date start;
            try {
                start = fmt.parse(parts[0]);
            } catch (Exception e) {
                return error("回看时间格式错误");
            }
            if (start == null) return error("回看时间解析失败");
            long playbackTimestamp = start.getTime() / 1000L;

            // 时间合理性校验：EPG 缺失时 Catchup 会把 startTime=0 格式化成 19700101080000，
            // 这个「时间戳」格式完全合法却能骗过上面的解析，若放行则必然拉不到录像。
            // 这里直接降级到直播，用户体验优于返回 500。
            if (playbackTimestamp < MIN_PLAYBACK_TIMESTAMP) {
                log("回看时间戳异常(" + parts[0] + ")，降级到直播: " + channelId);
                return liveFallback(ch, channelId);
            }

            String cacheKey = channelId + "_" + playbackTimestamp;
            ReentrantLock lock = lockFor(cacheKey);
            lock.lock();
            try {
                String cachedM3u8 = getCachedM3u8(cacheKey);
                if (cachedM3u8 != null) return ok(cachedM3u8);

                Object[] cached = getCachedPlayurl(cacheKey);
                if (Boolean.TRUE.equals(cached[1]) && cached[0] != null) {
                    String playurl = (String) cached[0];
                    for (int attempt = 0; attempt < 3; attempt++) {
                        String mc = fetchAndFixM3u8(playurl);
                        if (mc != null) {
                            failCount.remove(cacheKey);
                            setCachedM3u8(cacheKey, mc);
                            return ok(mc);
                        }
                        int fails = failCount.merge(cacheKey, 1, Integer::sum);
                        if (fails >= FAIL_THRESHOLD) {
                            clearCacheFile(cacheKey);
                            failCount.remove(cacheKey);
                            break;
                        }
                        sleep(200);
                    }
                    // 降级：拿旧 m3u8 兜底
                    String old = getCachedM3u8(cacheKey);
                    if (old != null) return ok(old);
                    clearCacheFile(cacheKey);
                }

                String newPlayurl = getPlayUrl(ch.cnlid, ch.livepid, ch.defn, playbackTimestamp);
                if (newPlayurl == null) return liveFallback(ch, channelId);
                String mc = fetchAndFixM3u8(newPlayurl);
                if (mc == null) return liveFallback(ch, channelId);
                setCachedPlayurl(cacheKey, newPlayurl);
                setCachedM3u8(cacheKey, mc);
                return ok(mc);
            } finally {
                lock.unlock();
            }
        } catch (Throwable t) {
            log("回看处理异常 " + channelId + ": " + t);
            return error("回看处理失败");
        }
    }

    /** 回看不可用时降级到该频道的直播。 */
    private Object[] liveFallback(YspChannels.Channel ch, String fallbackId) {
        String id = YspChannels.findIdByCnlid(ch.cnlid);
        if (id == null) id = fallbackId;
        return handleLive(id, ch);
    }

    private static Object[] ok(String m3u8) {
        return new Object[]{200, "application/vnd.apple.mpegurl", m3u8};
    }

    static Object[] error(String msg) {
        String body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-TARGETDURATION:10\n#EXTINF:10.0,\nerror.ts\n"
                + "#EXT-X-ENDLIST\n# " + msg;
        return new Object[]{500, "application/vnd.apple.mpegurl", body};
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ 列表

    /**
     * 生成央视频 M3U 列表。
     *
     * <p><b>必须输出 M3U 而非 TXT</b>：项目的 {@code Live.catchup} 带 {@code @Ignore}
     * 注解、永不从配置反序列化，catchup 只能逐频道从 {@code #EXTINF} 属性获得。
     * 这里用 {@code catchup="append"} 让回看参数追加到播放地址，配合
     * {@code Catchup.append()} 的 `?`→`&` 替换，最终得到
     * {@code /ysp?fun=cctv&id=xxx&playseek=...}。
     *
     * <p><b>tvg-url 不可或缺，且路径必须含 "xml"</b>：{@code Catchup} 渲染 playseek 时取的是
     * {@code EpgData.startTime/endTime}，没有 EPG 就只会拼出 1970 年的垃圾时间戳。
     * 这里把 EPG 地址写进 {@code #EXTM3U} 头，播放器会自行拉取并回填节目单，回看才真正可用。
     *
     * <p>注意 {@code Live.getEpgXml()} 的过滤条件是
     * {@code !url.contains("{") && (url.contains("xml") || url.contains("gz"))}，
     * 因此 EPG 路径<b>必须带 {@code .xml} 后缀</b>，否则会被静默忽略、回看照样失效。
     *
     * @param base 本机服务器地址（端口动态，勿硬编码）
     */
    public String liveContent(String base) {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U tvg-url=\"").append(base).append(EPG_PATH).append("\"\n");
        for (Map.Entry<String, List<String>> entry : YspChannels.groups().entrySet()) {
            String group = entry.getKey();
            sb.append("\n#  ").append(group).append("\n");
            for (String id : entry.getValue()) {
                YspChannels.Channel ch = YspChannels.get(id);
                if (ch == null) continue;
                sb.append("#EXTINF:-1 tvg-id=\"").append(ch.name)
                        .append("\" tvg-name=\"").append(ch.name)
                        .append("\" group-title=\"").append(group)
                        .append("\" catchup=\"append\"")
                        .append(" catchup-source=\"").append(CATCHUP_SOURCE).append("\",")
                        .append(ch.name).append('\n');
                sb.append(base).append("/ysp?fun=cctv&id=").append(id).append('\n');
            }
        }
        return sb.toString();
    }

    /** 直播源名称（对齐 Python `getName()`）。 */
    public String getName() {
        return "央视频（直播+回看）";
    }
}
