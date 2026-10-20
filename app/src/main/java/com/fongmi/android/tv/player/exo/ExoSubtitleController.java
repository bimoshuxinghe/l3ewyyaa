package com.fongmi.android.tv.player.exo;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.player.engine.PlayerEngine.SecondarySubtitleState;
import com.fongmi.android.tv.setting.SubtitleSetting;

/**
 * 字幕控制器（标准渲染路径）。
 * <p>上游 FongMi 版本基于私有 fork 的 libass 字幕渲染（LibassPlaybackSession /
 * LibassPlayerViewController）以实现 ASS 特效字幕与副字幕轨，该类未公开，因此这里
 * 退化为 media3 标准 {@link SubtitleView} 渲染：保留字号/位置/样式/强制覆盖等设置项。
 */
final class ExoSubtitleController {

    private final Player player;

    @Nullable
    private PlayerView playerView;

    ExoSubtitleController(ExoPlayerSession session) {
        this.player = session.player();
        applySubtitleStyle();
    }

    void release() {
        playerView = null;
    }

    void bindPlayerView(PlayerView playerView) {
        this.playerView = playerView;
        playerView.setPlayer(player);
        applySubtitleStyle();
    }

    void applySubtitleStyle() {
        if (playerView == null) return;
        SubtitleView subtitleView = playerView.getSubtitleView();
        if (subtitleView == null) return;
        SubtitleSetting.applyStyle(subtitleView);
        float position = SubtitleSetting.getPosition() / 100.0f;
        subtitleView.setBottomPosition(0.0f);
        subtitleView.setBottomPaddingFraction(1.0f - position);
    }

    SecondarySubtitleState getSecondarySubtitleState() {
        return SecondarySubtitleState.EMPTY;
    }

    void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
    }
}
