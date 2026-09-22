package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.live.cctv.YspChannels;
import com.fongmi.android.tv.live.cctv.YspEpg;
import com.fongmi.android.tv.live.cctv.YspSpider;
import com.fongmi.android.tv.live.migu.MiguCore;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * 内置直播源路由（/ysp）——央视频 + 咪咕，纯 Java 实现。
 *
 * <p>路由表：
 * <ul>
 *   <li>{@code /ysp?list=live} —— 合并 M3U 列表（央视频在上 + 咪咕在下）</li>
 *   <li>{@code /ysp?list=migu} —— 仅咪咕 M3U 列表</li>
 *   <li>{@code /ysp?epg[&date=yyyyMMdd]} —— 央视频 XMLTV 节目单（回看依赖它）</li>
 *   <li>{@code /ysp?fun=cctv&id=<pid>[&playseek=...]} —— 央视频直播 / 回看，返回 m3u8</li>
 *   <li>{@code /ysp?fun=migu&id=<pid>} —— 咪咕取流，302 重定向到 CDN</li>
 * </ul>
 *
 * <p><b>端口</b>：本机 Nano 端口是动态的（{@code Server} 从 9978 起自动挑可用端口），
 * 因此所有回写给播放器的地址都必须用 {@link Server#getAddress(boolean)} 实时拼接，
 * 绝不能硬编码。
 */
public class Ysp implements Process {

    private static final String MIME_M3U = "application/vnd.apple.mpegurl";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/ysp");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> params = session.getParms();

            // 1) 列表
            if (params.containsKey("list")) {
                String body = buildList(params.get("list"));
                return m3u(Status.OK, body);
            }

            // 2) EPG（回看依赖它提供节目起止时间）
            //    路径带 .xml 后缀：Live.getEpgXml() 只认含 "xml"/"gz" 的 URL
            if (params.containsKey("epg") || url.contains("epg.xml")) return handleEpg(params);

            // 3) 取流
            String fun = params.get("fun");
            if ("cctv".equals(fun)) return handleCctv(params);
            if ("migu".equals(fun)) return handleMigu(params);

            return Nano.error("未知请求");
        } catch (Throwable e) {
            return Nano.error(e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 列表

    /**
     * 构造 M3U 列表。
     *
     * @param list {@code live}=央视频+咪咕合并，{@code migu}=仅咪咕，其他值按合并处理
     */
    private String buildList(String list) {
        String base = Server.get().getAddress(true);
        if ("migu".equals(list)) {
            String migu = MiguCore.get().liveContent(base, "/ysp", "（咪咕）");
            return migu.isEmpty() ? "#EXTM3U\n# 咪咕列表加载中，请稍后重试\n" : migu;
        }
        StringBuilder sb = new StringBuilder();
        try {
            String cctv = YspSpider.get().liveContent(base);
            if (cctv != null && !cctv.trim().isEmpty()) sb.append(cctv.trim());
        } catch (Throwable ignored) {
        }
        try {
            String migu = MiguCore.get().liveContent(base, "/ysp", "（咪咕）");
            // 咪咕列表自带 #EXTM3U 头，合并时去掉，避免中途出现第二个头
            if (!migu.isEmpty()) {
                int idx = migu.indexOf('\n');
                String body = idx >= 0 ? migu.substring(idx + 1).trim() : "";
                if (!body.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append(body);
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.length() == 0 ? "#EXTM3U\n# 列表加载中，请稍后重试\n" : sb.toString() + "\n";
    }

    // ------------------------------------------------------------------ EPG

    /** 央视频 XMLTV 节目单。Catchup 的回看时间完全依赖这里。 */
    private Response handleEpg(Map<String, String> params) {
        List<YspChannels.Channel> channels = new ArrayList<>(YspChannels.all().values());
        String date = params.get("date");
        String xml = YspEpg.getAll(channels, date);
        if (xml.isEmpty()) {
            // 返回合法但空的 XMLTV，避免 EpgParser 解析异常
            xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv generator-info-name=\"YspEpg\"></tv>\n";
        }
        Response res = newFixedLengthResponse(Status.OK, "application/xml", xml);
        res.addHeader("Access-Control-Allow-Origin", "*");
        res.addHeader("Cache-Control", "no-store");
        return res;
    }

    // ------------------------------------------------------------------ 取流

    /** 央视频直播 / 回看。 */
    private Response handleCctv(Map<String, String> params) {
        String id = params.get("id");
        if (id == null || id.isEmpty()) return m3u(Status.INTERNAL_ERROR, "# 缺少频道ID");
        String playseek = params.get("playseek");
        Object[] result = (playseek != null && !playseek.isEmpty())
                ? YspSpider.get().playback(id, playseek)
                : YspSpider.get().live(id);
        return m3u(Status.OK, (String) result[2]);
    }

    /** 咪咕取流：302 重定向到 CDN。 */
    private Response handleMigu(Map<String, String> params) {
        String id = params.get("id");
        if (id == null || id.isEmpty()) return Nano.error("缺少频道ID");
        String purl = MiguCore.get().serve(id);
        if (purl == null) {
            return newFixedLengthResponse(Status.OK, MIME_PLAINTEXT, "取流失败：咪咕接口无返回");
        }
        // 透传调用方附加的 query（回看参数等）
        String extra = extraQuery(params);
        if (!extra.isEmpty()) purl = purl + (purl.contains("?") ? "&" : "?") + extra;

        Response res = newFixedLengthResponse(Status.REDIRECT, MIME_PLAINTEXT, "");
        res.addHeader("Location", purl);
        res.addHeader("Cache-Control", "no-store");
        return res;
    }

    /** 把除 id/fun/list 之外的参数拼回 query，供咪咕回看等场景透传。 */
    private static String extraQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            String k = e.getKey();
            if ("id".equals(k) || "fun".equals(k) || "list".equals(k)) continue;
            String v = e.getValue();
            if (v == null || v.isEmpty()) continue;
            if (sb.length() > 0) sb.append('&');
            sb.append(k).append('=').append(v);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 响应

    /** 统一 m3u8 响应：所有分支都带上正确的 Content-Type，避免播放器按文本解析。 */
    private static Response m3u(Status status, String body) {
        Response res = newFixedLengthResponse(status, MIME_M3U, body == null ? "" : body);
        res.addHeader("Access-Control-Allow-Origin", "*");
        res.addHeader("Cache-Control", "no-store");
        return res;
    }
}
