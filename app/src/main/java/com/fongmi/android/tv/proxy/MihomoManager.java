package com.fongmi.android.tv.proxy;

import android.net.Uri;
import android.text.TextUtils;

/**
 * 内置 mihomo 内核的接入层。
 *
 * <p>历史实现会以进程方式拉起随包携带的 {@code libmihomo.so}，用于把 Clash 订阅里的
 * VMess/VLESS/Trojan/SS/Hysteria2/AnyTLS 等加密节点转换成本地 HTTP 代理。
 *
 * <p>该二进制由 Go 构建并声明 {@code minSdk=34}，同时也依赖 API 23 起才存在的 bionic 符号，
 * 因此在 Android 14 以下设备上无法加载运行。自本版本起不再随包分发该二进制，
 * 本类降级为平台可用性开关：始终返回「不可用」，让上层走 HTTP/SOCKS 节点直连逻辑。
 *
 * <p>HTTP/HTTPS/SOCKS5 直连节点、订阅解析、延迟测速、自动择优，以及向爬虫注入
 * {@code mergeExt()} 的能力全部保留，不受影响。
 */
public class MihomoManager {

    /** 保留原端口常量：上层用于识别「本地 mihomo 代理」的历史选中值。 */
    private static final int MIXED_PORT = 18890;
    private static final int CONTROLLER_PORT = 18891;

    private static final String UNAVAILABLE_MSG =
            "内置 mihomo 内核已移除（原内核要求 Android 14 及以上）。"
                    + "加密节点不可用，请改用 HTTP / HTTPS / SOCKS5 直连节点。";

    private static class Loader {
        static volatile MihomoManager INSTANCE = new MihomoManager();
    }

    public static MihomoManager get() {
        return Loader.INSTANCE;
    }

    /**
     * 内置内核是否在当前平台可用。始终为 false —— 二进制已移除。
     */
    public static boolean isSupported() {
        return false;
    }

    public static String getProxyUrl() {
        return getProxyUrl("Mihomo");
    }

    public static int getMixedPort() {
        return MIXED_PORT;
    }

    public static int getControllerPort() {
        return CONTROLLER_PORT;
    }

    public static String getProxyUrl(String name) {
        return "http://127.0.0.1:" + MIXED_PORT + "#" + Uri.encode(TextUtils.isEmpty(name) ? "Mihomo" : name);
    }

    public String getLastError() {
        return UNAVAILABLE_MSG;
    }

    public String getLog() {
        return "";
    }

    public synchronized boolean start(String config) {
        return start(config, "");
    }

    public synchronized boolean start(String config, String selected) {
        return false;
    }

    public synchronized void stop() {
    }

    public synchronized boolean isRunning() {
        return false;
    }
}
