package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/** MPV 播放日志导出：GET /mpvlog 返回 mpv.log 内容，便于远程诊断播放问题。 */
public class MpvLog implements Process {

    private static final String PATH = "/mpvlog";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith(PATH);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        File log = new File(Path.mpvCache(), "mpv.log");
        String content = log.exists() ? Path.read(log) : "";
        if (content.isEmpty()) content = "MPV log is empty. Play a video with the MPV engine first, then refresh this page.";
        return newFixedLengthResponse(Status.OK, "text/plain; charset=utf-8", content);
    }
}
