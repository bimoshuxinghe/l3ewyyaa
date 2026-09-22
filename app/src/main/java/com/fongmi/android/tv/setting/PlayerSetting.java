package com.fongmi.android.tv.setting;

import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class PlayerSetting {

    public static final int ENGINE_EXO = 0;

    public static int getEngine() {
        return ENGINE_EXO;
    }

    public static void putEngine(int engine) {
        Prefers.put("player_engine", ENGINE_EXO);
    }

    public static boolean isAdFilter() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdFilter(boolean adFilter) {
        Prefers.put("adblock", adFilter);
    }

    public static boolean isPreload() {
        return Prefers.getBoolean("preload", false);
    }

    public static void putPreload(boolean preload) {
        Prefers.put("preload", preload);
    }

    public static boolean isPreloadNext() {
        return Prefers.getBoolean("preload_next", false);
    }

    public static void putPreloadNext(boolean preloadNext) {
        Prefers.put("preload_next", preloadNext);
    }

    public static int getPreloadThread() {
        return Math.min(Math.max(Prefers.getInt("preload_thread", 1), 1), 5);
    }

    public static void putPreloadThread(int thread) {
        Prefers.put("preload_thread", Math.min(Math.max(thread, 1), 5));
    }

    public static int getPreloadCapacity() {
        return Math.min(Math.max(Prefers.getInt("preload_capacity", 128), 32), 512);
    }

    public static void putPreloadCapacity(int capacity) {
        Prefers.put("preload_capacity", Math.min(Math.max(capacity, 32), 512));
    }

    public static int getPreloadSeconds() {
        return Math.min(Math.max(Prefers.getInt("preload_seconds", 120), 10), 300);
    }

    public static void putPreloadSeconds(int seconds) {
        Prefers.put("preload_seconds", Math.min(Math.max(seconds, 10), 300));
    }

    public static int getControllerTransparency() {
        return Math.min(Math.max(Prefers.getInt("controller_alpha", 80), 0), 90);
    }

    public static void putControllerTransparency(int transparency) {
        Prefers.put("controller_alpha", Math.min(Math.max(transparency, 0), 90));
    }

    public static void applyControllerTransparency(View view) {
        if (view == null) return;
        Drawable background = view.getBackground();
        if (background == null) return;
        background.mutate().setAlpha(Math.round((100 - getControllerTransparency()) * 2.55f));
    }

    public static boolean isExoDolbyVisionPassthrough() {
        return Prefers.getBoolean("exo_dolby_vision_passthrough", true);
    }

    public static void putExoDolbyVisionPassthrough(boolean passthrough) {
        Prefers.put("exo_dolby_vision_passthrough", passthrough);
    }

    public static int getRender() {
        return Prefers.getInt("render", 0);
    }

    public static void putRender(int render) {
        Prefers.put("render", render);
    }

    public static int getSize() {
        return Prefers.getInt("size", 2);
    }

    public static void putSize(int size) {
        Prefers.put("size", size);
    }

    public static int getScale() {
        return Prefers.getInt("scale");
    }

    public static void putScale(int scale) {
        Prefers.put("scale", scale);
    }

    public static int getBuffer() {
        return Math.min(Math.max(Prefers.getInt("buffer"), 1), 10);
    }

    public static void putBuffer(int buffer) {
        Prefers.put("buffer", buffer);
    }

    public static int getBackground() {
        return Prefers.getInt("background", 0);
    }

    public static void putBackground(int background) {
        Prefers.put("background", background);
    }

    public static boolean isBackgroundOff() {
        return getBackground() == 0;
    }

    public static boolean isBackgroundOn() {
        return getBackground() == 1 || getBackground() == 2;
    }

    public static boolean isBackgroundPiP() {
        return getBackground() == 2;
    }

    public static boolean isHomeMute() {
        return Prefers.getBoolean("home_mute", false);
    }

    public static void putHomeMute(boolean homeMute) {
        Prefers.put("home_mute", homeMute);
    }

    public static boolean isHomeCarousel() {
        return Prefers.getBoolean("home_carousel", true);
    }

    public static void putHomeCarousel(boolean homeCarousel) {
        Prefers.put("home_carousel", homeCarousel);
    }

    public static boolean isDetailPoster() {
        return Prefers.getBoolean("detail_poster", true);
    }

    public static void putDetailPoster(boolean detailPoster) {
        Prefers.put("detail_poster", detailPoster);
    }

    public static float getLrcTextSize() {
        return Math.min(Math.max(Prefers.getFloat("lrc_text_size", 56f), 24f), 80f);
    }

    public static void putLrcTextSize(float size) {
        Prefers.put("lrc_text_size", Math.min(Math.max(size, 24f), 80f));
    }

    public static int getLrcColor() {
        return Prefers.getInt("lrc_color", 0xFFFFD700);
    }

    public static void putLrcColor(int color) {
        Prefers.put("lrc_color", color);
    }

    public static float getSpeed() {
        return Math.min(Math.max(Prefers.getFloat("speed", 3), 2), 5);
    }

    public static void putSpeed(float speed) {
        Prefers.put("speed", speed);
    }

    public static boolean isCaption() {
        return Prefers.getBoolean("caption");
    }

    public static void putCaption(boolean caption) {
        Prefers.put("caption", caption);
    }

    public static boolean hasCaption() {
        return new Intent(Settings.ACTION_CAPTIONING_SETTINGS).resolveActivity(App.get().getPackageManager()) != null;
    }

    public static boolean isTunnel() {
        return Prefers.getBoolean("tunnel");
    }

    public static void putTunnel(boolean tunnel) {
        Prefers.put("tunnel", tunnel);
    }

    public static boolean isAudioPrefer() {
        return Prefers.getBoolean("audio_prefer");
    }

    public static void putAudioPrefer(boolean audioPrefer) {
        Prefers.put("audio_prefer", audioPrefer);
    }

    public static boolean isVideoPrefer() {
        return Prefers.getBoolean("video_prefer");
    }

    public static void putVideoPrefer(boolean videoPrefer) {
        Prefers.put("video_prefer", videoPrefer);
    }

    public static boolean isPreferAAC() {
        return Prefers.getBoolean("prefer_aac");
    }

    public static void putPreferAAC(boolean preferAAC) {
        Prefers.put("prefer_aac", preferAAC);
    }

    public static float getSubtitleTextSize() {
        return Prefers.getFloat("subtitle_text_size");
    }

    public static void putSubtitleTextSize(float value) {
        Prefers.put("subtitle_text_size", value);
    }

    public static float getSubtitlePosition() {
        return Prefers.getFloat("subtitle_position");
    }

    public static void putSubtitlePosition(float value) {
        Prefers.put("subtitle_position", value);
    }

}
