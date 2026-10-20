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
 * 内置央视频直播源（/ysp）→ ysp-live v9.5 Python 网关（127.0.0.1:9979）：
 *  - /ysp?list=live   全频道订阅（转发网关 /all.m3u，并把频道地址统一指向本机网关）
 *  - /ysp/&lt;path&gt;      透传给网关（回看、诊断等）
 * 本类只做 HTTP 转发，不经过 Java↔Python 桥：网关线程由 App 启动时拉起，
 * 首启（解压 assets）未就绪时按 500ms 重试，最长 8s，仍失败返回占位列表。
 */
public class Ysp implements Process {

    private static final String GATEWAY = "http://127.0.0.1:9979";
    private static final String MIME_M3U = "application/vnd.apple.mpegurl";
    private static final long WAIT_MS = 8000;
    private static final long RETRY_MS = 500;

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/ysp");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> params = session.getParms();
            // 旧版取流地址（fun=cctv）随 live_ysp 一并移除，明确报错让用户刷新列表
            if (params.containsKey("fun")) {
                return newFixedLengthResponse(Status.NOT_FOUND, MIME_PLAINTEXT, "央视频旧版取流地址已失效，请重新加载直播源列表");
            }
            boolean list = url.equals("/ysp") || url.equals("/ysp/");
            String target = list ? GATEWAY + "/all.m3u" + buildQuery(params) : GATEWAY + url.substring(4) + buildQuery(params);
            String body = fetchUntilReady(target);
            if (body == null || body.isEmpty()) {
                return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "#EXTM3U\n# 央视频列表加载中，请稍后重试\n");
            }
            if (list) body = rewriteOrigin(body);
            return newFixedLengthResponse(Status.OK, list ? MIME_PLAINTEXT : MIME_M3U, body);
        } catch (Throwable e) {
            return Nano.error(e.getMessage());
        }
    }

    /** 列表参数透传（网关 /all.m3u 只认 group / k4，list 本身是本类的路由标记）。 */
    private String buildQuery(Map<String, String> params) {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if ("list".equals(e.getKey())) continue;
            if (q.length() > 0) q.append('&');
            q.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        return q.length() == 0 ? "" : "?" + q;
    }

    /** 网关未就绪（首启解压 assets、端口尚未绑定）时重试，最长 8s；HTTP 层错误不重试。 */
    private String fetchUntilReady(String target) throws Exception {
        long deadline = System.currentTimeMillis() + WAIT_MS;
        while (true) {
            String body = fetch(target);
            if (body != null) return body;
            if (System.currentTimeMillis() >= deadline) return null;
            Thread.sleep(RETRY_MS);
        }
    }

    /** 频道地址统一改写到本机网关：列表由 Java 转发（Host 是本机服务地址），
     *  播放则直连 Python 网关，少一次转发、回看参数也能被网关识别。 */
    private static String rewriteOrigin(String body) {
        String origin = null;
        for (String line : body.split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int scheme = s.indexOf("://");
            if (scheme <= 0) continue;
            int slash = s.indexOf('/', scheme + 3);
            if (slash < 0) continue;
            origin = s.substring(0, slash);
            break;
        }
        if (origin == null || origin.equals(GATEWAY)) return body;
        return body.replace(origin, GATEWAY);
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** 连接失败返回 null（需要重试），HTTP 层错误返回空串（直接占位）。 */
    private String fetch(String u) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code != 200) return "";
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
