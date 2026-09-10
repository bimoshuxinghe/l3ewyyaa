package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * 内置央视频直播源（/ysp）：
 *  - /ysp?list=live   合并频道列表（央视频在上 + 咪咕在下）
 *  - /ysp?fun=cctv&id=xxx  央视频取流/回看
 * 列表合并与取流全部在 Python（migu_server 9979 的 /ysp 路由）内完成，
 * Java 只做 HTTP 转发——不经过 Java↔Python 桥，避免首次初始化慢导致列表超时。
 */
public class Ysp implements Process {

    private static final String YSP_BASE = "http://127.0.0.1:9979/ysp";
    private static final String MIME_M3U = "application/vnd.apple.mpegurl";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/ysp");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> params = session.getParms();
            StringBuilder q = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (q.length() > 0) q.append('&');
                q.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            }
            String body = fetch(YSP_BASE + "?" + q);
            if (body == null || body.isEmpty()) {
                body = "#EXTM3U\n# 列表加载中，请稍后重试\n";
            }
            String mime = "list".equals(params.get("list")) ? MIME_PLAINTEXT : MIME_M3U;
            return newFixedLengthResponse(Status.OK, mime, body);
        } catch (Throwable e) {
            return Nano.error(e.getMessage());
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private String fetch(String u) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(u).openConnection();
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            conn.disconnect();
        }
    }
}
