package com.fongmi.android.tv.ui.activity;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.model.GlideUrl;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.ActivityStillBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;

/**
 * 剧照大图预览页：左右键切换剧照，「设为桌面壁纸」按钮通过 {@link WallpaperManager}
 * 将当前横版剧照设置为电视/盒子桌面壁纸（需 SET_WALLPAPER 权限，normal 级安装即授予）。
 * 「下载到本地」按钮把原图保存到相册（Pictures/FongMi），供不支持设壁纸的桌面手动使用。
 */
public class StillActivity extends BaseActivity {

    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_INDEX = "index";

    /** TMDB 图片路径里的尺寸段：/t/p/w1280/xxx.jpg，换成 original 拿原图。 */
    private static final Pattern TMDB_SIZE = Pattern.compile("(/t/p/)(w\\d+|h\\d+|original)(/)");
    /** 豆瓣图片路径里的尺寸段：/view/photo/l/public/xxx.jpg，换成 raw 拿原图。 */
    private static final Pattern DOUBAN_SIZE = Pattern.compile("(img\\d*\\.doubanio\\.com/view/(?:photo|celebrity|personage)/)([^/]+)(/public/)");
    /** 保存壁纸/下载时用于 OkHttp 打 tag，便于取消。 */
    private static final String TAG = "still-image";
    private static final String DIR_NAME = "FongMi";

    private ActivityStillBinding mBinding;
    private final ArrayList<String> mUrls = new ArrayList<>();
    private int mIndex;

    /** 后台任务句柄，onDestroy 时取消，避免 Activity 销毁后还有回调打进来。 */
    private Future<?> mFuture;
    /** 两个按钮共用的互斥标志，防止「设壁纸」和「下载」并发去解大图导致 OOM。 */
    private boolean mBusy;
    private volatile boolean mDestroyed;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityStillBinding.inflate(getLayoutInflater());
    }

    @Override
    protected boolean customWall() {
        return false;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        ArrayList<String> extra = getIntent() == null ? null : getIntent().getStringArrayListExtra(EXTRA_URLS);
        if (extra != null) mUrls.addAll(extra);
        mIndex = getIntent() != null ? getIntent().getIntExtra(EXTRA_INDEX, 0) : 0;
        if (mUrls.isEmpty()) {
            finish();
            return;
        }
        if (mIndex < 0) mIndex = 0;
        if (mIndex >= mUrls.size()) mIndex = mUrls.size() - 1;
        mBinding.setWallpaper.setOnClickListener(v -> setWallpaper());
        mBinding.download.setOnClickListener(v -> download());
        mBinding.setWallpaper.requestFocus();
        show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 焦点在底部按钮上时，左右键必须交还给系统的焦点导航，
        // 否则焦点永远无法从「设为桌面壁纸」移到「下载到本地」。
        if (event.getAction() == KeyEvent.ACTION_DOWN && !isFocusOnButton()) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_LEFT) {
                if (mIndex > 0) {
                    mIndex--;
                    show();
                }
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (mIndex < mUrls.size() - 1) {
                    mIndex++;
                    show();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isFocusOnButton() {
        View focused = getCurrentFocus();
        return focused == mBinding.setWallpaper || focused == mBinding.download;
    }

    private void show() {
        String url = mUrls.get(mIndex);
        // 预览仍用列表里的 w1280 小图：加载快、内存友好。
        // 只有设壁纸/下载才升级到 original，避免切图时反复解码数 MB 大图导致卡顿。
        Glide.with(this).load(ImgUtil.getUrl(url)).fitCenter().into(mBinding.image);
        mBinding.position.setText((mIndex + 1) + " / " + mUrls.size());
    }

    // ---------------------------------------------------------------- 设为壁纸

    private void setWallpaper() {
        if (mBusy) return;
        if (mIndex < 0 || mIndex >= mUrls.size()) return;
        String url = mUrls.get(mIndex);
        setBusy(true);
        Notify.show("正在设置壁纸…");
        mFuture = Task.submit(() -> {
            try {
                doSetWallpaper(url);
                App.post(() -> {
                    if (isDead()) return;
                    setBusy(false);
                    Notify.show("桌面壁纸已设置，返回桌面查看");
                });
            } catch (Throwable e) {
                App.post(() -> {
                    if (isDead()) return;
                    setBusy(false);
                    Notify.show(toFriendlyMessage(e, "设置壁纸失败"));
                });
            }
        });
    }

    private void doSetWallpaper(String url) throws Throwable {
        WallpaperManager manager = WallpaperManager.getInstance(getApplicationContext());
        int[] size = resolveWallpaperSize(manager);
        int targetW = size[0];
        int targetH = size[1];
        // 自己不裁切，所以解码上限取目标长边即可，Glide 会保持源图比例降采样，避免 4K 图 OOM。
        int decodeLimit = Math.max(targetW, targetH);
        Bitmap composed = null;
        try {
            Bitmap src = loadOriginalBitmap(url, decodeLimit);
            composed = composeWallpaper(src, targetW, targetH);
            // FLAG_SYSTEM 只设系统桌面；旧系统/异常时回退普通 setBitmap。
            try {
                manager.setBitmap(composed, null, true, WallpaperManager.FLAG_SYSTEM);
            } catch (Throwable ignore) {
                manager.setBitmap(composed);
            }
        } finally {
            // 只回收自己合成的位图；Glide 返回的 src 归 LruBitmapPool 管，绝不能动。
            if (composed != null && !composed.isRecycled()) composed.recycle();
        }
    }

    /**
     * 计算壁纸目标尺寸。部分 TV ROM 的期望尺寸比屏幕大（用于壁纸滚动），
     * 交付符合其期望的位图可避免 ROM 二次缩放/裁剪。
     * 注意：Activity 声明了 configChanges，旋转不会重建，屏幕尺寸必须每次现取，不能缓存。
     */
    private int[] resolveWallpaperSize(WallpaperManager manager) {
        int screenW = ResUtil.getScreenWidth();
        int screenH = ResUtil.getScreenHeight();
        int desiredW = 0;
        int desiredH = 0;
        try {
            desiredW = manager.getDesiredMinimumWidth();
            desiredH = manager.getDesiredMinimumHeight();
        } catch (Throwable ignore) {
        }
        // ROM 在横竖屏下可能把这两个值颠倒，Activity 固定横屏，长边应始终对应 Width。
        if (desiredW > 0 && desiredH > 0 && desiredW < desiredH) {
            int tmp = desiredW;
            desiredW = desiredH;
            desiredH = tmp;
        }
        int targetW = (desiredW > 0 && desiredW >= screenW) ? desiredW : screenW;
        int targetH = (desiredH > 0 && desiredH >= screenH) ? desiredH : screenH;
        return new int[]{targetW, targetH};
    }

    /**
     * 等比缩放 + 居中 + 四周补纯黑，合成一张尺寸精确的壁纸位图。
     * <p>
     * 用 min 而非 max 计算缩放比：保证整张图**完整放入**目标框、绝不裁切；
     * 比例不一致时四周留纯黑边。因为交付给 WallpaperManager 的位图尺寸已经精确等于
     * 目标尺寸、内容也已排好，ROM 没有二次处理的空间，从根上避免裁切。
     */
    private static Bitmap composeWallpaper(Bitmap src, int targetW, int targetH) {
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        Rect srcRect = new Rect(0, 0, src.getWidth(), src.getHeight());
        float scale = Math.min((float) targetW / src.getWidth(), (float) targetH / src.getHeight());
        float dstW = src.getWidth() * scale;
        float dstH = src.getHeight() * scale;
        float dx = (targetW - dstW) / 2f;
        float dy = (targetH - dstH) / 2f;
        RectF dstRect = new RectF(dx, dy, dx + dstW, dy + dstH);
        Bitmap output = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(src, srcRect, dstRect, paint);
        return output;
    }

    // ---------------------------------------------------------------- 下载到本地

    private void download() {
        if (mBusy) return;
        if (mIndex < 0 || mIndex >= mUrls.size()) return;
        String url = mUrls.get(mIndex);
        if (checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            PermissionUtil.requestFile(this, granted -> {
                if (isDead()) return;
                if (Boolean.TRUE.equals(granted)) startDownload(url);
                else Notify.show("缺少存储权限，无法保存图片");
            });
            return;
        }
        startDownload(url);
    }

    private void startDownload(String url) {
        if (mBusy) return;
        setBusy(true);
        Notify.show("正在下载原图…");
        mFuture = Task.submit(() -> {
            try {
                File file = saveToPictures(url);
                App.post(() -> {
                    if (isDead()) return;
                    setBusy(false);
                    Notify.show("已保存到相册：Pictures/" + DIR_NAME + "/");
                });
            } catch (Throwable e) {
                App.post(() -> {
                    if (isDead()) return;
                    setBusy(false);
                    Notify.show(toFriendlyMessage(e, "下载失败"));
                });
            }
        });
    }

    /**
     * 下载原图到相册目录。全程走字节流，不经过 Bitmap 编解码，
     * 保证落盘的就是原始 JPEG/PNG 字节、零重压零画质损失。
     */
    private File saveToPictures(String url) throws Exception {
        File dir = getPictureDir();
        if (dir == null) throw new IOException("无法访问存储目录");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建保存目录");
        String original = getOriginalUrl(url);
        File file = uniqueFile(dir, buildFileName(original));
        try {
            fetchToFile(original, file);
        } catch (Throwable e) {
            // 豆瓣 raw 等大尺寸不保证存在，静默回退到列表里的原始 URL 重试一次。
            deleteQuietly(file);
            if (!original.equals(url)) fetchToFile(url, file);
            else throw e;
        }
        MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        return file;
    }

    @Nullable
    private static File getPictureDir() {
        try {
            File pictures = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
            if (pictures != null) return new File(pictures, DIR_NAME);
        } catch (Throwable ignore) {
        }
        // 极少数盒子没有外部存储，退到应用私有目录，至少让用户能拿到文件。
        File external = App.get().getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        return external == null ? null : new File(external, DIR_NAME);
    }

    /**
     * 用 OkHttp 拉取原始字节流写入文件。
     * <p>
     * 不能直接用 {@code Download}：它内部不带 Referer/Cookie，
     * 而豆瓣图床有防盗链（不带 Referer 返回 418）。
     * 这里复用 {@link ImgUtil#getUrl(String)} 已处理好的请求头。
     */
    private void fetchToFile(String url, File file) throws IOException {
        Object model = ImgUtil.getUrl(url);
        if (model == null) throw new IOException("图片地址为空");
        String realUrl;
        Request.Builder builder;
        if (model instanceof GlideUrl) {
            GlideUrl glideUrl = (GlideUrl) model;
            realUrl = glideUrl.toStringUrl();
            builder = new Request.Builder().url(realUrl).tag(TAG);
            for (Map.Entry<String, String> entry : glideUrl.getHeaders().entrySet()) {
                builder.addHeader(entry.getKey(), entry.getValue());
            }
        } else {
            realUrl = String.valueOf(model);
            builder = new Request.Builder().url(realUrl).tag(TAG);
        }
        try (Response response = OkHttp.client().newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            if (response.body() == null) throw new IOException("响应内容为空");
            try (InputStream in = response.body().byteStream(); OutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.interrupted()) throw new IOException("已取消");
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
        }
    }

    private static String buildFileName(String url) {
        String base = url;
        int query = base.indexOf('?');
        if (query > 0) base = base.substring(0, query);
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1);
        base = base.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
        base = base.replaceAll("[^A-Za-z0-9_\\-]", "_");
        if (base.length() > 24) base = base.substring(0, 24);
        if (base.isEmpty()) base = "still";
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return "FongMi_" + base + "_" + stamp + "." + resolveExtension(url);
    }

    private static String resolveExtension(String url) {
        String lower = url.toLowerCase(Locale.US);
        int query = lower.indexOf('?');
        if (query > 0) lower = lower.substring(0, query);
        if (lower.endsWith(".png")) return "png";
        if (lower.endsWith(".webp")) return "webp";
        if (lower.endsWith(".jpeg")) return "jpeg";
        return "jpg";
    }

    private static File uniqueFile(File dir, String name) {
        File file = new File(dir, name);
        if (!file.exists()) return file;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, stem + "_" + i + ext);
            if (!candidate.exists()) return candidate;
        }
        return file;
    }

    private static void deleteQuietly(File file) {
        try {
            if (file != null && file.exists()) file.delete();
        } catch (Throwable ignore) {
        }
    }

    // ---------------------------------------------------------------- URL 尺寸升级

    /**
     * 把图片 URL 升级成原图：TMDB 用 original，豆瓣用 raw。
     * <p>
     * 传进来的字符串可能带 {@code @Referer=}/{@code @Cookie=} 等后缀（见 {@link ImgUtil#getUrl}），
     * 必须只在 {@code @} 之前的主体上做替换，否则会破坏请求头段。
     */
    private static String getOriginalUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        int at = url.indexOf('@');
        if (at < 0) return replaceSize(url);
        return replaceSize(url.substring(0, at)) + url.substring(at);
    }

    private static String replaceSize(String url) {
        String out = TMDB_SIZE.matcher(url).replaceFirst("$1original$3");
        // 已经是 original 时正则替换结果不变，天然幂等。
        if (!out.equals(url)) return out;
        return DOUBAN_SIZE.matcher(url).replaceFirst("$1raw$3");
    }

    // ---------------------------------------------------------------- 通用

    /**
     * 加载原图 Bitmap。用单值 override 限制解码尺寸（Glide 会保持源图比例降采样），
     * <b>不加 centerCrop/fitCenter</b>：缩放与补边全部由 {@link #composeWallpaper} 自己做，
     * 保证既不裁切又不变形。
     */
    private Bitmap loadOriginalBitmap(String url, int decodeLimit) throws Exception {
        return Glide.with(getApplicationContext())
                .asBitmap()
                .load(ImgUtil.getUrl(getOriginalUrl(url)))
                .override(decodeLimit, decodeLimit)
                .submit()
                .get();
    }

    private void setBusy(boolean busy) {
        mBusy = busy;
        mBinding.setWallpaper.setEnabled(!busy);
        mBinding.download.setEnabled(!busy);
        float alpha = busy ? 0.5f : 1f;
        mBinding.setWallpaper.setAlpha(alpha);
        mBinding.download.setAlpha(alpha);
        // setEnabled(false) 会让当前持有焦点的按钮失焦，焦点可能直接丢失导致遥控器失灵，
        // 因此立刻把焦点交给另一个仍然可用的按钮。
        if (busy) {
            if (mBinding.download.hasFocus() || !mBinding.setWallpaper.hasFocus()) mBinding.setWallpaper.requestFocus();
        }
    }

    private boolean isDead() {
        return mDestroyed || isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed());
    }

    private static String toFriendlyMessage(Throwable e, String fallback) {
        if (e instanceof UnknownHostException || e instanceof SocketTimeoutException) return "网络异常，请检查网络后重试";
        if (e instanceof OutOfMemoryError) return "图片过大，内存不足";
        if (e instanceof SecurityException) return "缺少存储权限，请到设置中开启";
        if (e instanceof IOException && e.getMessage() != null && e.getMessage().contains("canceled")) return fallback;
        if (e instanceof IOException) return "网络异常，请检查网络后重试";
        return fallback;
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        if (mFuture != null) {
            mFuture.cancel(true);
            mFuture = null;
        }
        OkHttp.cancel(TAG);
        super.onDestroy();
    }

    public static Intent intentOf(Context context, ArrayList<String> urls, int index) {
        return new Intent(context, StillActivity.class)
                .putStringArrayListExtra(EXTRA_URLS, urls)
                .putExtra(EXTRA_INDEX, index)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
