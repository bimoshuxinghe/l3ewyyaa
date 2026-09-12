package com.fongmi.android.tv.player.exo;

import static androidx.media3.common.C.RESULT_END_OF_INPUT;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.TransferListener;

import com.fongmi.android.tv.setting.PlayerSetting;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 智能去广数据源：包在 HTTP 数据源外面，只对 m3u8 清单响应做广告过滤，
 * 视频切片仍由上游直连，无性能损耗。
 * <ul>
 *   <li>响应为 m3u8 清单（URL 含 .m3u8/.m3u 或 Content-Type 为 mpegurl）时：整读 → {@link M3u8AdFilter#filter} → 返回过滤后文本</li>
 *   <li>其他响应（ts 切片、mp4、字幕等）：原样透传</li>
 * </ul>
 * 开关：{@link PlayerSetting#isAdFilter()}，关闭时完全透传。
 */
public class FilteringHttpDataSource implements HttpDataSource {

    private final HttpDataSource upstream;
    private byte[] cached;
    private int cachedPos;

    public FilteringHttpDataSource(HttpDataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
        cached = null;
        cachedPos = 0;
        long length = upstream.open(dataSpec);
        try {
            if (PlayerSetting.isAdFilter() && isPlaylist(dataSpec.uri, upstream.getResponseHeaders())) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = upstream.read(buf, 0, buf.length)) != RESULT_END_OF_INPUT && n > 0) {
                    bos.write(buf, 0, n);
                }
                String filtered = M3u8AdFilter.filter(new String(bos.toByteArray(), StandardCharsets.UTF_8));
                cached = filtered.getBytes(StandardCharsets.UTF_8);
                cachedPos = 0;
                return cached.length;
            }
        } catch (Throwable ignored) {
            // 过滤失败退回原样（不阻塞播放）
            cached = null;
        }
        return length;
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int readLength) {
        if (cached != null) {
            int n = Math.min(readLength, cached.length - cachedPos);
            if (n <= 0) return RESULT_END_OF_INPUT;
            System.arraycopy(cached, cachedPos, buffer, offset, n);
            cachedPos += n;
            return n;
        }
        return upstream.read(buffer, offset, readLength);
    }

    @Override
    public void close() throws IOException {
        cached = null;
        cachedPos = 0;
        upstream.close();
    }

    @Override
    public @Nullable Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    private static boolean isPlaylist(@Nullable Uri uri, Map<String, List<String>> headers) {
        if (uri != null) {
            String path = uri.getPath();
            if (path != null) {
                String lower = path.toLowerCase();
                if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) return true;
            }
        }
        try {
            List<String> ct = headers.get("Content-Type");
            if (ct != null && !ct.isEmpty()) {
                String type = ct.get(0).toLowerCase();
                if (type.contains("mpegurl") || type.contains("vnd.apple")) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ---- HttpDataSource 接口转发 ----

    @Override
    public void setRequestProperty(@NonNull String name, @NonNull String value) {
        upstream.setRequestProperty(name, value);
    }

    @Override
    public void clearRequestProperty(@NonNull String name) {
        upstream.clearRequestProperty(name);
    }

    @Override
    public void clearAllRequestProperties() {
        upstream.clearAllRequestProperties();
    }

    @Override
    public int getResponseCode() {
        return upstream.getResponseCode();
    }

    @Override
    public @NonNull Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }
}
