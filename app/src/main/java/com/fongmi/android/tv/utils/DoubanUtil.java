package com.fongmi.android.tv.utils;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.bean.Person;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 豆瓣（Douban）元数据工具类。
 * 作为 TMDB 的国产直连兜底方案：<b>无需 API Key、无需代理</b>，国内网络可直接访问。
 * 提供横版剧照背景（backdrop）、剧情简介、导演/演员（含头像与饰演角色）。
 * 豆瓣不提供透明标题 Logo，故 logoUrl 恒为空，UI 会自动降级为文字剧名。
 *
 * 接口（m.douban.com rexxar，仅需移动端 UA + Referer）：
 * - 搜索（首选，数据中心 IP 下也稳定）：/rexxar/api/v2/search/movie?q=
 * - 搜索（备1，偶发 need_login）：      /rexxar/api/v2/search?q=
 * - 搜索（备2，家宽环境可用）：         https://movie.douban.com/j/subject_suggest?q=
 * - 详情：    /rexxar/api/v2/{tv|movie}/{id}
 * - 壁纸剧照：/rexxar/api/v2/{tv|movie}/{id}/photos?type=W
 * - 演职员：  /rexxar/api/v2/{tv|movie}/{id}/credits
 */
public class DoubanUtil {

    private static final String TAG = "DoubanUtil";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10; Pixel 2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36";
    private static final String REXXAR = "https://m.douban.com/rexxar/api/v2";
    // 搜索源按稳定性排序：search/movie 在数据中心 IP 下也稳定；search 偶发 need_login；suggest 仅家宽稳定
    private static final String SEARCH_MOVIE_URL = REXXAR + "/search/movie?start=0&limit=10&q=";
    private static final String SEARCH_URL = REXXAR + "/search?start=0&limit=10&q=";
    private static final String SUGGEST_URL = "https://movie.douban.com/j/subject_suggest?q=";
    // 统一走稳定的 img9 域名，去掉会过期的 qnmob*-sign 签名参数
    private static final String IMG_BASE = "https://img9.doubanio.com";
    // 匹配 doubanio 图片路径：/view/(photo|celebrity|personage)/<尺寸>/public/<文件名>
    private static final Pattern IMG_PATH = Pattern.compile("doubanio\\.com/view/(photo|celebrity|personage)/[^/]+/public/([^?]+)");

    // 简单内存缓存，避免同一会话内重复请求
    private static final Map<String, TmdbResult> SEARCH_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, CreditsResult> CREDITS_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CACHE = 200;

    private static Map<String, String> headers(String referer) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("User-Agent", UA);
        h.put("Referer", referer);
        h.put("Accept", "application/json, text/plain, */*");
        h.put("Accept-Language", "zh-CN,zh;q=0.9");
        return h;
    }

    private static String get(String url, String referer) {
        try {
            return OkHttp.string(url, headers(referer));
        } catch (Throwable e) {
            Log.w(TAG, "GET failed: " + url + " : " + e.getMessage());
            return "";
        }
    }

    /**
     * 把豆瓣图片 URL 规范化为稳定、无签名、可直连的地址。
     * 剧照（photo）用 l（约 1600 宽横图），头像（celebrity/personage）用 m。
     */
    private static String normalizeUrl(String raw, boolean photo) {
        if (TextUtils.isEmpty(raw)) return "";
        Matcher m = IMG_PATH.matcher(raw);
        if (m.find()) {
            String kind = m.group(1);
            String file = m.group(2);
            if ("photo".equals(kind)) {
                return IMG_BASE + "/view/photo/l/public/" + file;
            }
            return IMG_BASE + "/view/" + kind + "/m/public/" + file;
        }
        return raw;
    }

    /**
     * 搜索并聚合：横版剧照背景 + 剧情简介。返回结构复用 TmdbResult（logo 恒空）。
     */
    public static TmdbResult search(String name) {
        if (TextUtils.isEmpty(name)) return TmdbResult.empty();
        TmdbResult cached = SEARCH_CACHE.get(name);
        if (cached != null) return cached;
        try {
            String id = suggestId(name);
            if (TextUtils.isEmpty(id)) return TmdbResult.empty();

            // 判定 tv / movie：先请求 tv，404/非 JSON 再请求 movie
            String type = "tv";
            String detail = get(REXXAR + "/tv/" + id + "?ck=&for_mobile=1", "https://m.douban.com/");
            if (TextUtils.isEmpty(detail) || !detail.startsWith("{") || detail.contains("\"code\":404") || detail.contains("traversal_error")) {
                type = "movie";
                detail = get(REXXAR + "/movie/" + id + "?ck=&for_mobile=1", "https://m.douban.com/");
            }

            String intro = "";
            if (!TextUtils.isEmpty(detail) && detail.startsWith("{")) {
                try {
                    JsonObject d = JsonParser.parseString(detail).getAsJsonObject();
                    if (d.has("intro") && !d.get("intro").isJsonNull()) intro = d.get("intro").getAsString();
                } catch (Throwable ignore) {
                }
            }

            List<String> backdrops = fetchWallpapers(id, type);
            String first = backdrops.isEmpty() ? "" : backdrops.get(0);
            TmdbResult result = new TmdbResult(Integer.parseInt(id), type, first, backdrops, intro, "");
            if (!result.isEmpty()) {
                if (SEARCH_CACHE.size() >= MAX_CACHE) SEARCH_CACHE.clear();
                SEARCH_CACHE.put(name, result);
            }
            return result;
        } catch (Throwable e) {
            Log.w(TAG, "search failed: " + e.getMessage());
            return TmdbResult.empty();
        }
    }

    /**
     * 剧名 -> subject id，多源容错。
     * 1) rexxar /search/movie（首选，数据中心 IP 下也稳定，items[].target）
     * 2) rexxar /search（subjects.items[].target，偶发 need_login）
     * 3) movie.douban.com/j/subject_suggest（数组，家宽环境可用）
     * 每个源失败重试一次；候选统一按标题相关度 + 上映年份评分，规避同名山寨/未上映条目。
     */
    private static String suggestId(String name) {
        try {
            String enc = URLEncoder.encode(name, StandardCharsets.UTF_8.name());
            String[][] sources = {
                    {SEARCH_MOVIE_URL + enc, "https://m.douban.com/search/?query=" + enc},
                    {SEARCH_URL + enc, "https://m.douban.com/search/?query=" + enc},
                    {SUGGEST_URL + enc, "https://movie.douban.com/"}
            };
            for (String[] src : sources) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    String id = pickCandidate(name, get(src[0], src[1]));
                    if (!TextUtils.isEmpty(id)) return id;
                    if (attempt == 0) sleep(350);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "suggestId failed: " + e.getMessage());
        }
        return "";
    }

    /** 递归收集搜索结果里所有含 id+title 的候选条目，兼容三种搜索接口的不同包裹结构 */
    private static void collectCandidates(JsonElement el, List<JsonObject> out) {
        if (el == null) return;
        if (el.isJsonArray()) {
            for (JsonElement x : el.getAsJsonArray()) collectCandidates(x, out);
        } else if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("id") && o.has("title")) out.add(o);
            for (Map.Entry<String, JsonElement> en : o.entrySet()) collectCandidates(en.getValue(), out);
        }
    }

    private static String pickCandidate(String name, String json) {
        if (TextUtils.isEmpty(json) || (!json.startsWith("{") && !json.startsWith("["))) return "";
        if (json.contains("need_login") || json.contains("traversal_error")) return "";
        List<JsonObject> cand = new ArrayList<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (root.isJsonArray()) {
                for (JsonElement x : root.getAsJsonArray()) if (x.isJsonObject()) cand.add(x.getAsJsonObject());
            } else {
                collectCandidates(root, cand);
            }
        } catch (Throwable e) {
            return "";
        }
        int thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        String best = "";
        int bestScore = -1, bestYear = Integer.MAX_VALUE;
        for (JsonObject o : cand) {
            String title = optString(o, "title");
            if (TextUtils.isEmpty(title) || !o.has("id") || o.get("id").isJsonNull()) continue;
            String type = optString(o, "type");
            // 排除书籍、音乐等非影视条目（豆瓣剧集也常标 movie）
            if (!TextUtils.isEmpty(type) && !"movie".equals(type) && !"tv".equals(type)) continue;
            int y = parseYear(optString(o, "year"));
            int s = matchScore(name, title, y, thisYear);
            if (s > bestScore || (s == bestScore && y > 0 && y < bestYear)) {
                bestScore = s;
                bestYear = y;
                best = o.get("id").getAsString();
            }
        }
        return best;
    }

    private static int parseYear(String y) {
        Matcher m = Pattern.compile("(\\d{4})").matcher(y == null ? "" : y);
        try { return m.find() ? Integer.parseInt(m.group(1)) : 0; } catch (Throwable e) { return 0; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignore) { Thread.currentThread().interrupt(); }
    }

    /**
     * 标题相关度评分，结合上映年份：
     * - 完全同名但年份在未来（未上映/占位条目）降权；
     * - 用户未指定季时，非首季（第二/三季…）略降，含“第一季”略升，使裸剧名命中第一季；
     * - 同分时优先上映更早、信息更确定的条目。
     */
    private static int matchScore(String query, String title, int year, int thisYear) {
        String q = query.replaceAll("\\s+", "");
        String t = title.replaceAll("\\s+", "");
        boolean future = year > thisYear + 1;
        if (t.equals(q)) return future ? 70 : 100;
        if (t.contains(q) || q.contains(t)) {
            boolean queryNoLaterSeason = !q.matches(".*第[二三四五六七八九十2-9].*");
            if (queryNoLaterSeason && t.matches(".*第[二三四五六七八九十2-9]季.*")) return 75;
            if (t.contains("第一季")) return 85;
            return 80;
        }
        // 去掉“第x季”后再比，处理“庆余年”匹配“庆余年 第一季”
        String base = q.replaceAll("第[0-9一二三四五六七八九十]+季.*", "");
        if (base.length() >= 2 && (t.contains(base) || base.contains(t))) return 60;
        return 10;
    }

    /** 取 W 类壁纸剧照，仅保留横图（width>height），作为详情页/首页背景 */
    private static List<String> fetchWallpapers(String id, String type) {
        List<String> list = new ArrayList<>();
        try {
            String url = REXXAR + "/" + type + "/" + id + "/photos?type=W&start=0&count=30&ck=&for_mobile=1";
            String json = get(url, "https://m.douban.com/");
            if (TextUtils.isEmpty(json) || !json.startsWith("{")) return list;
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("photos") || !root.get("photos").isJsonArray()) return list;
            for (JsonElement el : root.getAsJsonArray("photos")) {
                if (!el.isJsonObject()) continue;
                JsonObject photo = el.getAsJsonObject();
                if (!photo.has("image") || photo.get("image").isJsonNull()) continue;
                JsonObject image = photo.getAsJsonObject("image");
                if (!image.has("large") || image.get("large").isJsonNull()) continue;
                JsonObject large = image.getAsJsonObject("large");
                int w = large.has("width") && !large.get("width").isJsonNull() ? large.get("width").getAsInt() : 0;
                int h = large.has("height") && !large.get("height").isJsonNull() ? large.get("height").getAsInt() : 0;
                if (w <= h) continue; // 只保留横图，竖海报不能做背景
                String u = normalizeUrl(optString(large, "url"), true);
                if (!TextUtils.isEmpty(u) && !list.contains(u)) list.add(u);
                if (list.size() >= 15) break;
            }
        } catch (Throwable e) {
            Log.w(TAG, "fetchWallpapers failed: " + e.getMessage());
        }
        return list;
    }

    /**
     * 获取演职员（含头像、饰演角色），结构复用 CreditsResult。
     * 注意：以 simple_character/character（本片职务）判定导演/演员，
     * 不能用 roles（那是该艺人的终身职业，会把主演误判成导演）。
     */
    public static CreditsResult getCredits(int id, String mediaType) {
        if (id <= 0) return CreditsResult.empty();
        String type = "tv".equalsIgnoreCase(mediaType) ? "tv" : "movie";
        String cacheKey = type + "/" + id;
        CreditsResult cached = CREDITS_CACHE.get(cacheKey);
        if (cached != null) return cached;
        CreditsResult result = CreditsResult.empty();
        try {
            String url = REXXAR + "/" + type + "/" + id + "/credits?start=0&count=20&ck=&for_mobile=1";
            String json = get(url, "https://m.douban.com/");
            if (TextUtils.isEmpty(json) || !json.startsWith("{")) return result;
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("items") || !root.get("items").isJsonArray()) return result;

            List<Person> cast = new ArrayList<>();
            List<Person> crew = new ArrayList<>();
            for (JsonElement el : root.getAsJsonArray("items")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String name = optString(o, "name");
                if (TextUtils.isEmpty(name)) continue;

                String simple = optString(o, "simple_character"); // 形如“导演”“联合执导”“饰 范闲”
                String character = optString(o, "character");     // 形如“导演 Director”“演员 Actor (饰 范闲)”
                String avatar = "";
                if (o.has("avatar") && !o.get("avatar").isJsonNull()) {
                    avatar = normalizeUrl(optString(o.getAsJsonObject("avatar"), "large"), false);
                }

                boolean isDirector = simple.contains("导演") || simple.contains("执导") || character.contains("Director");
                boolean isActor = simple.startsWith("饰") || character.startsWith("演员")
                        || character.contains("Actor") || character.contains("Actress");

                if (isDirector) {
                    Person director = new Person();
                    director.setName(name);
                    director.setProfilePath(avatar);
                    director.setDepartment("Directing"); // CreditsResult.getDirectors() 据此识别
                    director.setJob("导演");
                    crew.add(director);
                }
                if (isActor && !isDirector) {
                    Person actor = new Person();
                    actor.setName(name);
                    actor.setProfilePath(avatar); // 完整 URL，buildProfileUrl 识别 http 直接返回
                    actor.setKnownForDepartment("Acting");
                    String role = simple;
                    if (role.startsWith("饰")) {
                        role = role.substring(1).trim();
                    } else {
                        int idx = character.indexOf("饰");
                        if (idx >= 0) role = character.substring(idx + 1).replace(")", "").trim();
                    }
                    actor.setCharacter(role);
                    cast.add(actor);
                }
            }
            result = new CreditsResult(cast, crew);
            if (CREDITS_CACHE.size() >= MAX_CACHE) CREDITS_CACHE.clear();
            CREDITS_CACHE.put(cacheKey, result);
        } catch (Throwable e) {
            Log.w(TAG, "getCredits failed: " + e.getMessage());
        }
        return result;
    }

    public static void searchAsync(String name, TmdbUtil.SearchResultCallback callback) {
        new Thread(() -> callback.onResult(search(name)), "douban-search").start();
    }

    public static void getCreditsAsync(int id, String mediaType, TmdbUtil.CreditsCallback callback) {
        new Thread(() -> callback.onResult(getCredits(id, mediaType)), "douban-credits").start();
    }

    private static String optString(JsonObject o, String key) {
        return (o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : "";
    }
}
