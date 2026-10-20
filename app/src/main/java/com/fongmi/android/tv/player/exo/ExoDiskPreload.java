package com.fongmi.android.tv.player.exo;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.source.preload.PreloadException;
import androidx.media3.exoplayer.source.preload.PreloadManagerListener;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PreloadSetting;

public class ExoDiskPreload {

    private DefaultPreloadManager manager;
    private MediaItem mediaItem;

    public void start(ExoPlayer player, MediaItem mediaItem) {
        this.mediaItem = mediaItem;
        restart();
    }

    public void stop() {
        releaseManager();
        mediaItem = null;
    }

    public void release() {
        stop();
    }

    private void restart() {
        releaseManager();
        if (mediaItem == null) return;
        if (!PreloadSetting.isEnabled()) return;
        manager = createManager(mediaItem);
        manager.addListener(new Listener());
        manager.add(mediaItem, 0);
        manager.invalidate();
    }

    private void releaseManager() {
        if (manager == null) return;
        manager.remove(mediaItem);
        manager.release();
        manager = null;
    }

    private DefaultPreloadManager createManager(MediaItem item) {
        return new DefaultPreloadManager.Builder(App.get(), ignored -> DefaultPreloadManager.PreloadStatus.specifiedRangeLoaded(PreloadSetting.getDurationMs())).setMediaSourceFactorySupplier(ExoMediaSourceFactory.supplier()).setRenderersFactory(ExoUtil.buildRenderersFactory()).build();
    }

    private final class Listener implements PreloadManagerListener {

        @Override
        public void onCompleted(@NonNull MediaItem mediaItem) {
        }

        @Override
        public void onError(PreloadException exception) {
        }
    }
}
