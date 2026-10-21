package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.fongmi.android.tv.R;

/**
 * 启动开屏页：全屏居中裁剪显示横版原图（drawable-nodpi/ic_splash_bg，2848x1600 原图不压缩），
 * 停留 {@link #HOLD} 毫秒后在 {@link #FADE} 毫秒内渐隐并移除，露出首页内容。
 * 系统开屏（Android 12+）阶段为同色深藏青背景 + 无中央图标，进入首帧后由本 overlay 无缝接管。
 */
public class SplashOverlay {

    private static final long HOLD = 1800;
    private static final long FADE = 500;

    private SplashOverlay() {
    }

    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        ImageView view = new ImageView(activity);
        view.setImageResource(R.drawable.ic_splash_bg);
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        content.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        view.bringToFront();
        view.postDelayed(() -> view.animate().alpha(0f).setDuration(FADE).withEndAction(() -> content.removeView(view)).start(), HOLD);
    }
}
