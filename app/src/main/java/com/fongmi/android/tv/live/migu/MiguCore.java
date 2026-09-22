package com.fongmi.android.tv.live.migu;

import android.util.Log;

import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.Init;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 咪咕直播内核——由 Python 版 {@code migu_server.py} 的 {@code MiguCore} 移植。
 *
 * <p>对齐 github.com/develop202/migu_video（Node 版）核心逻辑：
 * <ul>
 *   <li>频道列表：咪咕 tv-data 接口按分类拉取（央视/卫视/地方/体育…各自分组，不做大杂烩）</li>
 *   <li>取流：Android 720p 签名（md5 + sign + salt）+ ddCalcu 纯字符变换（无需 wasm）</li>
 *   <li>播放：302 重定向到咪咕 CDN（播放器直连，与原项目一致）</li>
 *   <li>缓存：频道列表 6h / 取流 URL 3h（对齐原项目）</li>
 * </ul>
 *
 * <p><b>移植约定</b>：所有签名常量（盐值、前缀、截取位数）逐字保留，不做"优化"，
 * 否则咪咕服务端会拒绝请求。
 */
public final class MiguCore {

    private static final String TAG = "MiguCore";

    private static final String UA = "okhttp/3.12.1";
    private static final String APP_VERSION = "2600034600";
    private static final String CHANNEL_ID_PREFIX = APP_VERSION + "-99000-201600010010028";
    private static final String API_CATE = "https://program-sc.miguvideo.com/live/v2/tv-data/%s";
    private static final String API_PLAY = "https://play.miguvideo.com/playurl/v1/play/playurl";

    /** 任意 vomsID 返回全部分类 liveList */
    private static final String CATE_VOMS = "1ff892f2b5ab4a79be6e25b69d2f5d05";
    private static final long CATE_CACHE_SEC = 6 * 3600L;
    private static final long URL_CACHE_SEC = 3 * 3600L;

    /** 未登录 720p 签名盐（对齐 Python 版，勿改） */
    private static final String SALT_720P = "2cac4f2c6c3346a5b34e085725ef7e33migu";
    /** 登录 1080p 签名盐（对齐 Python 版，勿改） */
    private static final String SALT_LOGIN = "3ce941cc3cbc40528bfd1c64f9fdf6c0migu0123";
    /** ddCalcu 字符表（对齐 Python 版，勿改） */
    private static final String DD_KEYS = "cdabyzwxkl";

    /** 这两个频道开 flv 后不能回放，原项目不加 appCode */
    private static final List<String> NO_APP_CODE = List.of("641886683", "641886773");

    // ------------------------------------------------------------------ 单例

    private static final MiguCore INSTANCE = new MiguCore();

    public static MiguCore get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ 状态

    /** 频道分类缓存：[[name, vomsID, [Chan…]], …] */
    private volatile List<Cate> catesCache;
    private volatile long catesTs;

    /** 取流 URL 缓存：pid（或 pid:uid） -> [playurl, ts] */
    private final Map<String, Object[]> urlCache = new ConcurrentHashMap<>();

    private final Random rng = new Random();

    private MiguCore() {
    }

    // ------------------------------------------------------------------ 数据结构

    /** 一个直播频道。 */
    public static final class Chan {
        public final String name;
        public final String pid;
        public final String logo;

        Chan(String name, String pid, String logo) {
            this.name = name;
            this.pid = pid;
            this.logo = logo;
        }
    }

    /** 一个分类及其频道列表。 */
    public static final class Cate {
        public final String name;
        public final String vomsId;
        public final List<Chan> chans;

        Cate(String name, String vomsId, List<Chan> chans) {
            this.name = name;
            this.vomsId = vomsId;
            this.chans = chans;
        }
    }

    // ------------------------------------------------------------------ 工具

    /** md5 小写十六进制（对齐 Python hashlib.md5(...).hexdigest()）。 */
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

    /** HTTP GET，返回 [statusCode, body]；失败返回 [-1, ""]。 */
    private String[] httpGet(String url, long timeoutMs, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url).header("User-Agent", UA);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        OkHttpClient client = OkHttp.client(timeoutMs);
        try (Response res = client.newCall(builder.build()).execute()) {
            ResponseBody body = res.body();
            return new String[]{String.valueOf(res.code()), body == null ? "" : body.string()};
        } catch (Throwable t) {
            Log.w(TAG, "httpGet 失败: " + url + " -> " + t);
            return new String[]{"-1", ""};
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000L;
    }

    // ------------------------------------------------------------------ 频道数据

    /** 拉全部分类及频道；失败返回 null。 */
    private List<Cate> fetchCates() {
        String[] first = httpGet(String.format(API_CATE, CATE_VOMS), 12000L, null);
        if (!"200".equals(first[0])) {
            log("MIGU频道接口失败 http=" + first[0]);
            return null;
        }
        List<Cate> out = new ArrayList<>();
        try {
            JSONObject d = new JSONObject(first[1]);
            JSONObject body = d.optJSONObject("body");
            JSONArray liveList = body == null ? null : body.optJSONArray("liveList");
            if (liveList == null) return null;
            for (int i = 0; i < liveList.length(); i++) {
                JSONObject c = liveList.optJSONObject(i);
                if (c == null) continue;
                String name = c.optString("name", "");
                if (name.isEmpty() || "热门".equals(name)) continue;
                out.add(new Cate(name, c.optString("vomsID", ""), new ArrayList<>()));
            }
        } catch (Throwable t) {
            log("MIGU频道接口解析失败 " + t);
            return null;
        }
        // 每个分类拉频道（保留接口顺序：央视/卫视/地方/体育…）
        for (int i = 0; i < out.size(); i++) {
            Cate cate = out.get(i);
            String[] r = httpGet(String.format(API_CATE, cate.vomsId), 12000L, null);
            if ("200".equals(r[0])) {
                try {
                    JSONObject d2 = new JSONObject(r[1]);
                    JSONObject body2 = d2.optJSONObject("body");
                    JSONArray dataList = body2 == null ? null : body2.optJSONArray("dataList");
                    List<Chan> chans = new ArrayList<>();
                    if (dataList != null) {
                        for (int j = 0; j < dataList.length(); j++) {
                            JSONObject x = dataList.optJSONObject(j);
                            if (x == null) continue;
                            JSONObject pics = x.optJSONObject("pics");
                            chans.add(new Chan(
                                    x.optString("name", ""),
                                    String.valueOf(x.opt("pID") == null ? "" : x.opt("pID")),
                                    pics == null ? "" : pics.optString("highResolutionH", "")));
                        }
                    }
                    out.set(i, new Cate(cate.name, cate.vomsId, chans));
                } catch (Throwable ignored) {
                }
            }
            sleep(50);
        }
        return out;
    }

    /** 分类（6h 缓存）。 */
    public List<Cate> getCates() {
        long t = now();
        List<Cate> cached = catesCache;
        if (cached != null && t - catesTs < CATE_CACHE_SEC) return cached;
        List<Cate> c = fetchCates();
        if (c != null && !c.isEmpty()) {
            catesCache = c;
            catesTs = t;
        }
        List<Cate> result = catesCache;
        return result == null ? Collections.emptyList() : result;
    }

    // ------------------------------------------------------------------ 取流

    /** 读 Java 侧保存的咪咕账号：{uid, token}，无账号返回 {"", ""}。 */
    private String[] getAccount() {
        try {
            String uid = Prefers.getString("migu_uid", "");
            String token = Prefers.getString("migu_token", "");
            if (!uid.isEmpty() && !token.isEmpty()) return new String[]{uid, token};
        } catch (Throwable ignored) {
        }
        return new String[]{"", ""};
    }

    /** 有账号 → 登录取流(1080p蓝光, 非会员自动降级)；无账号 → 免费720p。缓存 3h。 */
    public String playUrl(String pid) {
        String[] acc = getAccount();
        if (!acc[0].isEmpty() && !acc[1].isEmpty()) return playUrlLogin(pid, acc[0], acc[1]);
        return playUrl720p(pid);
    }

    /** 未登录 720p 取流（对齐 getAndroidURL720p + getddCalcuURL720p），缓存 3h。 */
    private String playUrl720p(String pid) {
        long t = now();
        Object[] cached = urlCache.get(pid);
        if (cached != null && t - ((Long) cached[1]) < URL_CACHE_SEC) return (String) cached[0];

        String ts = String.valueOf(System.currentTimeMillis());
        String clientId = md5(String.valueOf(System.currentTimeMillis()));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("AppVersion", APP_VERSION);
        headers.put("TerminalId", "android");
        headers.put("X-UP-CLIENT-CHANNEL-ID", CHANNEL_ID_PREFIX);
        headers.put("ClientId", clientId);
        if (!NO_APP_CODE.contains(pid)) headers.put("appCode", "miguvideo_default_android");

        String m = md5(ts + pid + APP_VERSION.substring(0, 8));
        String salt = String.format(Locale.US, "%06d", rng.nextInt(1000000)) + "25";
        String sign = md5(m + SALT_720P + salt.substring(0, 4));
        String qs = "sign=" + sign + "&rateType=3&contId=" + pid + "&timestamp=" + ts + "&salt=" + salt
                + "&flvEnable=true&super4k=true&h265N=true";

        String[] r = httpGet(API_PLAY + "?" + qs, 12000L, headers);
        if (!"200".equals(r[0])) {
            log("MIGU取流失败 " + pid + " http=" + r[0]);
            return null;
        }
        String purl = extractUrl(r[1]);
        if (purl == null) {
            log("MIGU取流无url " + pid + " " + extractRid(r[1]));
            return null;
        }
        purl = purl + "&ddCalcu=" + ddCalcu720p(purl, pid) + "&sv=10004&ct=android";
        urlCache.put(pid, new Object[]{purl, t});
        return purl;
    }

    /** 登录取流（对齐 getAndroidURL + getddCalcu）：蓝光1080p，非会员自动降级。 */
    private String playUrlLogin(String pid, String userId, String token) {
        long t = now();
        String key = pid + ":" + userId;
        Object[] cached = urlCache.get(key);
        if (cached != null && t - ((Long) cached[1]) < URL_CACHE_SEC) return (String) cached[0];

        String ts = String.valueOf(System.currentTimeMillis());
        String appVersion = "2600037000";
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("AppVersion", appVersion);
        headers.put("TerminalId", "android");
        headers.put("X-UP-CLIENT-CHANNEL-ID", "2600037000-99000-200300220100002");
        headers.put("UserId", userId);
        headers.put("UserToken", token);
        if (!NO_APP_CODE.contains(pid)) headers.put("appCode", "miguvideo_default_android");

        String m = md5(ts + pid + appVersion);
        String salt = "1230024";
        String sign = md5(m + SALT_LOGIN);

        JSONObject d = requestLogin(headers, sign, "4", pid, ts, salt);
        if (d == null) {
            log("MIGU登录取流失败 " + pid);
            return null;
        }
        // 非会员降级（对齐原项目 TIPS_NEED_MEMBER 三级）
        if ("TIPS_NEED_MEMBER".equals(d.optString("rid", ""))) {
            JSONObject urlInfo = optUrlInfo(d);
            int rt = urlInfo == null ? 0 : urlInfo.optInt("rateType", 0);
            String rt2 = rt > 4 ? "4" : "3";
            JSONObject d2 = requestLogin(headers, sign, rt2, pid, ts, salt);
            if (d2 != null && "TIPS_NEED_MEMBER".equals(d2.optString("rid", ""))) d2 = requestLogin(headers, sign, "3", pid, ts, salt);
            if (d2 != null) d = d2;
        }
        String purl = extractUrl(d);
        if (purl == null) {
            log("MIGU登录取流无url " + pid + " " + extractRid(d));
            return null;
        }
        purl = purl + "&ddCalcu=" + ddCalcu(purl, pid, 4, userId) + "&sv=10004&ct=android";
        urlCache.put(key, new Object[]{purl, t});
        return purl;
    }

    /** 登录取流单次请求；失败返回 null。rt 为 rateType 字符串。 */
    private JSONObject requestLogin(Map<String, String> headers, String sign, String rt, String pid, String ts, String salt) {
        String qs = "sign=" + sign + "&rateType=" + rt + "&contId=" + pid + "&timestamp=" + ts + "&salt=" + salt
                + "&flvEnable=true&super4k=true";
        if ("9".equals(rt)) qs += "&ott=true";
        qs += "&h265N=true&4kvivid=true&2Kvivid=true&vivid=2";
        String[] r = httpGet(API_PLAY + "?" + qs, 12000L, headers);
        if (!"200".equals(r[0])) return null;
        try {
            return new JSONObject(r[1]);
        } catch (Throwable e) {
            return null;
        }
    }

    private static JSONObject optUrlInfo(JSONObject d) {
        JSONObject body = d.optJSONObject("body");
        return body == null ? null : body.optJSONObject("urlInfo");
    }

    private static String extractUrl(String json) {
        try {
            JSONObject urlInfo = optUrlInfo(new JSONObject(json));
            if (urlInfo == null) return null;
            String u = urlInfo.optString("url", "");
            return u.isEmpty() ? null : u;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractUrl(JSONObject d) {
        JSONObject urlInfo = optUrlInfo(d);
        if (urlInfo == null) return null;
        String u = urlInfo.optString("url", "");
        return u.isEmpty() ? null : u;
    }

    private static String extractRid(String json) {
        try {
            return extractRid(new JSONObject(json));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String extractRid(JSONObject d) {
        String rid = d.optString("rid", "");
        if (!rid.isEmpty()) return rid;
        String msg = d.optString("message", "");
        return msg.isEmpty() ? "?" : msg;
    }

    // ------------------------------------------------------------------ ddCalcu

    /**
     * 取 pid 指定下标的数字（越界或非数字回退 0）。
     *
     * <p>Python 版直接写 {@code pid[6]}，pid 过短会抛 IndexError；Java 的
     * {@code charAt} 会抛 StringIndexOutOfBoundsException。咪咕 pid 固定 9 位，
     * 但接口异常时不能让整个取流崩掉，故此处做防御。
     */
    private static int ddCode(String pid, int index) {
        if (pid == null || index < 0 || index >= pid.length()) return 0;
        char c = pid.charAt(index);
        return (c >= '0' && c <= '9') ? c - '0' : 0;
    }

    /** DD_KEYS 下标取值（越界回退 0 号字符）。 */
    private static char ddKey(int index) {
        if (index < 0 || index >= DD_KEYS.length()) return DD_KEYS.charAt(0);
        return DD_KEYS.charAt(index);
    }

    /** 纯字符变换，对齐 getddCalcuURL720p（旧版无需 wasm）。 */
    static String ddCalcu720p(String purl, String pid) {
        int idx = purl.indexOf("&puData=");
        if (idx < 0) return "";
        String pu = purl.substring(idx + "&puData=".length());
        String date3 = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()).substring(2, 3);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pu.length() / 2; i++) {
            out.append(pu.charAt(pu.length() - i - 1));
            out.append(pu.charAt(i));
            switch (i) {
                case 1:
                    out.append('v');
                    break;
                case 2:
                    out.append(DD_KEYS.charAt(Integer.parseInt(date3)));
                    break;
                case 3:
                    out.append(ddKey(ddCode(pid, 6)));
                    break;
                case 4:
                    out.append('a');
                    break;
                default:
                    break;
            }
        }
        return out.toString();
    }

    /** 登录版 ddCalcu（对齐 getddCalcuURL 纯字符变换，无需 wasm）。 */
    static String ddCalcu(String purl, String pid, int rateType, String userId) {
        int idx = purl.indexOf("&puData=");
        if (idx < 0) return "";
        String pu = purl.substring(idx + "&puData=".length());
        char[] words = {'v', 'a', '0', 'a'};
        int third = 6;
        if (userId != null && userId.length() > 7 && Character.isDigit(userId.charAt(7))) {
            words[0] = DD_KEYS.charAt(userId.charAt(7) - '0');
        }
        if (rateType == 2) words[0] = 'v';
        if (userId != null && userId.length() > 3 && userId.length() <= 8) words[0] = 'e';
        String date0 = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()).substring(0, 1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pu.length() / 2; i++) {
            out.append(pu.charAt(pu.length() - i - 1));
            out.append(pu.charAt(i));
            switch (i) {
                case 1:
                    out.append(words[0]);
                    break;
                case 2:
                    out.append(DD_KEYS.charAt(Integer.parseInt(date0)));
                    break;
                case 3:
                    out.append(ddKey(ddCode(pid, third)));
                    break;
                case 4:
                    out.append(words[3]);
                    break;
                default:
                    break;
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 直播源列表

    /**
     * 按分类分组输出 M3U（央视/卫视/地方…各自独立，不合并）。
     *
     * <p>输出 M3U 而非项目原 TXT 格式的原因：要与央视频部分合并成同一份列表，
     * 而央视频必须用 M3U 才能承载每频道的 {@code catchup} 回看属性
     * （{@code Live.catchup} 带 {@code @Ignore}、永不从配置反序列化）。两者统一格式后
     * 直接拼接即可，无需再做 TXT→M3U 转换。
     *
     * @param base        本机服务器地址，如 http://127.0.0.1:9978（端口动态，勿硬编码）
     * @param proxyPath   取流路由，如 /ysp
     * @param separator   分组注释行后缀，用于区分同名分组
     */
    public String liveContent(String base, String proxyPath, String separator) {
        StringBuilder sb = new StringBuilder();
        sb.append("#EXTM3U\n");
        for (Cate cate : getCates()) {
            if (cate.chans.isEmpty()) continue;
            sb.append("\n#  ").append(cate.name).append(separator).append('\n');
            for (Chan ch : cate.chans) {
                if (ch.pid.isEmpty() || ch.name.isEmpty()) continue;
                sb.append("#EXTINF:-1 tvg-name=\"").append(ch.name)
                        .append("\" group-title=\"").append(cate.name).append("\",")
                        .append(ch.name).append('\n');
                sb.append(base).append(proxyPath).append("?fun=migu&id=").append(ch.pid).append('\n');
            }
        }
        return sb.toString();
    }

    /** 取流；成功返回 CDN URL，失败返回 null。 */
    public String serve(String pid) {
        return playUrl(pid);
    }

    // ------------------------------------------------------------------ 日志

    /** 追加一行运行时日志（取址/拉流失败诊断用）。 */
    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            File dir = Init.context().getFilesDir();
            if (dir == null) return;
            try (Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, "migu_runtime.log"), true), StandardCharsets.UTF_8)) {
                w.write(System.currentTimeMillis() + " " + msg + "\n");
            }
        } catch (Throwable ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** TMDB API Key 是否已配置（扫码绑定页提示用）。 */
    public static boolean hasTmdbKey() {
        return Setting.hasTmdbApiKey();
    }
}
