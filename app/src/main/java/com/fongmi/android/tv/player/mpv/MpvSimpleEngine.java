package com.fongmi.android.tv.player.mpv;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.MediaItemFactory;
import com.fongmi.android.tv.player.media.PlaySpec;

/**
 * MPV 引擎（手机版参考接法）：使用 MpvSimplePlayer 直驱 libplayer/libmpv，
 * 与参考播放器（mpv v0.41.0-dev + libplacebo）同源，避免 vendored media3
 * 封装层在盒子上额外触发的 DV 直通/多 surface 路径。
 */
public class MpvSimpleEngine implements PlayerEngine {

    private final MpvErrorMessageProvider provider;
    private final MpvSimplePlayer player;
    private final Player.Listener listener;
    private PlaySpec spec;

    public MpvSimpleEngine(int decode, Player.Listener listener) {
        this.player = new MpvSimplePlayer(App.get(), decode);
        this.provider = new MpvErrorMessageProvider();
        this.listener = listener;
        this.player.addListener(listener);
    }

    public static boolean isAvailable() {
        return MpvSimplePlayer.isAvailable();
    }

    @Override
    public Type getType() {
        return Type.MPV;
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public int getAudioChannelCount() {
        return Format.NO_VALUE;
    }

    @Override
    public void release() {
        if (listener != null) player.removeListener(listener);
        player.release();
    }

    @Override
    public void setDecode(int decode) {
        player.setDecode(decode);
    }

    @Override
    public void bindPlayerView(PlayerView playerView) {
        playerView.setPlayer(player);
    }

    @Override
    public void start(PlaySpec spec, long startPositionMs) {
        this.spec = spec;
        startInternal(startPositionMs);
    }

    private void startInternal(long startPositionMs) {
        player.setMediaItem(MediaItemFactory.from(spec), startPositionMs);
        player.prepare();
        player.play();
    }

    @Override
    public void stop() {
        player.stop();
    }

    @Override
    public boolean addSubtitle(Sub sub) {
        return false;
    }

    @Override
    public SecondarySubtitleState getSecondarySubtitleState() {
        return SecondarySubtitleState.EMPTY;
    }

    @Override
    public void setSecondarySubtitleSelection(@Nullable TrackSelectionOverride selection) {
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> ErrorAction.DECODE;
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> retryHls();
            default -> ErrorAction.FATAL;
        };
    }

    private ErrorAction retryHls() {
        if (spec == null || MimeTypes.APPLICATION_M3U8.equals(spec.getFormat())) return ErrorAction.FATAL;
        spec.setFormat(MimeTypes.APPLICATION_M3U8);
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }

    @NonNull
    @Override
    public String toString() {
        return "MpvSimpleEngine";
    }
}
