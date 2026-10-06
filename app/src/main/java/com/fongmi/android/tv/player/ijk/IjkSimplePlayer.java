package com.fongmi.android.tv.player.ijk;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

import com.fongmi.android.tv.player.exo.ExoUtil;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.IjkMediaPlayer;
import tv.danmaku.ijk.media.player.misc.IMediaFormat;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;

/**
 * 老版 ijkplayer（bilibili k0.8.8）的 Media3 SimpleBasePlayer 适配器。
 * 与 MpvSimplePlayer 同构：把 ijk 的回调/命令翻译成 Media3 Player 语义，
 * 供 IjkPlayerEngine 接入 PlayerManager 的引擎体系。
 */
@UnstableApi
public final class IjkSimplePlayer extends SimpleBasePlayer implements IMediaPlayer.OnPreparedListener, IMediaPlayer.OnCompletionListener, IMediaPlayer.OnErrorListener, IMediaPlayer.OnInfoListener, IMediaPlayer.OnSeekCompleteListener, IMediaPlayer.OnVideoSizeChangedListener {

    private static final String TAG = "IjkSimplePlayer";
    private static Throwable availabilityError;

    private static final Player.Commands COMMANDS = new Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_RELEASE)
            .add(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_GET_TRACKS)
            .add(Player.COMMAND_GET_VOLUME)
            .add(Player.COMMAND_SET_VOLUME)
            .add(Player.COMMAND_SET_SPEED_AND_PITCH)
            .add(Player.COMMAND_SET_VIDEO_SURFACE)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .build();

    private static final long POSITION_TICK_MS = 500;

    private final Context context;
    private final Handler handler;
    private final Runnable positionTicker = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            tickPosition();
            handler.postDelayed(this, POSITION_TICK_MS);
        }
    };

    private IjkMediaPlayer ijk;
    private MediaItem mediaItem;
    private PlaybackParameters playbackParameters;
    private PlaybackException playerError;
    private Tracks currentTracks;
    private VideoSize videoSize;
    private Surface textureSurface;
    private Surface currentSurface;
    private Object currentVideoOutput;
    private SurfaceHolder currentSurfaceHolder;
    private TextureView currentTextureView;
    private SurfaceHolder.Callback surfaceCallback;
    private TextureView.SurfaceTextureListener textureListener;
    private boolean playWhenReady;
    private boolean prepared;
    private boolean released;
    private boolean loading;
    private boolean renderedFirstFrame;
    private boolean reportRenderedFirstFrame;
    private boolean manualStop;
    private float volume;
    private int playbackState;
    private int decode;
    private long pendingStartPositionMs;
    private long pendingSeekMs;
    private long durationMs;
    private long positionMs;

    public IjkSimplePlayer(Context context, int decode) {
        super(Looper.getMainLooper());
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.playbackParameters = PlaybackParameters.DEFAULT;
        this.currentTracks = Tracks.EMPTY;
        this.videoSize = VideoSize.UNKNOWN;
        this.playWhenReady = true;
        this.volume = 1.0f;
        this.playbackState = Player.STATE_IDLE;
        this.durationMs = C.TIME_UNSET;
        this.pendingStartPositionMs = C.TIME_UNSET;
        this.pendingSeekMs = C.TIME_UNSET;
        this.decode = decode;
        createIjk();
        handler.postDelayed(positionTicker, POSITION_TICK_MS);
    }

    public static boolean isAvailable() {
        try {
            Class.forName("tv.danmaku.ijk.media.player.IjkMediaPlayer");
            availabilityError = null;
            return true;
        } catch (Throwable e) {
            availabilityError = e;
            Log.e(TAG, "IJK player unavailable", e);
            return false;
        }
    }

    public static String getAvailabilityError() {
        Throwable error = availabilityError;
        if (error == null) return "";
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    private void createIjk() {
        try {
            IjkMediaPlayer mp = new IjkMediaPlayer();
            mp.setOnPreparedListener(this);
            mp.setOnCompletionListener(this);
            mp.setOnErrorListener(this);
            mp.setOnInfoListener(this);
            mp.setOnSeekCompleteListener(this);
            mp.setOnVideoSizeChangedListener(this);
            mp.setLogEnabled(false);
            ijk = mp;
        } catch (Throwable e) {
            ijk = null;
            playerError = new PlaybackException(e.getMessage(), e, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK);
            playbackState = Player.STATE_IDLE;
        }
    }

    public void setDecode(int decode) {
        this.decode = decode;
    }

    public void setTrack(List<com.fongmi.android.tv.bean.Track> tracks) {
        if (ijk == null || !prepared) return;
        // 先应用选中，再取消未选中，避免"先取消当前音轨再切"造成瞬间无声
        for (com.fongmi.android.tv.bean.Track track : tracks) if (track.isSelected()) applyTrackSelection(track, true);
        for (com.fongmi.android.tv.bean.Track track : tracks) if (!track.isSelected()) applyTrackSelection(track, false);
        buildTracks();
        invalidateState();
    }

    private void applyTrackSelection(com.fongmi.android.tv.bean.Track track, boolean select) {
        Integer index = parseTrackIndex(track.getFormat());
        if (index == null || index < 0) return;
        try {
            if (select) ijk.selectTrack(index);
            else ijk.deselectTrack(index);
        } catch (Throwable ignored) {
        }
    }

    public void resetTrack() {
        if (ijk == null || !prepared) return;
        IjkTrackInfo[] infos = ijk.getTrackInfo();
        int firstAudio = -1;
        if (infos != null) {
            for (int index = 0; index < infos.length; index++) {
                int type = infos[index].getTrackType();
                if (type == ITrackType.AUDIO) {
                    if (firstAudio < 0) firstAudio = index;
                } else if (type == ITrackType.TIMEDTEXT || type == ITrackType.SUBTITLE) {
                    try {
                        ijk.deselectTrack(index);
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        if (firstAudio >= 0) {
            try {
                ijk.selectTrack(firstAudio);
            } catch (Throwable ignored) {
            }
        }
        buildTracks();
        invalidateState();
    }

    private interface ITrackType {
        int UNKNOWN = 0;
        int VIDEO = 1;
        int AUDIO = 2;
        int TIMEDTEXT = 3;
        int SUBTITLE = 4;
    }

    @Override
    protected State getState() {
        int safePlaybackState = playerError == null ? playbackState : Player.STATE_IDLE;
        State.Builder builder = new State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(safePlaybackState)
                .setIsLoading(playerError == null && loading && safePlaybackState != Player.STATE_IDLE && safePlaybackState != Player.STATE_ENDED)
                .setPlaybackParameters(playbackParameters)
                .setVolume(volume)
                .setVideoSize(videoSize)
                .setSurfaceSize(getCurrentSurfaceSize())
                .setNewlyRenderedFirstFrame(consumeRenderedFirstFrame());
        if (playerError != null) builder.setPlayerError(playerError);
        if (mediaItem != null) {
            builder.setPlaylist(ImmutableList.of(buildMediaItemData()));
            builder.setCurrentMediaItemIndex(0);
            builder.setContentPositionMs(sanitizePosition(positionMs));
            builder.setContentBufferedPositionMs(PositionSupplier.getConstant(getBufferedPositionMs()));
            builder.setTotalBufferedDurationMs(PositionSupplier.getConstant(getTotalBufferedDurationMs()));
        }
        return builder.build();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        applyPlayPause();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        if (mediaItem == null) return Futures.immediateVoidFuture();
        loadIjk(pendingStartPositionMs);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        stopIjk(false);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        releaseInternal();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters playbackParameters) {
        this.playbackParameters = playbackParameters;
        if (ijk != null && prepared) {
            try {
                ijk.setSpeed(playbackParameters.speed);
            } catch (Throwable ignored) {
            }
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume, @C.VolumeOperationType int volumeOperationType) {
        this.volume = Math.min(Math.max(volume, 0.0f), 1.0f);
        if (ijk != null && prepared) {
            try {
                ijk.setVolume(this.volume, this.volume);
            } catch (Throwable ignored) {
            }
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        attachVideoOutput(videoOutput);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        if (videoOutput == null || videoOutput == currentVideoOutput) detachVideoOutput();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(Math.max(0, Math.min(startIndex == C.INDEX_UNSET ? 0 : startIndex, mediaItems.size() - 1)));
        pendingStartPositionMs = startPositionMs;
        resetMediaState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
        if (mediaItem == null && !mediaItems.isEmpty()) mediaItem = mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
        if (!mediaItems.isEmpty()) mediaItem = mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        mediaItem = null;
        stopIjk(false);
        resetMediaState();
        playbackState = Player.STATE_IDLE;
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, @Player.Command int seekCommand) {
        long target = positionMs == C.TIME_UNSET ? 0 : Math.max(0, positionMs);
        this.positionMs = target;
        if (prepared) {
            try {
                ijk.seekTo(target);
            } catch (Throwable ignored) {
            }
        } else {
            // 未 prepared 时的 seek（含起播定位）：记下来，onPrepared 后补
            pendingSeekMs = target;
        }
        return Futures.immediateVoidFuture();
    }

    // ---------------- ijk 回调（ijk 线程 → 主线程） ----------------

    @Override
    public void onPrepared(IMediaPlayer mp) {
        handler.post(() -> {
            if (released || ijk == null) return;
            prepared = true;
            applyPlaybackParametersToIjk();
            applyVolumeToIjk();
            if (pendingSeekMs != C.TIME_UNSET && pendingSeekMs > 0) {
                try {
                    ijk.seekTo(pendingSeekMs);
                } catch (Throwable ignored) {
                }
            }
            pendingSeekMs = C.TIME_UNSET;
            playbackState = Player.STATE_READY;
            loading = false;
            buildTracks();
            applyPlayPause();
            invalidateState();
        });
    }

    @Override
    public void onCompletion(IMediaPlayer mp) {
        handler.post(() -> {
            if (released || manualStop) return;
            prepared = false;
            playbackState = Player.STATE_ENDED;
            loading = false;
            invalidateState();
        });
    }

    @Override
    public boolean onError(IMediaPlayer mp, int what, int extra) {
        handler.post(() -> {
            if (released) return;
            if (manualStop || mediaItem == null) {
                playbackState = Player.STATE_IDLE;
                loading = false;
                invalidateState();
                return;
            }
            setError("IJK 播放失败 (" + describeError(what, extra) + ")");
        });
        return true;
    }

    @Override
    public boolean onInfo(IMediaPlayer mp, int what, int extra) {
        handler.post(() -> {
            if (released) return;
            if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_START) {
                playbackState = Player.STATE_BUFFERING;
                loading = true;
            } else if (what == IMediaPlayer.MEDIA_INFO_BUFFERING_END) {
                if (playbackState == Player.STATE_BUFFERING && renderedFirstFrame) {
                    playbackState = Player.STATE_READY;
                    loading = false;
                }
            } else if (what == IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                markRenderedFirstFrame();
            }
            invalidateState();
        });
        return true;
    }

    @Override
    public void onSeekComplete(IMediaPlayer mp) {
        handler.post(() -> {
            if (released) return;
            if (playbackState == Player.STATE_BUFFERING && renderedFirstFrame && !loading) {
                playbackState = Player.STATE_READY;
                invalidateState();
            }
        });
    }

    @Override
    public void onVideoSizeChanged(IMediaPlayer mp, int width, int height, int sarNum, int sarDen) {
        handler.post(() -> {
            if (released) return;
            if (width > 0 && height > 0) {
                float ratio = sarNum > 0 && sarDen > 0 ? (float) sarNum / (float) sarDen : 1.0f;
                videoSize = new VideoSize(width, height, Math.max(0.01f, ratio));
            } else {
                videoSize = VideoSize.UNKNOWN;
            }
            invalidateState();
        });
    }

    // ---------------- 内部装载/停止 ----------------

    private void loadIjk(long startPositionMs) {
        if (ijk == null) {
            createIjk();
            if (ijk == null) return;
        }
        if (mediaItem == null || mediaItem.localConfiguration == null) return;
        String url = mediaItem.localConfiguration.uri.toString();
        Map<String, String> headers = ExoUtil.extractHeaders(mediaItem);
        resetMediaState();
        playbackState = Player.STATE_BUFFERING;
        loading = true;
        prepared = false;
        positionMs = startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs);
        pendingStartPositionMs = C.TIME_UNSET;
        pendingSeekMs = positionMs > 0 ? positionMs : C.TIME_UNSET;
        invalidateState();
        try {
            ijk.reset();
        } catch (Throwable ignored) {
        }
        applyOptions();
        try {
            if (headers.isEmpty()) ijk.setDataSource(context, mediaItem.localConfiguration.uri);
            else ijk.setDataSource(context, mediaItem.localConfiguration.uri, headers);
        } catch (Throwable e) {
            setError(TextUtils.isEmpty(e.getMessage()) ? "IJK 打开失败" : "IJK 打开失败: " + e.getMessage(), e);
            return;
        }
        attachSurfaceToIjk();
        if (positionMs > 0) ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "seek-at-start", positionMs);
        try {
            ijk.prepareAsync();
        } catch (Throwable e) {
            setError(TextUtils.isEmpty(e.getMessage()) ? "IJK 准备失败" : "IJK 准备失败: " + e.getMessage(), e);
        }
    }

    private void applyOptions() {
        IjkMediaPlayer mp = ijk;
        boolean hard = decode == com.fongmi.android.tv.player.engine.PlayerEngine.HARD;
        // player 类
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 0);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", hard ? 1 : 0);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-hevc", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "handle-resolution", 0);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "overlay-format", IjkMediaPlayer.SDL_FCC_RV32);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "reconnect", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "max-buffer-size", 10 * 1024 * 1024);
        // format 类：缩短起播探测时间（低端盒子首帧更快），保留足够探测精度
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 2097152);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 2000000);
        mp.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1);
    }

    private void stopIjk(boolean releasing) {
        manualStop = true;
        loading = false;
        prepared = false;
        playbackState = Player.STATE_IDLE;
        positionMs = 0;
        pendingSeekMs = C.TIME_UNSET;
        pendingStartPositionMs = C.TIME_UNSET;
        renderedFirstFrame = false;
        reportRenderedFirstFrame = false;
        playerError = null;
        if (ijk != null) {
            try {
                ijk.stop();
            } catch (Throwable ignored) {
            }
            try {
                ijk.reset();
            } catch (Throwable ignored) {
            }
        }
        if (!releasing) invalidateState();
    }

    private void releaseInternal() {
        if (released) return;
        released = true;
        if (ijk != null) {
            try {
                ijk.release();
            } catch (Throwable ignored) {
            }
            ijk = null;
        }
    }

    private void resetMediaState() {
        playerError = null;
        loading = false;
        prepared = false;
        durationMs = C.TIME_UNSET;
        positionMs = 0;
        currentTracks = Tracks.EMPTY;
        videoSize = VideoSize.UNKNOWN;
        manualStop = false;
        renderedFirstFrame = false;
        reportRenderedFirstFrame = false;
        playbackState = mediaItem == null ? Player.STATE_IDLE : Player.STATE_BUFFERING;
    }

    // ---------------- Surface 附加 ----------------

    private void attachVideoOutput(Object videoOutput) {
        detachVideoOutput();
        currentVideoOutput = videoOutput;
        if (videoOutput instanceof Surface surface) {
            attachSurface(surface, 0, 0);
        } else if (videoOutput instanceof SurfaceHolder holder) {
            attachSurfaceHolder(holder);
        } else if (videoOutput instanceof SurfaceView view) {
            attachSurfaceHolder(view.getHolder());
        } else if (videoOutput instanceof TextureView view) {
            attachTextureView(view);
        }
    }

    private void attachSurfaceHolder(SurfaceHolder holder) {
        currentSurfaceHolder = holder;
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                attachSurface(holder.getSurface(), holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                attachSurface(holder.getSurface(), width, height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                detachSurface();
            }
        };
        holder.addCallback(surfaceCallback);
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            attachSurface(holder.getSurface(), holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
        }
    }

    private void attachTextureView(TextureView view) {
        currentTextureView = view;
        textureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                releaseTextureSurface();
                textureSurface = new Surface(surfaceTexture);
                attachSurface(textureSurface, width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                detachSurface();
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            }
        };
        view.setSurfaceTextureListener(textureListener);
        if (view.isAvailable()) {
            releaseTextureSurface();
            textureSurface = new Surface(view.getSurfaceTexture());
            attachSurface(textureSurface, view.getWidth(), view.getHeight());
        }
    }

    private void attachSurface(@Nullable Surface surface, int width, int height) {
        if (surface == null || !surface.isValid()) return;
        currentSurface = surface;
        attachSurfaceToIjk();
    }

    private void attachSurfaceToIjk() {
        if (ijk == null || currentSurface == null || !currentSurface.isValid()) return;
        try {
            ijk.setSurface(currentSurface);
        } catch (Throwable ignored) {
        }
    }

    private void detachSurface() {
        if (currentSurface == null) return;
        if (ijk != null) {
            try {
                ijk.setSurface(null);
            } catch (Throwable ignored) {
            }
        }
        currentSurface = null;
        releaseTextureSurface();
    }

    private void detachVideoOutput() {
        if (currentSurfaceHolder != null && surfaceCallback != null) currentSurfaceHolder.removeCallback(surfaceCallback);
        if (currentTextureView != null && currentTextureView.getSurfaceTextureListener() == textureListener) currentTextureView.setSurfaceTextureListener(null);
        currentSurfaceHolder = null;
        currentTextureView = null;
        surfaceCallback = null;
        textureListener = null;
        currentVideoOutput = null;
        detachSurface();
    }

    private void releaseTextureSurface() {
        if (textureSurface == null) return;
        textureSurface.release();
        textureSurface = null;
    }

    // ---------------- 位置/时长心跳 ----------------

    private void tickPosition() {
        if (ijk == null || !prepared || playbackState == Player.STATE_IDLE) return;
        try {
            long position = ijk.getCurrentPosition();
            long duration = ijk.getDuration();
            if (position >= 0) positionMs = position;
            durationMs = duration > 0 ? duration : C.TIME_UNSET;
            if (playbackState == Player.STATE_BUFFERING && renderedFirstFrame && !loading) {
                playbackState = Player.STATE_READY;
            }
            invalidateState();
        } catch (Throwable ignored) {
        }
    }

    private void applyPlayPause() {
        if (ijk == null || !prepared) return;
        try {
            if (playWhenReady) ijk.start();
            else ijk.pause();
        } catch (Throwable ignored) {
        }
    }

    private void applyPlaybackParametersToIjk() {
        if (ijk == null) return;
        try {
            ijk.setSpeed(playbackParameters.speed);
        } catch (Throwable ignored) {
        }
    }

    private void applyVolumeToIjk() {
        if (ijk == null) return;
        try {
            ijk.setVolume(volume, volume);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 轨道 ----------------

    private void buildTracks() {
        if (ijk == null || !prepared) {
            currentTracks = Tracks.EMPTY;
            return;
        }
        IjkTrackInfo[] infos;
        try {
            infos = ijk.getTrackInfo();
        } catch (Throwable e) {
            infos = null;
        }
        if (infos == null || infos.length == 0) {
            currentTracks = Tracks.EMPTY;
            return;
        }
        List<Tracks.Group> groups = new ArrayList<>();
        for (int index = 0; index < infos.length; index++) {
            int type = infos[index].getTrackType();
            int trackType = toMedia3Type(type);
            if (trackType == C.TRACK_TYPE_UNKNOWN) continue;
            groups.add(new Tracks.Group(new TrackGroup("ijk-" + type + "-" + index, buildFormat(index, type)), false, new int[]{C.FORMAT_HANDLED}, new boolean[]{isTrackSelected(infos, index, type)}));
        }
        currentTracks = groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
    }

    private boolean isTrackSelected(IjkTrackInfo[] infos, int index, int type) {
        // ijk 0.8.8 没有直接的"当前选中"查询，音轨取默认首个、字幕默认未选中；
        // 用户通过轨道菜单选择后会立即生效，由 UI 层的 Track 持久化记录选择状态
        return type == ITrackType.AUDIO && isFirstOfType(infos, index, ITrackType.AUDIO);
    }

    private boolean isFirstOfType(IjkTrackInfo[] infos, int index, int type) {
        for (int i = 0; i < infos.length; i++) {
            if (infos[i].getTrackType() == type) return i == index;
        }
        return false;
    }

    private int toMedia3Type(int ijkType) {
        if (ijkType == ITrackType.VIDEO) return C.TRACK_TYPE_VIDEO;
        if (ijkType == ITrackType.AUDIO) return C.TRACK_TYPE_AUDIO;
        if (ijkType == ITrackType.TIMEDTEXT || ijkType == ITrackType.SUBTITLE) return C.TRACK_TYPE_TEXT;
        return C.TRACK_TYPE_UNKNOWN;
    }

    private Format buildFormat(int index, int ijkType) {
        Format.Builder builder = new Format.Builder()
                .setId("ijk:" + ijkType + ":" + index)
                .setLabel(getTrackLabel(index, ijkType));
        IMediaFormat mf = null;
        try {
            IjkTrackInfo info = ijk.getTrackInfo()[index];
            mf = info.getFormat();
        } catch (Throwable ignored) {
        }
        if (mf == null) return builder.build();
        String mime = safeGetString(mf, MediaFormat.KEY_MIME);
        String codec = safeGetString(mf, "codec-name");
        if (!TextUtils.isEmpty(codec)) builder.setCodecs(codec);
        if (!TextUtils.isEmpty(mime)) builder.setSampleMimeType(mime);
        int width = safeGetInteger(mf, MediaFormat.KEY_WIDTH);
        int height = safeGetInteger(mf, MediaFormat.KEY_HEIGHT);
        if (width > 0) builder.setWidth(width);
        if (height > 0) builder.setHeight(height);
        int sampleRate = safeGetInteger(mf, MediaFormat.KEY_SAMPLE_RATE);
        if (sampleRate > 0) builder.setSampleRate(sampleRate);
        int channelCount = safeGetInteger(mf, MediaFormat.KEY_CHANNEL_COUNT);
        if (channelCount > 0) builder.setChannelCount(channelCount);
        return builder.build();
    }

    private String getTrackLabel(int index, int ijkType) {
        String prefix;
        if (ijkType == ITrackType.AUDIO) prefix = "音轨";
        else if (ijkType == ITrackType.TIMEDTEXT || ijkType == ITrackType.SUBTITLE) prefix = "字幕";
        else if (ijkType == ITrackType.VIDEO) prefix = "视轨";
        else prefix = "轨道";
        return prefix + " " + (index + 1);
    }

    @Nullable
    private Integer parseTrackIndex(String format) {
        if (TextUtils.isEmpty(format)) return null;
        String id = format.split(",", 2)[0];
        String[] parts = id.split(":", 3);
        if (parts.length != 3 || !"ijk".equals(parts[0])) return null;
        try {
            return Integer.parseInt(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private String safeGetString(IMediaFormat mf, String key) {
        try {
            return mf.getString(key);
        } catch (Throwable e) {
            return null;
        }
    }

    private int safeGetInteger(IMediaFormat mf, String key) {
        try {
            return mf.getInteger(key);
        } catch (Throwable e) {
            return 0;
        }
    }

    // ---------------- 通用小工具 ----------------

    private SimpleBasePlayer.MediaItemData buildMediaItemData() {
        long durationUs = durationMs == C.TIME_UNSET ? C.TIME_UNSET : Util.msToUs(durationMs);
        return new SimpleBasePlayer.MediaItemData.Builder("ijk")
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setTracks(currentTracks)
                .setIsSeekable(durationMs > 0)
                .setIsDynamic(durationMs == C.TIME_UNSET)
                .setDurationUs(durationUs)
                .build();
    }

    private String describeError(int what, int extra) {
        String base;
        if (what == IMediaPlayer.MEDIA_ERROR_SERVER_DIED) base = "服务中断";
        else if (what == IMediaPlayer.MEDIA_ERROR_IO) base = "IO 错误";
        else if (what == IMediaPlayer.MEDIA_ERROR_MALFORMED) base = "数据异常";
        else if (what == IMediaPlayer.MEDIA_ERROR_UNSUPPORTED) base = "格式不支持";
        else if (what == IMediaPlayer.MEDIA_ERROR_TIMED_OUT) base = "连接超时";
        else base = "错误码 " + what;
        return base + "/" + extra;
    }

    private void setError(String message) {
        setError(message, null);
    }

    private void setError(String message, @Nullable Throwable cause) {
        playerError = new PlaybackException(TextUtils.isEmpty(message) ? "IJK 播放失败" : message, cause, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK);
        playbackState = Player.STATE_IDLE;
        loading = false;
        prepared = false;
        invalidateState();
    }

    private long getBufferedPositionMs() {
        if (prepared && durationMs != C.TIME_UNSET) return durationMs;
        return positionMs;
    }

    private long getTotalBufferedDurationMs() {
        return Math.max(0, getBufferedPositionMs() - sanitizePosition(positionMs));
    }

    private Size getCurrentSurfaceSize() {
        if (currentTextureView != null) return new Size(currentTextureView.getWidth(), currentTextureView.getHeight());
        if (currentSurfaceHolder != null) return new Size(currentSurfaceHolder.getSurfaceFrame().width(), currentSurfaceHolder.getSurfaceFrame().height());
        return Size.UNKNOWN;
    }

    private void markRenderedFirstFrame() {
        if (renderedFirstFrame) return;
        renderedFirstFrame = true;
        reportRenderedFirstFrame = true;
        if (playerError == null && playbackState == Player.STATE_BUFFERING) {
            playbackState = Player.STATE_READY;
            loading = false;
        }
    }

    private boolean consumeRenderedFirstFrame() {
        boolean value = reportRenderedFirstFrame;
        reportRenderedFirstFrame = false;
        return value;
    }

    private long sanitizePosition(long positionMs) {
        return positionMs == C.TIME_UNSET ? 0 : Math.max(0, positionMs);
    }
}
