package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import android.content.pm.PackageInfo;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/** MPV 诊断导出：GET /mpvlog（及别名）返回版本、配置开关、缓存目录清单与日志内容。 */
public class MpvLog implements Process {

    private static final String[] PATHS = {"/mpvlog", "/mpv_log", "/mpvdiag", "/mpv.log"};

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        for (String path : PATHS) if (url.startsWith(path)) return true;
        return false;
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== APP ===\n");
        try {
            PackageInfo info = App.get().getPackageManager().getPackageInfo(App.get().getPackageName(), 0);
            sb.append("versionName=").append(info.versionName).append(" versionCode=").append(info.versionCode).append("\n");
        } catch (Throwable e) {
            sb.append("version unknown: ").append(e).append("\n");
        }
        sb.append("\n=== MPV CONFIG ===\n");
        try {
            sb.append("mpvEnabled=").append(PlayerSetting.isMpv()).append("\n");
            sb.append("gpuNext=").append(PlayerSetting.isMpvGpuNext()).append("\n");
            sb.append("vulkanPref=").append(PlayerSetting.isMpvVulkan()).append("\n");
            sb.append("vulkanSupported=").append(com.fongmi.android.tv.player.mpv.MpvUtil.isVulkanSupported()).append("\n");
            sb.append("dvPolicy(2=ASSUME_UNSUPPORTED)=").append(DecodeSetting.getDolbyVisionOutputPolicy()).append("\n");
        } catch (Throwable e) {
            sb.append("config error: ").append(e).append("\n");
        }
        sb.append("\n=== MPV CACHE DIR ===\n");
        try {
            File dir = Path.mpvCache();
            sb.append("dir=").append(dir.getAbsolutePath()).append(" exists=").append(dir.exists()).append("\n");
            File[] items = dir.listFiles();
            if (items == null) sb.append("(unreadable)\n");
            else for (File f : items) sb.append(f.getName()).append(" ").append(f.length()).append(" bytes\n");
        } catch (Throwable e) {
            sb.append("dir error: ").append(e).append("\n");
        }
        sb.append("\n=== mpv.log ===\n");
        File log = new File(Path.mpvCache(), "mpv.log");
        sb.append(log.exists() ? Path.read(log) : "(mpv.log not found - MPV 未走到初始化写日志这一步)\n");
        sb.append("\n=== java_crash.log ===\n");
        File crash = new File(Path.mpvCache(), "java_crash.log");
        sb.append(crash.exists() ? Path.read(crash) : "(none)\n");
        return newFixedLengthResponse(Status.OK, "text/plain; charset=utf-8", sb.toString());
    }
}
