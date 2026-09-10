package com.fongmi.chaquo;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.util.List;
import java.util.Map;

/**
 * 央视频内置源的 Python 桥（app 模块不直接依赖 chaquopy SDK，统一经此转发）。
 *  - liveList(): 央视频频道列表（M3U 文本）
 *  - stream(params): 取流/回看，返回 [status, mime, body]
 */
public class YspBridge {

    public static String liveList() {
        try {
            return Python.getInstance().getModule("live_ysp").callAttr("liveContent", "").toString();
        } catch (Throwable e) {
            return "";
        }
    }

    public static String[] stream(Map<String, String> params) {
        String[] empty = {"200", "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-ENDLIST\n# 央视频取流失败\n"};
        try {
            PyObject app = Python.getInstance().getModule("app");
            PyObject dict = app.callAttr("str2json", toJson(params));
            PyObject result = Python.getInstance().getModule("live_ysp").callAttr("localProxy", dict);
            List<PyObject> list = result.asList();
            if (list.size() < 3) return empty;
            int status = list.get(0).toInt();
            String mime = list.get(1).toString();
            String body = list.get(2).toString();
            return new String[]{String.valueOf(status), mime == null ? "" : mime, body};
        } catch (Throwable e) {
            return empty;
        }
    }

    private static String toJson(Map<String, String> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(e.getKey().replace("\"", "\\\"")).append("\":\"")
                    .append(e.getValue().replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append('}').toString();
    }
}
