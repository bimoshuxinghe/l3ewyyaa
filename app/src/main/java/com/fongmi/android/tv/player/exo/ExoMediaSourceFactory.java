package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.preload.MediaSourceFactorySupplier;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.ts.TsExtractor;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.PreloadSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.io.File;
import java.util.Map;

public class ExoMediaSourceFactory implements MediaSource.Factory {

    private static final int CACHE_SPACE_PERCENT = 80;

    private static StandaloneDatabaseProvider databaseProvider;
    private static Cache cache;

    private final DefaultMediaSourceFactory defaultMediaSourceFactory;

    private HttpDataSource.Factory httpDataSourceFactory;
    private DataSource.Factory dataSourceFactory;

    public ExoMediaSourceFactory() {
        this.defaultMediaSourceFactory = new DefaultMediaSourceFactory(getDataSourceFactory(), createDefaultExtractorsFactory());
    }

    static MediaSourceFactorySupplier supplier() {
        return new MediaSourceFactorySupplier() {
            @NonNull
            @Override
            public MediaSourceFactorySupplier setCache(Cache cache) {
                return this;
            }

            @NonNull
            @Override
            public MediaSourceFactorySupplier setDataSourceFactory(DataSource.Factory dataSourceFactory) {
                return this;
            }

            @Override
            public MediaSource.Factory get() {
                return new ExoMediaSourceFactory();
            }
        };
    }

    static DataSource.Factory createUpstreamDataSourceFactory(Map<String, String> headers) {
        HttpDataSource.Factory factory = new OkHttpDataSource.Factory(OkHttp.player());
        factory.setDefaultRequestProperties(headers);
        return new DefaultDataSource.Factory(App.get(), factory);
    }

    static synchronized Cache getCache() {
        if (cache != null) return cache;
        File dir = Path.exo();
        return cache = new SimpleCache(dir, new LeastRecentlyUsedCacheEvictor(getMaxCacheSize(dir)), getDatabaseProvider());
    }

    private static StandaloneDatabaseProvider getDatabaseProvider() {
        if (databaseProvider == null) databaseProvider = new StandaloneDatabaseProvider(App.get());
        return databaseProvider;
    }

    private static long folderSize(File dir) {
        if (!dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long size = 0;
        for (File file : files) size += file.isDirectory() ? folderSize(file) : file.length();
        return size;
    }

    private static long getMaxCacheSize(File dir) {
        long usedBytes = folderSize(dir);
        long availableBytes = Math.max(0, dir.getUsableSpace());
        long storageBudget = (usedBytes + availableBytes) * CACHE_SPACE_PERCENT / 100;
        return Math.min(PreloadSetting.getSizeBytes(), storageBudget);
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
        return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
        return defaultMediaSourceFactory.getSupportedTypes();
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
        getHttpDataSourceFactory().setDefaultRequestProperties(ExoUtil.extractHeaders(mediaItem));
        String url = mediaItem.requestMetadata.mediaUri != null ? mediaItem.requestMetadata.mediaUri.toString() : "";
        if (url.contains("***") && url.contains("|||")) return createConcatenatingMediaSource(mediaItem, url);
        if (url.startsWith("rtsp://")) return createRtspMediaSource(mediaItem);
        return defaultMediaSourceFactory.createMediaSource(mediaItem);
    }

    private MediaSource createRtspMediaSource(MediaItem mediaItem) {
        RtspMediaSource.Factory factory = new RtspMediaSource.Factory().setDebugLoggingEnabled(true).setForceUseRtpTcp(false).setTimeoutMs(15000).setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(3));
        MediaItem.Builder builder = mediaItem.buildUpon();
        if (mediaItem.requestMetadata.mediaUri.toString().toLowerCase().endsWith(".sdp")) builder.setMimeType(MimeTypes.APPLICATION_RTSP);
        return factory.createMediaSource(builder.build());
    }

    private MediaSource createConcatenatingMediaSource(MediaItem mediaItem, String url) {
        ConcatenatingMediaSource2.Builder builder = new ConcatenatingMediaSource2.Builder();
        for (String split : url.split("\\*\\*\\*")) {
            String[] info = split.split("\\|\\|\\|");
            if (info.length >= 2) {
                MediaItem item = mediaItem.buildUpon().setUri(Uri.parse(info[0])).build();
                if (info[0].startsWith("rtsp://")) builder.add(createRtspMediaSource(item), Long.parseLong(info[1]));
                else builder.add(defaultMediaSourceFactory.createMediaSource(item), Long.parseLong(info[1]));
            }
        }
        return builder.build();
    }

    private static ExtractorsFactory createDefaultExtractorsFactory() {
        return new DefaultExtractorsFactory().setTsExtractorTimestampSearchBytes(TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES * 10);
    }

    private DataSource.Factory getDataSourceFactory() {
        if (dataSourceFactory == null) dataSourceFactory = () -> getCacheDataSource(new DefaultDataSource.Factory(App.get(), getHttpDataSourceFactory())).createDataSource();
        return dataSourceFactory;
    }

    private CacheDataSource.Factory getCacheDataSource(DataSource.Factory upstreamFactory) {
        // 缓存 key 带上「智能去广」开关状态：切换开关后旧缓存（未过滤/已过滤）自动失效，避免读到旧内容
        return new CacheDataSource.Factory().setCache(getCache()).setUpstreamDataSourceFactory(upstreamFactory).setCacheWriteDataSinkFactory(null).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR).setCacheKeyFactory(dataSpec -> (dataSpec.key != null ? dataSpec.key : dataSpec.uri.toString()) + (PlayerSetting.isAdFilter() ? "#adf1" : "#adf0"));
    }

    private HttpDataSource.Factory getHttpDataSourceFactory() {
        if (httpDataSourceFactory == null) {
            OkHttpDataSource.Factory ok = new OkHttpDataSource.Factory(OkHttp.player());
            HttpDataSource.Factory base = ok;
            httpDataSourceFactory = new HttpDataSource.Factory() {
                @Override
                public HttpDataSource createDataSource() {
                    return new FilteringHttpDataSource(base.createDataSource());
                }

                @Override
                public HttpDataSource.Factory setDefaultRequestProperties(Map<String, String> defaultRequestProperties) {
                    base.setDefaultRequestProperties(defaultRequestProperties);
                    return this;
                }
            };
        }
        return httpDataSourceFactory;
    }
}
