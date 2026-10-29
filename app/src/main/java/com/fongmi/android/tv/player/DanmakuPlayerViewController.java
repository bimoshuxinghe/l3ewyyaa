package com.fongmi.android.tv.player;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.media3.ui.danmaku.DanmakuController;
import androidx.media3.ui.danmaku.DanmakuSurfaceView;
import androidx.media3.ui.danmaku.DanmakuView;

import com.fongmi.android.tv.setting.DanmakuSetting;

import okhttp3.OkHttpClient;

/**
 * 弹幕视图控制器（抄自 FongMi/tv 的 DanmakuPlayerViewController 思路）。
 *
 * <p>自己持有 {@link DanmakuView} 与 {@link DanmakuController}，把弹幕层挂到 {@link PlayerView}
 * 的 overlay 上，并负责把 player / 数据源 / 配置喂给 controller。生命周期与 Activity 绑定，
 * 因此不会像 fork 之前那样被 PlayerView 里反复 release 掉的 controller 拖累，导致弹幕推不上去。
 */
public class DanmakuPlayerViewController {

    @Nullable private PlayerView playerView;
    /** 真正挂到播放器 overlay 上的视图：可能是 {@link DanmakuView}，也可能是 {@link DanmakuSurfaceView}。 */
    @Nullable private View danmakuLayer;
    @Nullable private DanmakuView danmakuView;
    @Nullable private DanmakuSurfaceView danmakuSurfaceView;
    @Nullable private DanmakuController controller;

    public void bind(PlayerView playerView) {
        if (this.playerView == playerView && controller != null) return;
        this.playerView = playerView;
        if (danmakuView == null) {
            createLayer(playerView.getContext());
        }
        if (controller == null) {
            controller = new DanmakuController();
            controller.setView(danmakuView);
        }
        Player player = playerView.getPlayer();
        if (player != null) {
            controller.setPlayer(player);
        }
    }

    private void createLayer(Context context) {
        // 低配机上弹幕改走独立 Surface + 独立线程：主 UI 线程还要负责遥控器按键分发，
        // 4K 播放时它本就被解码抢占，弹幕不能再每帧贴上去抢这条线程。
        if (DanmakuSetting.resolveRenderMode() == DanmakuSetting.RENDER_MODE_SURFACE) {
            danmakuSurfaceView = new DanmakuSurfaceView(context);
            danmakuView = danmakuSurfaceView.getDanmakuView();
            danmakuLayer = danmakuSurfaceView;
        } else {
            danmakuSurfaceView = null;
            danmakuView = new DanmakuView(context);
            danmakuLayer = danmakuView;
        }
        addLayer(danmakuLayer);
    }

    private void addLayer(View view) {
        FrameLayout.LayoutParams params =
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        if (playerView != null) {
            playerView.addDanmakuLayer(view, params);
        }
    }

    public void setPlayer(@Nullable Player player) {
        if (controller != null) {
            controller.setPlayer(player);
        }
    }

    public void setOkHttpClient(@Nullable OkHttpClient client) {
        if (controller != null) {
            controller.setOkHttpClient(client);
        }
    }

    public void setConfig(DanmakuConfig config) {
        if (controller != null) {
            controller.setConfig(config);
        }
    }

    public void setEnabled(boolean enabled) {
        if (controller != null) {
            controller.setEnabled(enabled);
        }
    }

    public void setDataSource(@Nullable Uri uri) {
        if (controller != null) {
            controller.setDataSource(uri);
        }
    }

    public void setListener(@Nullable DanmakuController.Listener listener) {
        if (controller != null) {
            controller.setListener(listener);
        }
    }

    public void sendNow(String text) {
        if (controller != null) {
            controller.sendNow(text);
        }
    }

    public void close() {
        if (controller != null) {
            controller.setPlayer(null);
            controller.release();
            controller = null;
        }
        if (danmakuLayer != null && playerView != null) {
            playerView.removeDanmakuLayer(danmakuLayer);
        }
        if (danmakuSurfaceView != null) {
            // Surface 模式下的 DanmakuView 不挂载到窗口，必须显式释放后台测量线程。
            danmakuSurfaceView.release();
            danmakuSurfaceView = null;
        }
        danmakuView = null;
        danmakuLayer = null;
        playerView = null;
    }
}
