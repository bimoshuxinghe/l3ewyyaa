package com.fongmi.android.tv.ui.activity;

import android.app.WallpaperManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;

import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.Target;
import com.fongmi.android.tv.databinding.ActivityStillBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;

/**
 * 剧照大图预览页：左右键切换剧照，「设为桌面壁纸」按钮通过 {@link WallpaperManager}
 * 将当前横版剧照设置为电视/盒子桌面壁纸（需 SET_WALLPAPER 权限，normal 级安装即授予）。
 */
public class StillActivity extends BaseActivity {

    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_INDEX = "index";

    private ActivityStillBinding mBinding;
    private final ArrayList<String> mUrls = new ArrayList<>();
    private int mIndex;

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
        mBinding.setWallpaper.requestFocus();
        show();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
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

    private void show() {
        String url = mUrls.get(mIndex);
        Glide.with(this).load(ImgUtil.getUrl(url)).fitCenter().into(mBinding.image);
        mBinding.position.setText((mIndex + 1) + " / " + mUrls.size());
    }

    private void setWallpaper() {
        if (mIndex < 0 || mIndex >= mUrls.size()) return;
        String url = mUrls.get(mIndex);
        mBinding.setWallpaper.setEnabled(false);
        Notify.show("正在设置壁纸…");
        new Thread(() -> {
            try {
                // 按屏幕尺寸解码 + 居中裁剪，既够清晰又避免全尺寸大图 OOM
                Bitmap bitmap = Glide.with(getApplicationContext())
                        .asBitmap()
                        .load(ImgUtil.getUrl(url))
                        .override(ResUtil.getScreenWidth(), ResUtil.getScreenHeight())
                        .centerCrop()
                        .submit()
                        .get();
                WallpaperManager manager = WallpaperManager.getInstance(getApplicationContext());
                // FLAG_SYSTEM 只设系统桌面；旧系统/异常时回退普通 setBitmap
                try {
                    manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM);
                } catch (Throwable ignore) {
                    manager.setBitmap(bitmap);
                }
                runOnUiThread(() -> {
                    Notify.show("桌面壁纸已设置，返回桌面查看");
                    mBinding.setWallpaper.setEnabled(true);
                });
            } catch (Throwable e) {
                runOnUiThread(() -> {
                    Notify.show("设置失败：" + e.getClass().getSimpleName());
                    mBinding.setWallpaper.setEnabled(true);
                });
            }
        }, "set-wallpaper").start();
    }

    public static Intent intentOf(android.content.Context context, ArrayList<String> urls, int index) {
        return new Intent(context, StillActivity.class)
                .putStringArrayListExtra(EXTRA_URLS, urls)
                .putExtra(EXTRA_INDEX, index)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
