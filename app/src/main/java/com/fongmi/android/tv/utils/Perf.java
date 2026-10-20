package com.fongmi.android.tv.utils;

import android.os.Build;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * 设备性能档位识别：用于晶晨(Amlogic)等低端电视盒子上的画质/流畅度与 UI 降级策略。
 */
public final class Perf {

    private static Boolean lowEnd;
    private static Boolean amlogic;

    private Perf() {
    }

    /** 是否为晶晨(Amlogic)方案：S905L/S905X/S912/A311D/S922X 等。 */
    public static boolean isAmlogic() {
        if (amlogic != null) return amlogic;
        String hardware = String.valueOf(Build.HARDWARE).toLowerCase();
        String board = String.valueOf(Build.BOARD).toLowerCase();
        String product = String.valueOf(Build.PRODUCT).toLowerCase();
        String device = String.valueOf(Build.DEVICE).toLowerCase();
        String cpu = readHardwareLine().toLowerCase();
        amlogic = hardware.contains("amlogic") || board.contains("amlogic") || product.contains("amlogic")
                || device.contains("amlogic") || cpu.contains("amlogic") || cpu.contains("meson")
                || board.equals("gxl") || board.startsWith("g12a") || board.startsWith("sm1") || board.startsWith("sc2");
        return amlogic;
    }

    /**
     * 低端设备：晶晨方案，或 CPU <= 4 核且内存 <= 2GB 的盒子。
     * 低端机上禁用背景图轮换、缩小图片解码尺寸、关闭过渡动画，并让 MPV 使用最省 GPU 的渲染参数。
     */
    public static boolean isLowEnd() {
        if (lowEnd != null) return lowEnd;
        lowEnd = isAmlogic() || (Runtime.getRuntime().availableProcessors() <= 4 && totalMemoryMb() > 0 && totalMemoryMb() <= 2048);
        return lowEnd;
    }

    /** 图片解码缩放：低端机取 1/2，避免大图解码占用主线程与 GPU。 */
    public static float imageScale() {
        return isLowEnd() ? 0.5f : 1.0f;
    }

    private static long totalMemoryMb() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String line = reader.readLine();
            if (line == null) return 0;
            return Long.parseLong(line.replaceAll("\\D+", "")) / 1024;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readHardwareLine() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase().startsWith("hardware")) return line;
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
