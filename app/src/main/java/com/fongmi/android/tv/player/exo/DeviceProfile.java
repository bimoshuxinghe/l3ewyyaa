package com.fongmi.android.tv.player.exo;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PlayerSetting;

import java.util.Locale;

/**
 * 设备性能档位判定。
 *
 * <p>905L3 系列（S905L3 / S905L3A / S905L3B / S905L3Y）这类运营商机顶盒是四核 A53 + 1~2GB 内存，
 * 硬解 4K 的余量极小，一旦解码器回落软件解码就会把 CPU 打满，表现为画面卡死、遥控器按键无响应。
 * 这里把这些机器识别为「低配」，播放参数和弹幕渲染策略都会切到保守档；高配机行为完全不变。
 */
public final class DeviceProfile {

    /** 自动识别。 */
    public static final int MODE_AUTO = 0;
    /** 强制按低配处理。 */
    public static final int MODE_FORCE_LOW = 1;
    /** 强制按高配处理。 */
    public static final int MODE_FORCE_HIGH = 2;

    /** 低配机最小缓冲：15 秒（原生默认 50 秒，4K 下常驻内存约 125MB）。 */
    public static final int LOW_SPEC_MIN_BUFFER_MS = 15_000;
    /** 低配机最大缓冲：30 秒。 */
    public static final int LOW_SPEC_MAX_BUFFER_MS = 30_000;
    /** 低配机起播缓冲：0.5 秒。 */
    public static final int LOW_SPEC_BUFFER_FOR_PLAYBACK_MS = 500;
    /** 低配机卡顿后恢复缓冲：1 秒。 */
    public static final int LOW_SPEC_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_000;
    /**
     * 低配机缓冲字节上限：64MB。
     *
     * <p>配合 {@code prioritizeTimeOverSizeThresholds=false} 使用，播放器需同时满足时间与字节两个
     * 阈值才继续加载，从而把 4K 播放的内存占用硬性压住。
     */
    public static final int LOW_SPEC_TARGET_BUFFER_BYTES = 64 * 1024 * 1024;

    /** 低配机视频轨道高度上限：1080。 */
    public static final int LOW_SPEC_MAX_VIDEO_HEIGHT = 1080;
    /** 低配机视频轨道码率上限：20Mbps。 */
    public static final int LOW_SPEC_MAX_VIDEO_BITRATE = 20_000_000;

    @Nullable
    private static volatile Boolean lowSpecCache;

    private DeviceProfile() {
    }

    /** 当前是否为低配机。结果会被缓存，修改档位设置时需调用 {@link #invalidate()}。 */
    public static boolean isLowSpec() {
        @Nullable Boolean cached = lowSpecCache;
        if (cached != null) return cached;
        boolean result;
        int mode = PlayerSetting.getLowSpecMode();
        if (mode == MODE_FORCE_LOW) {
            result = true;
        } else if (mode == MODE_FORCE_HIGH) {
            result = false;
        } else {
            result = detectLowSpec();
        }
        lowSpecCache = result;
        return result;
    }

    /** 清除档位缓存，下一次 {@link #isLowSpec()} 会重新判定。 */
    public static void invalidate() {
        lowSpecCache = null;
    }

    private static boolean detectLowSpec() {
        if (matchesKnownLowEndBoard()) return true;
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores > 0 && cores <= 4) {
            long totalMemMb = getTotalMemoryMb();
            // 四核及以下 + 2GB 以内内存，基本就是运营商定制盒子的配置。
            if (totalMemMb <= 0 || totalMemMb <= 2048) return true;
        }
        return false;
    }

    private static boolean matchesKnownLowEndBoard() {
        String text = (safe(Build.DEVICE) + " " + safe(Build.BOARD) + " " + safe(Build.MODEL) + " "
                + safe(Build.HARDWARE) + " " + safe(Build.PRODUCT)).toLowerCase(Locale.US);
        // S905L3 / S905L3A / S905L3B / S905L3Y 统一命中 "905l3"。
        return text.contains("905l3") || text.contains("905l2") || text.contains("s805");
    }

    private static long getTotalMemoryMb() {
        try {
            ActivityManager manager = (ActivityManager) App.get().getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return 0L;
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            manager.getMemoryInfo(info);
            return info.totalMem / (1024L * 1024L);
        } catch (Throwable e) {
            return 0L;
        }
    }

    private static String safe(@Nullable String value) {
        return value == null ? "" : value;
    }
}
