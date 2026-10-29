package com.fongmi.chaquo;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;
import android.util.Log;

import com.chaquo.python.Python;
import com.chaquo.python.internal.Common;
import com.github.catvod.Init;

import androidx.annotation.NonNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class Platform extends Python.Platform {

    private static final String TAG = "PyPlatform";
    private static final String[] OBSOLETE_FILES = {"app.zip", "requirements.zip", "chaquopy.mp3", "stdlib.mp3", "chaquopy.zip", "lib-dynload", "stdlib.zip", "bootstrap.zip", "stdlib-common.zip", "ticket.txt"};
    private static final String[] OBSOLETE_CACHE = {"AssetFinder"};

    private final SharedPreferences sp;
    private final JSONObject buildJson;
    private final AssetManager am;
    private final Context context;
    private final Set<String> assetNames;
    private String ABI;

    public Platform() {
        this.context = Init.context();
        this.sp = context.getSharedPreferences(Common.ASSET_DIR, Context.MODE_PRIVATE);
        this.am = context.getAssets();
        this.assetNames = new HashSet<>();
        try {
            try (InputStream is = am.open(Common.ASSET_DIR + "/" + Common.ASSET_BUILD_JSON)) {
                buildJson = new JSONObject(streamToString(is));
            }
            JSONObject assetsJson = buildJson.optJSONObject("assets");
            if (assetsJson != null) {
                Iterator<String> keys = assetsJson.keys();
                while (keys.hasNext()) assetNames.add(keys.next());
            }
            ABI = detectAbi();
            loadNativeLibs();
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }
        if (ABI == null) throw new RuntimeException("No supported ABI found in: " + Arrays.asList(Build.SUPPORTED_ABIS));
        publishAbi();
    }

    /**
     * Chaquopy 15 的 python 侧 importer.py 通过 AndroidPlatform.ABI 静态字段
     * 查找与 ABI 相关的资源 zip（stdio 原生模块等）。自定义 Platform 不会
     * 给该字段赋值，这里补上，缺失时不会致命，但会少加载一个原生 zip。
     */
    private void publishAbi() {
        try {
            Class<?> cls = Class.forName("com.chaquo.python.android.AndroidPlatform");
            java.lang.reflect.Field field = cls.getField("ABI");
            field.set(null, ABI);
            Log.i(TAG, "AndroidPlatform.ABI=" + ABI);
        } catch (Throwable e) {
            Log.w(TAG, "AndroidPlatform.ABI not published: " + e);
        }
    }

    public static Platform create() {
        return new Platform();
    }

    /**
     * 资源文件名在不同 Chaquopy 版本里不一样（app.zip / app.mp3 / app.imy / app），
     * 这里按 build.json 中记录的实际资源名解析，避免版本差异导致 import app 失败。
     */
    private String resolve(String name) {
        for (String ext : new String[]{"", ".imy", ".mp3", ".zip"}) {
            String candidate = name + ext;
            if (assetNames.contains(candidate)) return candidate;
        }
        for (String existing : assetNames) {
            if (existing.startsWith(name + ".")) return existing;
        }
        return null;
    }

    private boolean exists(String path) {
        if (assetNames.contains(path)) return true;
        for (String existing : assetNames) {
            if (existing.startsWith(path + "/")) return true;
        }
        return false;
    }

    private String detectAbi() {
        List<String> supportedAbis = new ArrayList<>();
        Collections.addAll(supportedAbis, Build.SUPPORTED_ABIS);
        Collections.addAll(supportedAbis, Build.CPU_ABI, Build.CPU_ABI2);
        for (String abi : supportedAbis) {
            if (abi == null) continue;
            if (resolve(Common.assetZip(Common.ASSET_STDLIB, abi)) != null) return abi;
        }
        // 兜底：从实际资源名里推断唯一可用的 abi
        for (String existing : assetNames) {
            String prefix = Common.ASSET_STDLIB + "-";
            if (!existing.startsWith(prefix)) continue;
            String abi = existing.substring(prefix.length()).split("\\.")[0];
            if (!abi.isEmpty() && !Common.ABI_COMMON.equals(abi)) return abi;
        }
        return null;
    }

    @Override
    public @NonNull String getPath() {
        String assetDir = new File(context.getFilesDir(), Common.ASSET_DIR).getAbsolutePath();
        List<String> pathAssets = new ArrayList<>();
        String stdlibCommon = resolve(Common.assetZip(Common.ASSET_STDLIB, Common.ABI_COMMON));
        if (stdlibCommon != null) pathAssets.add(stdlibCommon);
        String bootstrap = resolve(Common.assetZip(Common.ASSET_BOOTSTRAP));
        if (bootstrap != null) pathAssets.add(bootstrap);
        String bootstrapNative = Common.ASSET_BOOTSTRAP_NATIVE + "/" + ABI;
        if (exists(bootstrapNative)) pathAssets.add(bootstrapNative);
        String pythonPath = pathAssets.stream().map(asset -> assetDir + "/" + asset).collect(Collectors.joining(":"));
        List<String> extractionList = new ArrayList<>(pathAssets);
        if (resolve(Common.ASSET_CACERT) != null) extractionList.add(resolve(Common.ASSET_CACERT));
        try {
            deleteObsolete(context.getFilesDir(), OBSOLETE_FILES);
            deleteObsolete(context.getCacheDir(), OBSOLETE_CACHE);
            extractAssets(extractionList);
        } catch (IOException | JSONException e) {
            throw new RuntimeException(e);
        }
        Log.i(TAG, "pythonPath=" + pythonPath + " abi=" + ABI);
        return pythonPath;
    }

    @Override
    public void onStart(@NonNull Python py) {
        List<String> appPath = new ArrayList<>();
        // 注意：这里必须传"不带后缀"的资源名（app / requirements / stdlib-<abi>）。
        // python 侧 AssetFinder 会自己按版本拼 .imy / .mp3 / .zip，传错名字会导致
        // import app 失败（表现为所有 py 站源都加载不出来）。
        for (String name : new String[]{
                Common.ASSET_APP,
                Common.ASSET_REQUIREMENTS,
                Common.ASSET_STDLIB + "-" + ABI}) {
            if (resolve(name) != null) appPath.add(name);
        }
        Log.i(TAG, "appPath=" + appPath);
        py.getModule("java.android").callAttr("initialize", context, buildJson, appPath.toArray(new String[0]));
    }

    private void deleteObsolete(File baseDir, String[] filenames) {
        for (String filename : filenames) {
            filename = filename.replace("<abi>", String.valueOf(ABI));
            deleteRecursive(new File(baseDir, Common.ASSET_DIR + "/" + filename));
        }
    }

    private void extractAssets(List<String> assets) throws IOException, JSONException {
        JSONObject assetsJson = buildJson.getJSONObject("assets");
        Set<String> unextracted = new HashSet<>(assets);
        Set<String> directories = new HashSet<>();
        SharedPreferences.Editor spe = sp.edit();
        Iterator<String> keys = assetsJson.keys();
        while (keys.hasNext()) {
            String path = keys.next();
            for (String ea : assets) {
                if (path.equals(ea) || path.startsWith(ea + "/")) {
                    extractAsset(assetsJson, spe, path);
                    unextracted.remove(ea);
                    if (path.startsWith(ea + "/")) directories.add(ea);
                    break;
                }
            }
        }
        // 取向宽松：个别可选资源缺失（如 cacert.pem、bootstrap-native）只记日志，
        // 不再整体抛异常，否则 Python 起不来会连带整个首页无数据。
        if (!unextracted.isEmpty()) Log.w(TAG, "skip missing assets: " + unextracted);
        for (String dir : directories) cleanExtractedDir(dir, assetsJson);
        spe.apply();
    }

    private void extractAsset(JSONObject assetsJson, SharedPreferences.Editor spe, String path) throws IOException, JSONException {
        String fullPath = Common.ASSET_DIR + "/" + path;
        File outFile = new File(context.getFilesDir(), fullPath);
        String spKey = "asset." + path;
        String newHash = assetsJson.getString(path);
        if (outFile.exists() && sp.getString(spKey, "").equals(newHash)) return;
        outFile.delete();
        File outDir = outFile.getParentFile();
        if (outDir != null && !outDir.exists() && !outDir.mkdirs()) throw new IOException("Failed to create " + outDir);
        File tmpFile = new File(outDir, outFile.getName() + ".tmp");
        tmpFile.delete();
        try (InputStream in = am.open(fullPath); OutputStream out = new FileOutputStream(tmpFile)) {
            transferStream(in, out);
        }
        if (!tmpFile.renameTo(outFile)) throw new IOException("Failed to rename " + tmpFile);
        spe.putString(spKey, newHash);
    }

    private void cleanExtractedDir(String dir, JSONObject assetsJson) {
        File outDir = new File(context.getFilesDir(), Common.ASSET_DIR + "/" + dir);
        File[] list = outDir.listFiles();
        if (list == null) return;
        Arrays.stream(list).forEach(outFile -> {
            String name = outFile.getName();
            if (outFile.isDirectory()) {
                cleanExtractedDir(dir + "/" + name, assetsJson);
            } else if (!assetsJson.has(dir + "/" + name)) {
                outFile.delete();
            }
        });
    }

    private void deleteRecursive(File file) {
        File[] children = file.listFiles();
        if (children != null) Arrays.stream(children).forEach(this::deleteRecursive);
        file.delete();
    }

    private void transferStream(InputStream in, OutputStream out) throws IOException {
        int len;
        byte[] buffer = new byte[8192];
        while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
    }

    private String streamToString(InputStream in) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        transferStream(in, result);
        return result.toString(StandardCharsets.UTF_8.name());
    }

    private void loadNativeLibs() throws JSONException {
        String pythonVer = buildJson.getString("python_version");
        for (String lib : new String[]{"crypto_chaquopy", "ssl_chaquopy", "sqlite3_chaquopy", "python" + pythonVer, "chaquopy_java"}) {
            try {
                System.loadLibrary(lib);
            } catch (Throwable e) {
                Log.e(TAG, "load library failed: " + lib, e);
            }
        }
    }
}
