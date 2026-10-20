package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.utils.TmdbResult;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 豆瓣兜底数据源：未配置 TMDB API Key（或 TMDB 无结果）时，
 * 为首页横屏背景图/剧情简介提供数据。使用 m.douban.com rexxar 接口（无需登录）。
 */
public class DoubanApi {

    private static final String BASE = "https://m.douban.com/rexxar/api/v2";
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0",
            "Referer", "https://movie.douban.com/",
            "Origin", "https://movie.douban.com"
    );

    /** 按名称搜索并组装与 TmdbResult 同构的兜底结果（横屏剧照 + 简介）。 */
    public static TmdbResult fallback(String name) {
        try {
            if (TextUtils.isEmpty(name)) return TmdbResult.empty();
            String id = findId(name);
            if (TextUtils.isEmpty(id)) return TmdbResult.empty();
            JSONObject detail = detail(id);
            String overview = detail.optString("intro", detail.optString("summary", "")).trim();
            List<String> backdrops = new ArrayList<>();
            for (Photo photo : photos(id)) if (photo.isLand() && !photo.url.isEmpty()) backdrops.add(photo.url);
            String backdropUrl = backdrops.isEmpty() ? pic(detail) : backdrops.get(0);
            return new TmdbResult(0, "", backdropUrl, backdrops, overview, "");
        } catch (Exception e) {
            return TmdbResult.empty();
        }
    }

    /** 搜索条目：先电影后剧集，标题精确匹配优先，否则取首个候选。 */
    private static String findId(String name) throws Exception {
        String candidate = "";
        for (String type : new String[]{"movie", "tv"}) {
            JSONArray items = subjects(name, type);
            if (items == null) continue;
            for (int i = 0; i < items.length(); i++) {
                JSONObject target = items.optJSONObject(i) == null ? null : items.optJSONObject(i).optJSONObject("target");
                if (target == null) continue;
                String id = target.optString("id");
                if (TextUtils.isEmpty(id)) continue;
                if (name.equals(target.optString("title"))) return id;
                if (TextUtils.isEmpty(candidate)) candidate = id;
            }
        }
        return candidate;
    }

    private static JSONArray subjects(String q, String type) throws Exception {
        String url = BASE + "/search/subjects?q=" + URLEncoder.encode(q, "UTF-8") + "&type=" + type;
        JSONObject root = new JSONObject(OkHttp.string(url, HEADERS));
        JSONObject subjects = root.optJSONObject("subjects");
        return subjects == null ? null : subjects.optJSONArray("items");
    }

    private static JSONObject detail(String id) throws Exception {
        return new JSONObject(OkHttp.string(BASE + "/subject/" + id, HEADERS));
    }

    private static List<Photo> photos(String id) {
        List<Photo> items = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(OkHttp.string(BASE + "/subject/" + id + "/photos?type=R&start=0&count=30", HEADERS));
            JSONArray array = root.optJSONArray("photos");
            if (array == null) return items;
            for (int i = 0; i < array.length(); i++) items.add(Photo.objectFrom(array.optJSONObject(i)));
        } catch (Exception ignored) {
        }
        return items;
    }

    private static String pic(JSONObject object) {
        return image(object.optString("cover_url", object.optString("cover")));
    }

    /** 豆瓣图片有防盗链：追加 @Referer/@User-Agent 后缀，由 ImgUtil 代理请求时注入。 */
    private static String image(String url) {
        if (url == null || url.isEmpty()) return "";
        return url + "@Referer=https://movie.douban.com/@User-Agent=Mozilla/5.0";
    }

    public static class Photo {

        public final String url;
        public final int width;
        public final int height;

        private Photo(String url, int width, int height) {
            this.url = image(url);
            this.width = width;
            this.height = height;
        }

        public boolean isLand() {
            return width > height;
        }

        private static Photo objectFrom(JSONObject object) {
            if (object == null) return new Photo("", 0, 0);
            JSONObject image = object.optJSONObject("image");
            JSONObject large = image == null ? null : image.optJSONObject("large");
            JSONObject normal = image == null ? null : image.optJSONObject("normal");
            JSONObject data = large != null ? large : normal;
            if (data == null) return new Photo("", 0, 0);
            return new Photo(data.optString("url"), data.optInt("width"), data.optInt("height"));
        }
    }
}
