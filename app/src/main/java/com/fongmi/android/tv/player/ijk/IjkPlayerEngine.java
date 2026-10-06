package com.fongmi.android.tv.player.ijk;

import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 老版 ijkplayer（k0.8.8）引擎，按 MpvPlayerEngine 同样的方式接入 PlayerManager。
 * 软解/硬解：硬解走 ijk 的 mediacodec 路径，软解走 ffmpeg，切换需重建播放器。
 */
public class IjkPlayerEngine implements PlayerEngine {

    private static final String TAG = "IjkPlayerEngine";

    private IjkSimplePlayer player;
    private int decode;

    public IjkPlayerEngine(int decode, Player.Listener listener) {
        this.decode = decode;
        this.player = buildPlayer(listener);
    }

    public static boolean isAvailable() {
        return IjkSimplePlayer.isAvailable();
    }

    public static String getAvailabilityError() {
        return IjkSimplePlayer.getAvailabilityError();
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        player.release();
    }

    @Override
    public Player rebuild(Player.Listener listener) {
        player.release();
        return player = buildPlayer(listener);
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
        player.setDecode(decode);
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    @Override
    public void start(PlaySpec spec) {
        start(spec, C.TIME_UNSET);
    }

    @Override
    public void start(PlaySpec spec, long positionMs) {
        MediaItem item = ExoUtil.getMediaItem(spec, decode);
        ensureIdleOrEnded(player);
        try {
            player.setMediaItem(item, positionMs);
            player.prepare();
            player.play();
        } catch (Exception e) {
            Log.w(TAG, "start failed, retry after stop+clear.", e);
            try {
                player.stop();
            } catch (Exception ignored) {
            }
            try {
                player.clearMediaItems();
            } catch (Exception ignored) {
            }
            try {
                player.setMediaItem(item, positionMs);
                player.prepare();
                player.play();
            } catch (Exception e2) {
                Log.e(TAG, "start retry failed.", e2);
            }
        }
    }

    private static void ensureIdleOrEnded(Player player) {
        if (player == null) return;
        int state = player.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        state = player.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) return;
        try {
            player.clearMediaItems();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return player.getDuration() < TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    public boolean isVod() {
        return player.getDuration() > TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    public void setTrack(List<Track> tracks) {
        player.setTrack(tracks);
    }

    @Override
    public void resetTrack() {
        player.resetTrack();
    }

    @Override
    public boolean haveTrack(int type) {
        return player.getCurrentTracks().containsType(type);
    }

    @Override
    public Tracks getCurrentTracks() {
        return player.getCurrentTracks();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return e.getMessage() == null ? "IJK 播放失败" : e.getMessage();
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        // 给一次软/硬解切换重试的机会：硬解失败（老盒子 mediacodec 不支持该编码）时降级软解
        return ErrorAction.DECODE;
    }

    private IjkSimplePlayer buildPlayer(Player.Listener listener) {
        IjkSimplePlayer player = new IjkSimplePlayer(App.get(), decode);
        player.addListener(listener);
        player.setPlayWhenReady(true);
        return player;
    }
}
