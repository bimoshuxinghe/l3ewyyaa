package com.fongmi.android.tv.setting;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.List;

public class Setting {

    public static String getDoh() {
        return Prefers.getString("doh");
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static int getWall() {
        return Prefers.getInt("wall", 1);
    }

    public static void putWall(int wall) {
        Prefers.put("wall", wall);
    }

    public static int getWallType() {
        return Prefers.getInt("wall_type", 0);
    }

    public static int getPictureReaderMode() {
        return Prefers.getInt("picture_reader_mode", 0);
    }

    public static void putPictureReaderMode(int mode) {
        Prefers.put("picture_reader_mode", mode);
    }

    public static void putWallType(int type) {
        Prefers.put("wall_type", type);
    }

    public static int getReset() {
        return Prefers.getInt("reset", 0);
    }

    public static void putReset(int reset) {
        Prefers.put("reset", reset);
    }

    public static int getSiteMode() {
        return Prefers.getInt("site_mode");
    }

    public static void putSiteMode(int mode) {
        Prefers.put("site_mode", mode);
    }

    public static int getSyncMode() {
        return Prefers.getInt("sync_mode");
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", mode);
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    public static boolean getUpdate() {
        return Prefers.getBoolean("update", true);
    }

    public static void putUpdate(boolean update) {
        Prefers.put("update", update);
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", -1);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getWallColor() {
        return Prefers.getInt("wall_color", 0);
    }

    public static void putWallColor(int color) {
        Prefers.put("wall_color", color);
    }

    public static int getDynamicColor() {
        int color = getThemeColor();
        if (color == -1) return 0;
        return color != 0 ? color : getWallColor();
    }

    public static boolean hasFileManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + App.get().getPackageName())).resolveActivity(App.get().getPackageManager()) != null || new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).resolveActivity(App.get().getPackageManager()) != null;
    }

    public static String getSyncUrl() {
        return Prefers.getString("sync_url", "");
    }

    public static void putSyncUrl(String value) {
        Prefers.put("sync_url", value);
    }

    public static String getSyncUser() {
        return Prefers.getString("sync_user", "");
    }

    public static void putSyncUser(String value) {
        Prefers.put("sync_user", value);
    }

    public static String getSyncPass() {
        return Prefers.getString("sync_pass", "");
    }

    public static void putSyncPass(String value) {
        Prefers.put("sync_pass", value);
    }

    public static boolean isSyncAutoBackup() {
        return getSyncInterval() > 0;
    }

    public static void putSyncAutoBackup(boolean value) {
        Prefers.put("sync_auto_backup", value);
    }

    public static int getSyncInterval() {
        return Prefers.getInt("sync_interval", 0);
    }

    public static void putSyncInterval(int value) {
        Prefers.put("sync_interval", value);
    }

    public static boolean isSyncAutoSync() {
        return Prefers.getBoolean("sync_auto_sync", false);
    }

    public static void putSyncAutoSync(boolean value) {
        Prefers.put("sync_auto_sync", value);
    }

    public static String getProxySubscriptionUrl() {
        return Prefers.getString("proxy_subscription_url", "");
    }

    public static void putProxySubscriptionUrl(String value) {
        Prefers.put("proxy_subscription_url", value);
    }

    public static String getProxySubscriptionNodes() {
        return Prefers.getString("proxy_subscription_nodes", "");
    }

    public static void putProxySubscriptionNodes(String value) {
        Prefers.put("proxy_subscription_nodes", value);
    }

    public static String getProxySubscriptionConfig() {
        return Prefers.getString("proxy_subscription_config", "");
    }

    public static void putProxySubscriptionConfig(String value) {
        Prefers.put("proxy_subscription_config", value);
    }

    public static String getProxySubscriptionCoreName() {
        return Prefers.getString("proxy_subscription_core_name", "");
    }

    public static void putProxySubscriptionCoreName(String value) {
        Prefers.put("proxy_subscription_core_name", value);
    }

    public static String getProxySubscriptionSelected() {
        return Prefers.getString("proxy_subscription_selected", "");
    }

    public static void putProxySubscriptionSelected(String value) {
        Prefers.put("proxy_subscription_selected", value);
    }

    public static boolean isProxySubscriptionEnabled() {
        return Prefers.getBoolean("proxy_subscription_enabled", false);
    }

    public static void putProxySubscriptionEnabled(boolean value) {
        Prefers.put("proxy_subscription_enabled", value);
    }

    public static int getSearchMode() {
        return Prefers.getInt("search_mode", 0);
    }

    public static void putSearchMode(int value) {
        Prefers.put("search_mode", value);
    }

    public static int getSearchType() {
        return Prefers.getInt("search_type", 0);
    }

    public static void putSearchType(int value) {
        Prefers.put("search_type", value);
    }

    public static int getSearchThread() {
        return Math.min(Math.max(Prefers.getInt("search_thread", 10), 3), 15);
    }

    public static void putSearchThread(int value) {
        Prefers.put("search_thread", Math.min(Math.max(value, 3), 15));
    }

    public static int getLayoutMode() {
        return Prefers.getInt("layout_mode", 0);
    }

    public static void putLayoutMode(int value) {
        Prefers.put("layout_mode", value);
    }

    public static boolean isAlwaysTime() {
        return Prefers.getBoolean("always_time", false);
    }

    public static void putAlwaysTime(boolean value) {
        Prefers.put("always_time", value);
    }

    public static boolean isAlwaysProgress() {
        return Prefers.getBoolean("always_progress", false);
    }

    public static void putAlwaysProgress(boolean value) {
        Prefers.put("always_progress", value);
    }

    public static boolean isHomeVod() {
        return Prefers.getBoolean("home_vod", true);
    }

    public static void putHomeVod(boolean value) {
        Prefers.put("home_vod", value);
    }

    public static boolean isHomeHot() {
        return Prefers.getBoolean("home_hot", true);
    }

    public static void putHomeHot(boolean value) {
        Prefers.put("home_hot", value);
    }

    public static boolean isHomeLive() {
        return Prefers.getBoolean("home_live", true);
    }

    public static void putHomeLive(boolean value) {
        Prefers.put("home_live", value);
    }

    public static boolean isHomeLocal() {
        return Prefers.getBoolean("home_local", false);
    }

    public static void putHomeLocal(boolean value) {
        Prefers.put("home_local", value);
    }

    public static boolean isHomeHistory() {
        return Prefers.getBoolean("home_history", true);
    }

    public static void putHomeHistory(boolean value) {
        Prefers.put("home_history", value);
    }

    public static boolean isHomeDownload() {
        return false;
    }

    public static void putHomeDownload(boolean value) {
        Prefers.put("home_download", value);
    }

    public static int getHomeStyle() {
        return Prefers.getInt("home_style", 1);
    }

    public static void putHomeStyle(int value) {
        Prefers.put("home_style", value);
    }

    public static boolean isHomeCapsule() {
        return getHomeStyle() == 1;
    }

    public static int getFocusColor() {
        return Prefers.getInt("focus_color", 0);
    }

    public static void putFocusColor(int value) {
        Prefers.put("focus_color", value);
    }

    public static String getIqiyiRecommends() {
        return Prefers.getString("iqiyi_recommends", "");
    }

    public static void putIqiyiRecommends(String value) {
        Prefers.put("iqiyi_recommends", value);
    }

    public static String getHomeRecommend(String key) {
        return Prefers.getString("home_recommend_" + key, "");
    }

    public static void putHomeRecommend(String key, String value) {
        Prefers.put("home_recommend_" + key, value);
    }

    public static String getTmdbApiUrl() {
        String url = Prefers.getString("tmdb_api_url", "");
        return url.isEmpty() ? "https://api.tmdb.org/3" : url;
    }

    public static void putTmdbApiUrl(String url) {
        Prefers.put("tmdb_api_url", url);
    }

    public static String getTmdbImageUrl() {
        String url = Prefers.getString("tmdb_image_url", "");
        return url.isEmpty() ? "https://image.tmdb.org/t/p" : url;
    }

    public static void putTmdbImageUrl(String url) {
        Prefers.put("tmdb_image_url", url);
    }

    public static String getTmdbApiKey() {
        return Prefers.getString("tmdb_api_key", "");
    }

    public static void putTmdbApiKey(String key) {
        Prefers.put("tmdb_api_key", key);
    }

    public static boolean hasTmdbApiKey() {
        return !getTmdbApiKey().isEmpty();
    }

    public static String getTmdbBackdrop(String name) {
        return Prefers.getString("tmdb_backdrop_v2_" + name, "");
    }

    public static void putTmdbBackdrop(String name, String url) {
        Prefers.put("tmdb_backdrop_v2_" + name, url);
    }

    public static String getTmdbOverview(String name) {
        return Prefers.getString("tmdb_overview_v2_" + name, "");
    }

    public static void putTmdbOverview(String name, String overview) {
        Prefers.put("tmdb_overview_v2_" + name, overview);
    }

    public static List<String> getTmdbBackdrops(String name) {
        String value = Prefers.getString("tmdb_backdrops_v2_" + name, "");
        if (TextUtils.isEmpty(value)) return new ArrayList<>();
        String[] split = value.split("\\|");
        List<String> list = new ArrayList<>();
        for (String url : split) {
            if (!TextUtils.isEmpty(url)) list.add(url);
        }
        return list;
    }

    public static void putTmdbBackdrops(String name, List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            Prefers.put("tmdb_backdrops_v2_" + name, "");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String url : urls) {
            if (sb.length() > 0) sb.append("|");
            sb.append(url);
        }
        Prefers.put("tmdb_backdrops_v2_" + name, sb.toString());
    }

    public static String getTmdbLogo(String name) {
        String value = Prefers.getString("tmdb_logo_v2_" + name, "");
        // 哨兵 "none" 表示已查询过但确实没有 Logo，对外当作空处理
        return "none".equals(value) ? "" : value;
    }

    public static boolean isTmdbLogoDecided(String name) {
        // 仅当曾经写入过 tmdb_logo_v2_ 时才认为已查询（含 none 哨兵）
        return Prefers.getString("tmdb_logo_v2_" + name, null) != null;
    }

    public static void putTmdbLogo(String name, String url) {
        // 没有 Logo 时写入哨兵，避免后续重复请求却永远拿不到
        Prefers.put("tmdb_logo_v2_" + name, TextUtils.isEmpty(url) ? "none" : url);
    }

    public static float getDetailScrimAlpha() {
        return Prefers.getInt("detail_scrim_alpha", 50) / 100f;
    }

    public static void putDetailScrimAlpha(int percent) {
        Prefers.put("detail_scrim_alpha", Math.max(0, Math.min(100, percent)));
    }
}
