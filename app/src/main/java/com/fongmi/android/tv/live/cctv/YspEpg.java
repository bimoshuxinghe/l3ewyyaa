package com.fongmi.android.tv.live.cctv;

import android.util.Log;

import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 央视频 EPG（电子节目单）——把 {@code api.cntv.cn} 的节目单接口转成 XMLTV。
 *
 * <p>项目里 {@code Catchup} 的回看时间来自 EPG：{@code LiveActivity.onItemClick()} 拿到
 * 用户点选的节目后，用它的 {@code startTime/endTime} 渲染 {@code playseek}。因此
 * <b>没有 EPG 就没有可用的回看</b>——这也是本类存在的唯一理由。
 *
 * <p>接口：{@code https://api.cntv.cn/epg/epginfo3?serviceId=tvcctv&c=<code>&d=<yyyyMMdd>&cb=t}
 * <pre>
 * t({"cctv1":{
 *      "isLive":"节目名","liveSt":1790095140,"channelName":"CCTV-1 综合",
 *      "program":[{"t":"节目名","st":1790095140,"et":1790096460,"showTime":"00:39"},...]
 * }});
 * </pre>
 *
 * <p>输出 XMLTV：{@code <channel id="CCTV1"><display-name>CCTV1</display-name></channel>}
 * 加 {@code <programme start="..." stop="..." channel="CCTV1"><title>...</title></programme>}。
 * 频道 id 用本地的 {@code Channel.name}，因为 {@code EpgParser.prepareLiveChannels()}
 * 会同时用 tvg-id / tvg-name / name 三个键去匹配。
 */
public final class YspEpg {

    private static final String TAG = "YspEpg";
    private static final String API = "https://api.cntv.cn/epg/epginfo3";
    private static final String SERVICE_ID = "tvcctv";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36";

    /** XMLTV 时间格式：yyyyMMddHHmmss + 时区偏移 */
    private static final DateTimeFormatter XMLTV_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss Z");

    private static final long CACHE_TTL = 30 * 60 * 1000L; // 30 分钟

    /** 本地 pid -> [xmltv 文本, 生成时间戳] */
    private static final Map<String, Object[]> CACHE = new ConcurrentHashMap<>();

    private YspEpg() {
    }

    /**
     * 取某频道某天的 XMLTV 片段。
     *
     * @param channel 频道
     * @param date    {@code yyyyMMdd}，为空取今天
     * @return XMLTV 文本；无节目单或失败返回空串（调用方据此回退）
     */
    public static String get(YspChannels.Channel channel, String date) {
        String code = YspChannels.epgCode(channel);
        if (code == null) return "";

        String d = (date == null || date.isEmpty())
                ? java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                : date;

        String key = channel.id + "_" + d;
        Object[] cached = CACHE.get(key);
        if (cached != null && System.currentTimeMillis() - (Long) cached[1] < CACHE_TTL) {
            return (String) cached[0];
        }

        String xml = fetch(channel, code, d);
        CACHE.put(key, new Object[]{xml, System.currentTimeMillis()});
        return xml;
    }

    private static String fetch(YspChannels.Channel channel, String code, String date) {
        try {
            String url = API + "?serviceId=" + SERVICE_ID + "&c=" + code + "&d=" + date + "&cb=t";
            String body = OkHttp.string(url, Map.of("User-Agent", UA));
            if (body == null || body.isEmpty()) return "";

            // 剥掉 JSONP 外壳 t(...);
            int start = body.indexOf('(');
            int end = body.lastIndexOf(')');
            if (start < 0 || end <= start) return "";
            String json = body.substring(start + 1, end).trim();

            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject node = root.has(code) ? root.getAsJsonObject(code) : null;
            if (node == null) {
                // 接口对多频道返回以请求名为键；单个频道时键名可能不同，取第一个对象兜底
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    if (e.getValue().isJsonObject()) {
                        node = e.getValue().getAsJsonObject();
                        break;
                    }
                }
            }
            if (node == null || !node.has("program")) return "";

            JsonArray programs = node.getAsJsonArray("program");
            if (programs == null || programs.isEmpty()) return "";

            return buildXmltv(channel, programs);
        } catch (Throwable t) {
            Log.w(TAG, "fetch failed " + channel.id + ": " + t);
            return "";
        }
    }

    private static String buildXmltv(YspChannels.Channel channel, JsonArray programs) {
        ZoneId zone = ZoneId.systemDefault();
        String id = channel.name;

        StringBuilder sb = new StringBuilder();
        sb.append("<channel id=\"").append(escape(id)).append("\">")
                .append("<display-name>").append(escape(id)).append("</display-name>")
                .append("</channel>\n");

        int written = 0;
        for (JsonElement el : programs) {
            if (!el.isJsonObject()) continue;
            JsonObject p = el.getAsJsonObject();
            long st = optLong(p, "st");
            long et = optLong(p, "et");
            String title = optString(p, "t");
            if (st <= 0 || et <= st || title.isEmpty()) continue;

            sb.append("<programme start=\"")
                    .append(XMLTV_TIME.format(Instant.ofEpochSecond(st).atZone(zone)))
                    .append("\" stop=\"")
                    .append(XMLTV_TIME.format(Instant.ofEpochSecond(et).atZone(zone)))
                    .append("\" channel=\"").append(escape(id)).append("\">")
                    .append("<title lang=\"zh\">").append(escape(title)).append("</title>")
                    .append("</programme>\n");
            written++;
        }
        return written == 0 ? "" : sb.toString();
    }

    /**
     * 批量取多个频道的 EPG，拼成一份完整 XMLTV 文档。
     *
     * <p>接口支持 {@code c=a,b,c} 批量查询，为了减少请求数这里按批拉取。
     *
     * @return 完整 XMLTV；全部为空时返回空串
     */
    public static String getAll(List<YspChannels.Channel> channels, String date) {
        if (channels == null || channels.isEmpty()) return "";
        String d = (date == null || date.isEmpty())
                ? java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"))
                : date;

        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<tv generator-info-name=\"YspEpg\">\n");

        Map<String, JsonArray> byCode = fetchBatch(channels, d);
        int total = 0;
        for (YspChannels.Channel ch : channels) {
            String code = YspChannels.epgCode(ch);
            if (code == null) continue;
            JsonArray programs = byCode.get(code);
            if (programs == null || programs.isEmpty()) continue;
            String frag = buildXmltv(ch, programs);
            if (!frag.isEmpty()) {
                sb.append(frag);
                total++;
            }
        }
        sb.append("</tv>\n");
        return total == 0 ? "" : sb.toString();
    }

    private static Map<String, JsonArray> fetchBatch(List<YspChannels.Channel> channels, String date) {
        Map<String, JsonArray> result = new LinkedHashMap<>();
        List<String> codes = new ArrayList<>();
        for (YspChannels.Channel ch : channels) {
            String code = YspChannels.epgCode(ch);
            if (code != null && !codes.contains(code)) codes.add(code);
        }
        if (codes.isEmpty()) return result;

        // 接口 URL 长度有限，分批（每批 <= 15 个频道）
        int batch = 15;
        for (int i = 0; i < codes.size(); i += batch) {
            List<String> slice = new ArrayList<>(codes.subList(i, Math.min(i + batch, codes.size())));
            if (!fetchSlice(slice, date, result)) {
                // 整批被拒（接口对任何非法频道名都会返回 params error），
                // 逐个子请求重试，让单个坏频道只影响它自己。
                Log.w(TAG, "batch rejected, retrying individually: " + slice);
                for (String code : slice) fetchSlice(new ArrayList<>(List.of(code)), date, result);
            }
        }
        return result;
    }

    /** 拉取一批；成功返回 true 并把结果并入 out。 */
    private static boolean fetchSlice(List<String> codes, String date, Map<String, JsonArray> out) {
        try {
            String url = API + "?serviceId=" + SERVICE_ID
                    + "&c=" + String.join(",", codes)
                    + "&d=" + date + "&cb=t";
            String body = OkHttp.string(url, Map.of("User-Agent", UA));
            if (body == null || body.isEmpty()) return false;

            int start = body.indexOf('(');
            int end = body.lastIndexOf(')');
            if (start < 0 || end <= start) return false;
            String inner = body.substring(start + 1, end).trim();
            if (inner.contains("\"errcode\"")) return false;

            JsonObject root = JsonParser.parseString(inner).getAsJsonObject();
            boolean any = false;
            for (String code : codes) {
                JsonElement node = root.get(code);
                if (node == null || !node.isJsonObject()) continue;
                JsonElement prog = node.getAsJsonObject().get("program");
                if (prog != null && prog.isJsonArray()) {
                    out.put(code, prog.getAsJsonArray());
                    any = true;
                }
            }
            return any;
        } catch (Throwable t) {
            Log.w(TAG, "fetchSlice failed [" + String.join(",", codes) + "]: " + t);
            return false;
        }
    }

    private static long optLong(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return (e == null || e.isJsonNull()) ? 0L : e.getAsLong();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static String optString(JsonObject o, String key) {
        try {
            JsonElement e = o.get(key);
            return (e == null || e.isJsonNull()) ? "" : e.getAsString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
