package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.MIME_HTML;
import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.Method;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import android.text.TextUtils;

import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * 局域网扫码绑定页（/bind）——由 Python 版 {@code migu_server.py} 的 {@code BindHandler} 移植。
 *
 * <p>手机扫电视上的二维码 → 打开 {@code http://<电视IP>:<端口>/bind?type=migu|tmdb} →
 * 在手机上填 UID/Token（或 TMDB API Key）→ POST {@code /bind/submit} → 写回 Prefers。
 *
 * <p><b>端口</b>：原先 Python 版固定监听 9980；现在挂在本机 Nano 上（端口动态），
 * 二维码地址由 {@code MiguLoginDialog} 用 {@code Server.get().getAddress(false)} 实时拼。
 * 只有 {@code /bind} 对局域网开放，取流路由仍走 loopback。
 */
public class Bind implements Process {

    /** 绑定类型：咪咕账号（UID/Token）。 */
    public static final String TYPE_MIGU = "migu";
    /** 绑定类型：TMDB API Key。 */
    public static final String TYPE_TMDB = "tmdb";

    private static final String PATH_BIND = "/bind";
    private static final String PATH_SUBMIT = "/bind/submit";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return PATH_BIND.equals(url) || PATH_SUBMIT.equals(url);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            if (PATH_SUBMIT.equals(url) && session.getMethod() == Method.POST) return submit(session, files);
            if (PATH_BIND.equals(url)) return page(session);
            return Nano.error("未知请求");
        } catch (Throwable e) {
            return json(Status.INTERNAL_ERROR, false, "服务器错误");
        }
    }

    // ------------------------------------------------------------------ 页面

    /** GET /bind?type=migu|tmdb —— 返回填表页。页面只读，不做 token 拦截（写入由 POST 侧校验）。 */
    private Response page(IHTTPSession session) {
        String type = session.getParms().get("type");
        boolean tmdb = TYPE_TMDB.equals(type);
        Response res = newFixedLengthResponse(Status.OK, MIME_HTML, tmdb ? TMDB_PAGE : MIGU_PAGE);
        res.addHeader("Cache-Control", "no-store");
        return res;
    }

    // ------------------------------------------------------------------ 提交

    /** POST /bind/submit —— 按 type 分派写入。 */
    private Response submit(IHTTPSession session, Map<String, String> files) {
        Map<String, String> form = form(session, files);
        String type = form.getOrDefault("type", TYPE_MIGU);

        if (TYPE_TMDB.equals(type)) {
            String key = trim(form.get("key"));
            if (key.isEmpty()) return json(Status.BAD_REQUEST, false, "API Key 不能为空");
            try {
                Setting.putTmdbApiKey(key);
                return json(Status.OK, true, null);
            } catch (Throwable e) {
                return json(Status.INTERNAL_ERROR, false, "保存失败，请重试");
            }
        }

        String uid = trim(form.get("uid"));
        String token = trim(form.get("token"));
        if (uid.isEmpty() || token.isEmpty()) return json(Status.BAD_REQUEST, false, "UID/Token 不能为空");
        try {
            Prefers.put("migu_uid", uid);
            Prefers.put("migu_token", token);
            return json(Status.OK, true, null);
        } catch (Throwable e) {
            return json(Status.INTERNAL_ERROR, false, "保存失败，请重试");
        }
    }

    /**
     * 取表单字段。
     *
     * <p>NanoHTTPD 对 {@code application/x-www-form-urlencoded} 的 POST 会同时填
     * {@code getParms()} 与 files；这里以 files 为主，再兜底读 body，最后合并 query，
     * 避免个别实现把 body 落错位置。
     */
    private Map<String, String> form(IHTTPSession session, Map<String, String> files) {
        Map<String, String> out = new LinkedHashMap<>();
        out.putAll(session.getParms());
        if (files != null) for (Map.Entry<String, String> e : files.entrySet()) {
            if (e.getValue() != null) out.put(e.getKey(), e.getValue());
        }
        if (!out.containsKey("uid") || !out.containsKey("token")) {
            try {
                int len = 0;
                try {
                    len = Integer.parseInt(session.getHeaders().getOrDefault("content-length", "0"));
                } catch (Throwable ignored) {
                }
                if (len > 0 && len < 64 * 1024) {
                    BufferedReader r = new BufferedReader(new InputStreamReader(session.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    char[] buf = new char[1024];
                    int n;
                    while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
                    parseQuery(sb.toString(), out);
                }
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    private static void parseQuery(String raw, Map<String, String> out) {
        if (raw == null || raw.isEmpty()) return;
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                out.put(k, v);
            } catch (Throwable ignored) {
            }
        }
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static Response json(Status status, boolean ok, String msg) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("ok", ok);
            if (msg != null) obj.put("msg", msg);
            Response res = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString());
            res.addHeader("Cache-Control", "no-store");
            return res;
        } catch (Throwable e) {
            return newFixedLengthResponse(status, MIME_PLAINTEXT, ok ? "{\"ok\":true}" : "{\"ok\":false}");
        }
    }

    // ------------------------------------------------------------------ 内嵌页面（原样移植自 Python 版）

    private static final String MIGU_PAGE = "<!doctype html><html><head><meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>咪咕账号绑定</title>\n"
            + "<style>\n"
            + "body{font-family:system-ui,-apple-system,sans-serif;background:#0f1420;color:#fff;margin:0;padding:24px;max-width:420px}\n"
            + "h1{font-size:19px;margin:4px 0 10px}.tip{color:#9aa4b5;font-size:13px;line-height:1.7;margin:0 0 8px}\n"
            + "input{width:100%;box-sizing:border-box;padding:13px;margin:10px 0;border-radius:10px;border:1px solid #2a3446;background:#1a2230;color:#fff;font-size:16px;outline:none}\n"
            + "input:focus{border-color:#3b82f6}\n"
            + "button{width:100%;padding:14px;border:0;border-radius:10px;background:#3b82f6;color:#fff;font-size:16px;font-weight:600;margin-top:6px}\n"
            + ".msg{color:#22c55e;font-size:15px;text-align:center;margin-top:14px}\n"
            + ".err{color:#ef4444}\n"
            + "</style></head><body>\n"
            + "<h1>咪咕账号绑定</h1>\n"
            + "<p class=\"tip\">登录 miguvideo.com 后，用浏览器开发者工具（F12 → Network）从任意请求的请求头里复制 <b>UserId</b> 和 <b>UserToken</b> 填入。绑定后电视端自动播放蓝光1080p，非会员自动降回高清。</p>\n"
            + "<form id=\"f\" onsubmit=\"return false;\">\n"
            + "<input id=\"uid\" placeholder=\"咪咕 UID\" autocomplete=\"off\">\n"
            + "<input id=\"token\" placeholder=\"咪咕 Token\" autocomplete=\"off\">\n"
            + "<button onclick=\"submit()\">绑定到电视</button>\n"
            + "</form>\n"
            + "<p id=\"msg\" class=\"msg\"></p>\n"
            + "<script>\n"
            + "async function submit(){\n"
            + "  var uid=document.getElementById('uid').value.trim(), token=document.getElementById('token').value.trim();\n"
            + "  var m=document.getElementById('msg');\n"
            + "  if(!uid||!token){m.textContent='请填写完整';m.className='msg err';return}\n"
            + "  var type=new URLSearchParams(location.search).get('type')||'migu';\n"
            + "  var fd=new FormData();fd.append('uid',uid);fd.append('token',token);fd.append('type',type);\n"
            + "  try{\n"
            + "    var r=await fetch('/bind/submit',{method:'POST',body:fd});\n"
            + "    var d=await r.json();\n"
            + "    if(d.ok){m.textContent='绑定成功！现在可以关闭本页，返回电视播放。';document.getElementById('f').style.display='none';}\n"
            + "    else{m.textContent='绑定失败：'+(d.msg||'未知错误');m.className='msg err';}\n"
            + "  }catch(e){m.textContent='网络错误：请确认手机和电视在同一个WiFi';m.className='msg err';}\n"
            + "}\n"
            + "</script></body></html>";

    private static final String TMDB_PAGE = "<!doctype html><html><head><meta charset=\"utf-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
            + "<title>TMDB API Key 绑定</title>\n"
            + "<style>\n"
            + "body{font-family:system-ui,-apple-system,sans-serif;background:#0f1420;color:#fff;margin:0;padding:24px;max-width:420px}\n"
            + "h1{font-size:19px;margin:4px 0 10px}.tip{color:#9aa4b5;font-size:13px;line-height:1.7;margin:0 0 8px}\n"
            + "input{width:100%;box-sizing:border-box;padding:13px;margin:10px 0;border-radius:10px;border:1px solid #2a3446;background:#1a2230;color:#fff;font-size:16px;outline:none}\n"
            + "input:focus{border-color:#3b82f6}\n"
            + "button{width:100%;padding:14px;border:0;border-radius:10px;background:#3b82f6;color:#fff;font-size:16px;font-weight:600;margin-top:6px}\n"
            + ".msg{color:#22c55e;font-size:15px;text-align:center;margin-top:14px}\n"
            + ".err{color:#ef4444}\n"
            + "</style></head><body>\n"
            + "<h1>TMDB API Key 绑定</h1>\n"
            + "<p class=\"tip\">在 themoviedb.org 注册后，到 <b>Settings → API</b> 申请 API Key（v3），把 Key 填入即可。绑定后电视端刮削海报/简介自动生效。</p>\n"
            + "<form id=\"f\" onsubmit=\"return false;\">\n"
            + "<input id=\"key\" placeholder=\"TMDB API Key\" autocomplete=\"off\">\n"
            + "<button onclick=\"submit()\">绑定到电视</button>\n"
            + "</form>\n"
            + "<p id=\"msg\" class=\"msg\"></p>\n"
            + "<script>\n"
            + "async function submit(){\n"
            + "  var key=document.getElementById('key').value.trim(), m=document.getElementById('msg');\n"
            + "  if(!key){m.textContent='请填写 API Key';m.className='msg err';return}\n"
            + "  var type=new URLSearchParams(location.search).get('type')||'migu';\n"
            + "  var fd=new FormData();fd.append('key',key);fd.append('type',type);\n"
            + "  try{\n"
            + "    var r=await fetch('/bind/submit',{method:'POST',body:fd});\n"
            + "    var d=await r.json();\n"
            + "    if(d.ok){m.textContent='绑定成功！现在可以关闭本页，返回电视。';document.getElementById('f').style.display='none';}\n"
            + "    else{m.textContent='绑定失败：'+(d.msg||'未知错误');m.className='msg err';}\n"
            + "  }catch(e){m.textContent='网络错误：请确认手机和电视在同一个WiFi';m.className='msg err';}\n"
            + "}\n"
            + "</script></body></html>";
}
