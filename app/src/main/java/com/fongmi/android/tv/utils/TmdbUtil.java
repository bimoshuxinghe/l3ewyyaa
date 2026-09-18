package com.fongmi.android.tv.utils;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.bean.Person;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * TMDB API 工具类
 * 用于通过 TMDB API 搜索影视内容并获取横屏背景图(backdrop)、剧情简介(overview)和标题 Logo。
 * 需要在设置中配置 TMDB API Key 后才能使用。
 * 支持自定义 API 地址和图片地址（用于国内镜像）。
 */
public class TmdbUtil {

    private static final String TAG = "TmdbUtil";
    private static final String DEFAULT_BASE_URL = "https://api.tmdb.org/3";
    private static final String DEFAULT_IMAGE_BASE = "https://image.tmdb.org/t/p";

    private static String getBaseUrl() {
        String url = Setting.getTmdbApiUrl();
        if (TextUtils.isEmpty(url)) return DEFAULT_BASE_URL;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!url.endsWith("/3")) {
            if (url.endsWith("/3/")) url = url.substring(0, url.length() - 1);
            else if (!url.contains("/3")) url = url + "/3";
        }
        return url;
    }

    private static String getImageBase() {
        String url = Setting.getTmdbImageUrl();
        if (TextUtils.isEmpty(url)) return DEFAULT_IMAGE_BASE;
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url;
    }

    private static String buildImageUrl(String path) {
        if (TextUtils.isEmpty(path)) return "";
        return getImageBase() + "/original" + path;
    }

    /**
     * 搜索影视内容并返回包含多张背景图、剧情简介和标题 Logo 的结果。
     * 依次尝试 multi 搜索、movie 搜索、tv 搜索。
     *
     * @param name 影视名称
     * @return TmdbResult，未找到返回空结果
     */
    public static TmdbResult search(String name) {
        if (TextUtils.isEmpty(name) || !Setting.hasTmdbApiKey()) return TmdbResult.empty();
        String apiKey = Setting.getTmdbApiKey();
        String baseUrl = getBaseUrl();
        Log.d(TAG, "search: name=" + name + ", baseUrl=" + baseUrl);

        TmdbResult result = searchMulti(baseUrl, apiKey, name);
        if (result.hasBackdrop() || result.hasLogoUrl()) {
            Log.d(TAG, "Found via multi search: backdrop=" + result.hasBackdrop() + ", logo=" + result.hasLogoUrl());
            return result;
        }

        result = searchMovie(baseUrl, apiKey, name);
        if (result.hasBackdrop() || result.hasLogoUrl()) {
            Log.d(TAG, "Found via movie search: backdrop=" + result.hasBackdrop() + ", logo=" + result.hasLogoUrl());
            return result;
        }

        result = searchTv(baseUrl, apiKey, name);
        if (result.hasBackdrop() || result.hasLogoUrl()) {
            Log.d(TAG, "Found via tv search: backdrop=" + result.hasBackdrop() + ", logo=" + result.hasLogoUrl());
            return result;
        }

        Log.w(TAG, "No result found for: " + name);
        return TmdbResult.empty();
    }

    private static TmdbResult searchMulti(String baseUrl, String apiKey, String name) {
        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.name());
            String url = baseUrl + "/search/multi?api_key=" + apiKey + "&query=" + encodedName + "&language=zh-CN&page=1&include_adult=true";
            String json = OkHttp.string(url, Map.of("Accept", "application/json"));
            return extractFromResults(json, baseUrl, apiKey, "");
        } catch (Exception e) {
            Log.w(TAG, "searchMulti failed: " + e.getMessage());
            return TmdbResult.empty();
        }
    }

    private static TmdbResult searchMovie(String baseUrl, String apiKey, String name) {
        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.name());
            String url = baseUrl + "/search/movie?api_key=" + apiKey + "&query=" + encodedName + "&language=zh-CN&page=1&include_adult=true";
            String json = OkHttp.string(url, Map.of("Accept", "application/json"));
            return extractFromResults(json, baseUrl, apiKey, "movie");
        } catch (Exception e) {
            Log.w(TAG, "searchMovie failed: " + e.getMessage());
            return TmdbResult.empty();
        }
    }

    private static TmdbResult searchTv(String baseUrl, String apiKey, String name) {
        try {
            String encodedName = URLEncoder.encode(name, StandardCharsets.UTF_8.name());
            String url = baseUrl + "/search/tv?api_key=" + apiKey + "&query=" + encodedName + "&language=zh-CN&page=1&include_adult=true";
            String json = OkHttp.string(url, Map.of("Accept", "application/json"));
            return extractFromResults(json, baseUrl, apiKey, "tv");
        } catch (Exception e) {
            Log.w(TAG, "searchTv failed: " + e.getMessage());
            return TmdbResult.empty();
        }
    }

    private static TmdbResult extractFromResults(String json, String baseUrl, String apiKey, String defaultType) {
        if (TextUtils.isEmpty(json)) return TmdbResult.empty();
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray results = root.has("results") ? root.getAsJsonArray("results") : null;
            if (results == null || results.isEmpty()) return TmdbResult.empty();
            TmdbResult backdropResult = TmdbResult.empty();
            for (JsonElement element : results) {
                JsonObject item = element.getAsJsonObject();
                String overview = "";
                if (item.has("overview") && !item.get("overview").isJsonNull()) {
                    overview = item.get("overview").getAsString();
                }
                if (!item.has("id") || item.get("id").isJsonNull()) continue;
                int id = item.get("id").getAsInt();
                String mediaType = defaultType;
                if (item.has("media_type") && !item.get("media_type").isJsonNull()) {
                    String itemType = item.get("media_type").getAsString();
                    if (!TextUtils.isEmpty(itemType)) mediaType = itemType;
                }
                if (id <= 0) continue;
                // multi 搜索可能返回 person 等非影视类型，只处理 movie/tv
                if (!"movie".equals(mediaType) && !"tv".equals(mediaType)) continue;
                TmdbResult images = fetchImages(baseUrl, apiKey, id, mediaType);
                // 优先选取第一个（最相关）带标题 Logo 的结果，最大化 logo 覆盖率
                if (images.hasLogoUrl()) {
                    return new TmdbResult(id, mediaType, images.getBackdropUrl(), images.getBackdrops(), overview, images.getLogoUrl());
                }
                // 没有 logo 时，记录第一个带背景图或简介的结果作为备选
                if (backdropResult.isEmpty() && (images.hasBackdrop() || !TextUtils.isEmpty(overview))) {
                    backdropResult = new TmdbResult(id, mediaType, images.getBackdropUrl(), images.getBackdrops(), overview, "");
                }
            }
            // 没有命中带 Logo 的结果时，返回带背景图/简介的备选
            return backdropResult;
        } catch (Exception e) {
            Log.w(TAG, "extractFromResults failed: " + e.getMessage());
        }
        return TmdbResult.empty();
    }

    private static TmdbResult fetchImages(String baseUrl, String apiKey, int id, String mediaType) {
        if (id <= 0 || TextUtils.isEmpty(mediaType)) return TmdbResult.empty();
        String typePath = "tv".equals(mediaType) ? "tv" : "movie";
        try {
            String url = baseUrl + "/" + typePath + "/" + id + "/images?api_key=" + apiKey;
            String json = OkHttp.string(url, Map.of("Accept", "application/json"));
            if (TextUtils.isEmpty(json)) return TmdbResult.empty();
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            List<String> backdrops = new ArrayList<>();
            String firstBackdrop = "";
            if (root.has("backdrops") && !root.get("backdrops").isJsonNull()) {
                JsonArray backdropArray = root.getAsJsonArray("backdrops");
                for (JsonElement e : backdropArray) {
                    JsonObject o = e.getAsJsonObject();
                    if (!o.has("file_path") || o.get("file_path").isJsonNull()) continue;
                    String path = o.get("file_path").getAsString();
                    if (TextUtils.isEmpty(path)) continue;
                    String imageUrl = buildImageUrl(path);
                    if (backdrops.isEmpty()) firstBackdrop = imageUrl;
                    backdrops.add(imageUrl);
                }
            }
            String logoUrl = extractLogoUrl(root);
            return new TmdbResult(id, mediaType, firstBackdrop, backdrops, "", logoUrl);
        } catch (Exception e) {
            Log.w(TAG, "fetchImages failed: " + e.getMessage());
            return TmdbResult.empty();
        }
    }

    private static String extractLogoUrl(JsonObject root) {
        if (!root.has("logos") || root.get("logos").isJsonNull()) return "";
        JsonArray logos = root.getAsJsonArray("logos");
        String zh = "";
        String en = "";
        String other = "";
        for (JsonElement e : logos) {
            JsonObject o = e.getAsJsonObject();
            if (!o.has("file_path") || o.get("file_path").isJsonNull()) continue;
            String path = o.get("file_path").getAsString();
            if (TextUtils.isEmpty(path)) continue;
            String iso = "";
            if (o.has("iso_639_1") && !o.get("iso_639_1").isJsonNull()) {
                iso = o.get("iso_639_1").getAsString();
            }
            String url = buildImageUrl(path);
            if (other.isEmpty()) other = url;
            // 识别无语言标识（通用）Logo，视为英文备选
            if (TextUtils.isEmpty(iso)) {
                if (en.isEmpty()) en = url;
                continue;
            }
            String lower = iso.toLowerCase();
            // 优先中文（zh、zh-CN、zh-SG 等）
            if (lower.startsWith("zh") && zh.isEmpty()) {
                zh = url;
            } else if (lower.startsWith("en") && en.isEmpty()) {
                en = url;
            }
        }
        if (!zh.isEmpty()) return zh;
        if (!en.isEmpty()) return en;
        return other;
    }

    /**
     * 异步搜索影视内容，通过回调返回包含多张背景图、简介和 Logo 的结果。
     * 结果会缓存到 Setting 中，避免重复请求。
     *
     * @param name     影视名称
     * @param callback 回调接口
     */
    public static void searchAsync(String name, SearchResultCallback callback) {
        if (TextUtils.isEmpty(name) || !Setting.hasTmdbApiKey()) {
            callback.onResult(TmdbResult.empty());
            return;
        }
        // 先查缓存
        String cachedBackdrop = Setting.getTmdbBackdrop(name);
        String cachedOverviews = Setting.getTmdbOverview(name);
        List<String> cachedBackdrops = Setting.getTmdbBackdrops(name);
        String cachedLogo = Setting.getTmdbLogo(name);
        boolean hasBackdropCache = !cachedBackdrops.isEmpty() || !cachedBackdrop.isEmpty();
        // 背景图已缓存且 Logo 是否已查询过（含“无 Logo”哨兵）：都满足则直接返回，否则重新请求补全 Logo
        if (hasBackdropCache && Setting.isTmdbLogoDecided(name)) {
            Log.d(TAG, "Using cached result for: " + name);
            int cachedId = Setting.getTmdbId(name);
            String cachedMediaType = Setting.getTmdbMediaType(name);
            TmdbResult result = new TmdbResult(
                cachedId, cachedMediaType,
                cachedBackdrops.isEmpty() ? cachedBackdrop : cachedBackdrops.get(0),
                cachedBackdrops,
                cachedOverviews,
                cachedLogo
            );
            callback.onResult(result);
            return;
        }
        new Thread(() -> {
            TmdbResult result = search(name);
            if (result.hasBackdrops()) {
                Setting.putTmdbBackdrops(name, result.getBackdrops());
            } else if (result.hasBackdrop()) {
                Setting.putTmdbBackdrop(name, result.getBackdropUrl());
            }
            if (result.hasOverview()) {
                Setting.putTmdbOverview(name, result.getOverview());
            }
            // 缓存id和mediaType，方便后续请求credits接口
            Setting.putTmdbId(name, result.getId());
            Setting.putTmdbMediaType(name, result.getMediaType());
            // 无论是否有 Logo 都写入（无 Logo 时写入 none 哨兵），避免重复请求
            Setting.putTmdbLogo(name, result.getLogoUrl());
            callback.onResult(result);
        }, "tmdb-search").start();
    }

    /**
     * 兼容旧接口：仅搜索背景图
     */
    public static String searchBackdrop(String name) {
        return search(name).getBackdropUrl();
    }

    /**
     * 兼容旧接口：仅异步搜索背景图
     */
    public static void searchBackdropAsync(String name, BackdropCallback callback) {
        searchAsync(name, result -> callback.onResult(result.getBackdropUrl()));
    }

    public interface SearchResultCallback {
        void onResult(TmdbResult result);
    }

    public interface BackdropCallback {
        void onResult(String backdropUrl);
    }

    /**
     * 获取影视的演员和导演列表（同步）
     *
     * @param id        TMDB 影视 ID
     * @param mediaType 类型：movie 或 tv
     * @return CreditsResult，包含 cast（演员）和 crew（主创，含导演）
     */
    public static CreditsResult getCredits(int id, String mediaType) {
        if (id <= 0 || !Setting.hasTmdbApiKey()) return CreditsResult.empty();
        String apiKey = Setting.getTmdbApiKey();
        String baseUrl = getBaseUrl();
        String type = "tv".equalsIgnoreCase(mediaType) ? "tv" : "movie";
        try {
            String url = baseUrl + "/" + type + "/" + id + "/credits?api_key=" + apiKey + "&language=zh-CN";
            String json = OkHttp.string(url, Map.of("Accept", "application/json"));
            return parseCredits(json);
        } catch (Exception e) {
            Log.w(TAG, "getCredits failed: " + e.getMessage());
            return CreditsResult.empty();
        }
    }

    /**
     * 异步获取演员和导演列表
     */
    public static void getCreditsAsync(int id, String mediaType, CreditsCallback callback) {
        if (id <= 0 || !Setting.hasTmdbApiKey()) {
            callback.onResult(CreditsResult.empty());
            return;
        }
        new Thread(() -> {
            CreditsResult result = getCredits(id, mediaType);
            callback.onResult(result);
        }, "tmdb-credits").start();
    }

    private static CreditsResult parseCredits(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            List<Person> cast = new ArrayList<>();
            List<Person> crew = new ArrayList<>();
            if (root.has("cast") && root.get("cast").isJsonArray()) {
                JsonArray castArray = root.getAsJsonArray("cast");
                for (JsonElement el : castArray) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    Person p = new Person();
                    p.setId(obj.has("id") ? obj.get("id").getAsInt() : 0);
                    p.setName(obj.has("name") ? obj.get("name").getAsString() : "");
                    p.setProfilePath(obj.has("profile_path") && !obj.get("profile_path").isJsonNull() ? obj.get("profile_path").getAsString() : "");
                    p.setCharacter(obj.has("character") && !obj.get("character").isJsonNull() ? obj.get("character").getAsString() : "");
                    p.setKnownForDepartment(obj.has("known_for_department") && !obj.get("known_for_department").isJsonNull() ? obj.get("known_for_department").getAsString() : "");
                    cast.add(p);
                }
            }
            if (root.has("crew") && root.get("crew").isJsonArray()) {
                JsonArray crewArray = root.getAsJsonArray("crew");
                for (JsonElement el : crewArray) {
                    if (!el.isJsonObject()) continue;
                    JsonObject obj = el.getAsJsonObject();
                    Person p = new Person();
                    p.setId(obj.has("id") ? obj.get("id").getAsInt() : 0);
                    p.setName(obj.has("name") ? obj.get("name").getAsString() : "");
                    p.setProfilePath(obj.has("profile_path") && !obj.get("profile_path").isJsonNull() ? obj.get("profile_path").getAsString() : "");
                    p.setDepartment(obj.has("department") && !obj.get("department").isJsonNull() ? obj.get("department").getAsString() : "");
                    p.setJob(obj.has("job") && !obj.get("job").isJsonNull() ? obj.get("job").getAsString() : "");
                    crew.add(p);
                }
            }
            return new CreditsResult(cast, crew);
        } catch (Exception e) {
            Log.w(TAG, "parseCredits failed: " + e.getMessage());
            return CreditsResult.empty();
        }
    }

    /**
     * 构建演员头像完整 URL（用w185尺寸，加载更快）
     */
    public static String buildProfileUrl(String profilePath) {
        if (TextUtils.isEmpty(profilePath)) return "";
        return getImageBase() + "/w185" + profilePath;
    }

    public interface CreditsCallback {
        void onResult(CreditsResult result);
    }
}
