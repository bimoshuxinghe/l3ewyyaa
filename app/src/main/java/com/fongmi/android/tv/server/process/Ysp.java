package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.chaquo.YspBridge;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * 内置央视频直播源（/ysp）：
 *  - /ysp?list=live   合并频道列表（央视频 M3U 在上 + 咪咕 9979 列表在下，均转 M3U）
 *  - /ysp?id=xxx      央视频取流/回看：转发到内置 py live_ysp.localProxy（经 chaquo YspBridge）
 */
public class Ysp implements Process {

    private static final String MIGU_LIST = "http://127.0.0.1:9979/migu?list=live";
    private static final String MIME_M3U = "application/vnd.apple.mpegurl";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/ysp");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> params = session.getParms();
            if ("list".equals(params.get("list"))) {
                return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, mergeList());
            }
            return stream(params);
        } catch (Throwable e) {
            return Nano.error(e.getMessage());
        }
    }

    /** 合并频道列表：央视频（内置 py）在上，咪咕（9979）在下。 */
    private String mergeList() {
        StringBuilder sb = new StringBuilder();
        String ysp = YspBridge.liveList();
        if (ysp != null && !ysp.trim().isEmpty()) sb.append(ysp.trim()).append("\n\n");
        try {
            String migu = fetch(MIGU_LIST);
            if (migu != null && !migu.trim().isEmpty()) {
                String m3u = txtToM3u(migu);
                if (!m3u.isEmpty()) sb.append(m3u);
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "#EXTM3U\n" : sb.toString();
    }

    /** 咪咕 TXT（组名,#genre# / 频道,url#）→ M3U 行。 */
    private String txtToM3u(String txt) {
        StringBuilder sb = new StringBuilder();
        String group = "";
        for (String line : txt.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            int idx = line.indexOf(',');
            if (idx <= 0) continue;
            String name = line.substring(0, idx).trim();
            String rest = line.substring(idx + 1).trim();
            if (rest.contains("#genre#")) {
                group = name;
                continue;
            }
            if (rest.endsWith("#")) rest = rest.substring(0, rest.length() - 1);
            if (!rest.contains("://")) continue;
            sb.append("#EXTINF:-1 group-title=\"").append(group).append("\",").append(name).append("\n").append(rest).append("\n");
        }
        return sb.toString();
    }

    /** 央视频取流：转发到内置 py live_ysp.localProxy。 */
    private Response stream(Map<String, String> params) throws Exception {
        Map<String, String> p = new HashMap<>(params);
        p.putIfAbsent("fun", "cctv");
        String[] rs = YspBridge.stream(p);
        int status;
        try {
            status = Integer.parseInt(rs[0]);
        } catch (Exception e) {
            status = 200;
        }
        String mime = rs[1] == null || rs[1].isEmpty() ? MIME_M3U : rs[1];
        String body = rs[2] == null ? "" : rs[2];
        if (status >= 500) status = 200; // 绝不回 5xx 给播放器（杜绝 Bad HTTP Status），占位 m3u 内含错误注释
        return newFixedLengthResponse(Status.lookup(status), mime, body);
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
