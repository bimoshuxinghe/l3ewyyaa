package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.CaptioningManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextRenderer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.bean.Drm;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.media.PlaySpec;
import com.fongmi.android.tv.player.track.LangUtil;
import com.fongmi.android.tv.setting.DecodeSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.setting.SpeedSetting;
import com.fongmi.android.tv.utils.UrlUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public final class ExoUtil {

    public static void setPlayerView(PlayerView view) {
        view.setRender(PlayerSetting.getRender());
        view.getSubtitleView().setStyle(getCaptionStyle());
        view.getSubtitleView().setApplyEmbeddedStyles(true);
        view.getSubtitleView().setApplyEmbeddedFontSizes(false);
        if (PlayerSetting.getSubtitlePosition() != 0) view.getSubtitleView().setBottomPosition(PlayerSetting.getSubtitlePosition());
        if (PlayerSetting.getSubtitleTextSize() != 0) view.getSubtitleView().setFractionalTextSize(PlayerSetting.getSubtitleTextSize());
    }

    public static ExoPlayer buildPlayer(Player.Listener listener, DefaultPreloadManager.Builder preloadManagerBuilder) {
        ExoPlayer.Builder playerBuilder = new ExoPlayer.Builder(App.get()).setSkipSilenceEnabled(SpeedSetting.isSkipSilence());
        ExoPlayer player = preloadManagerBuilder.buildExoPlayer(playerBuilder);
        if (BuildConfig.DEBUG) player.addAnalyticsListener(new EventLogger());
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        player.addListener(listener);
        return player;
    }

    @Nullable
    public static String getMimeType(int errorCode) {
        return switch (errorCode) {
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> MimeTypes.APPLICATION_M3U8;
            case PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> MimeTypes.APPLICATION_OCTET_STREAM;
            default -> null;
        };
    }

    static LoadControl buildLoadControl(int maxPreloadBufferBytes) {
        int buffer = PlayerSetting.getBuffer();
        return new DefaultLoadControl.Builder().setBufferDurationsMs(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * buffer, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS).setPlayerTargetBufferBytes(PlayerId.PRELOAD.name, maxPreloadBufferBytes).build();
    }

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return new HashMap<>();
        return extras.keySet().stream().filter(key -> extras.getString(key) != null).collect(Collectors.toMap(key -> key, extras::getString));
    }

    static DefaultTrackSelector buildTrackSelector() {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (PlayerSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        builder.setPreferredTextLanguages(LangUtil.getPreferredTextLanguages());
        builder.setTunnelingEnabled(PlayerSetting.isTunnel());
        builder.setForceHighestSupportedBitrate(true);
        // DV7 handling: when stripping DV7 (STRIP mode or AUTO without device support),
        // prefer non-DV video mime types to avoid selecting DV7 tracks.
        boolean dv7Strip = shouldStripDv7();
        if (!allowDolbyVision() || dv7Strip) builder.setPreferredVideoMimeTypes(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_VP8);
        trackSelector.setParameters(builder.build());
        return trackSelector;
    }

    static RenderersFactory buildRenderersFactory() {
        return buildRenderersFactory(null, null, PlayerEngine.HARD);
    }

    static RenderersFactory buildRenderersFactory(int decode) {
        return buildRenderersFactory(null, null, decode);
    }

    static RenderersFactory buildRenderersFactory(@Nullable AudioProcessor audioProcessor, @Nullable TextOutput secondaryTextOutput) {
        return buildRenderersFactory(audioProcessor, secondaryTextOutput, PlayerEngine.HARD);
    }

    static RenderersFactory buildRenderersFactory(@Nullable AudioProcessor audioProcessor, @Nullable TextOutput secondaryTextOutput, int decode) {
        return new ExoRenderersFactory(audioProcessor, secondaryTextOutput, decode);
    }

    private static AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams, @Nullable AudioProcessor audioProcessor) {
        DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context).setEnableFloatOutput(enableFloatOutput).setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams);
        if (!DecodeSetting.isAudioPassThrough()) builder.setAudioOutputProvider(new AudioTrackAudioOutputProvider.Builder(null).build());
        if (audioProcessor != null) builder.setAudioProcessors(new AudioProcessor[]{audioProcessor});
        return builder.build();
    }

    public static void applyDolbyVisionPolicy(Player player) {
        // DV7 specific handling: detect selected DV7 track and apply fallback policy.
        boolean dv7Fallback = false;
        if (getDv7HandlingMode() != PlayerSetting.DV7_OFF) {
            for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
                for (int i = 0; i < group.length; i++) {
                    if (group.isTrackSelected(i) && isDolbyVisionProfile7(group.getTrackFormat(i))) {
                        dv7Fallback = shouldStripDv7();
                        break;
                    }
                }
                if (dv7Fallback) break;
            }
        }
        if (!dv7Fallback && allowDolbyVision()) return;
        Tracks tracks = player.getCurrentTracks();
        TrackGroup bestGroup = null;
        int bestIndex = -1;
        long bestScore = -1;
        boolean hasDolbyVision = false;
        boolean selectedDolbyVision = false;
        boolean selectedNonDolbyVision = false;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                boolean dolbyVision = isDolbyVision(format);
                hasDolbyVision |= dolbyVision;
                selectedDolbyVision |= dolbyVision && group.isTrackSelected(i);
                selectedNonDolbyVision |= !dolbyVision && group.isTrackSelected(i);
                if (dolbyVision || !group.isTrackSupported(i)) continue;
                long score = videoScore(format);
                if (score > bestScore) {
                    bestScore = score;
                    bestGroup = group.getMediaTrackGroup();
                    bestIndex = i;
                }
            }
        }
        if (!hasDolbyVision || bestGroup == null || (selectedNonDolbyVision && !selectedDolbyVision)) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setOverrideForType(new TrackSelectionOverride(bestGroup, List.of(bestIndex))).build());
    }

    public static boolean allowDolbyVision() {
        return PlayerSetting.isExoDolbyVisionPassthrough() && hasDolbyVisionDecoder() && hasDolbyVisionDisplay();
    }

    private static boolean hasDolbyVisionDecoder() {
        try {
            return !MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, PlayerSetting.isTunnel()).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasDolbyVisionDisplay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        WindowManager manager = (WindowManager) App.get().getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) return false;
        Display display = manager.getDefaultDisplay();
        if (display == null) return false;
        for (int type : display.getHdrCapabilities().getSupportedHdrTypes()) {
            if (type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) return true;
        }
        return false;
    }

    public static boolean isDolbyVision(Format format) {
        if (MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) return true;
        String codecs = format.codecs == null ? "" : format.codecs.toLowerCase(Locale.US);
        return codecs.contains("dvhe") || codecs.contains("dvh1") || codecs.contains("dva1") || codecs.contains("dvav");
    }

    public static boolean isDolbyVisionProfile7(Format format) {
        if (format == null) return false;
        String codecs = format.codecs == null ? "" : format.codecs.toLowerCase(Locale.US);
        return codecs.contains("dvhe.07") || codecs.contains("dvh1.07") || codecs.contains("dva1.07") || codecs.contains("dvav.07");
    }

    public static String rewriteDv7ToDv81(String codecs) {
        if (codecs == null) return null;
        return codecs.replaceAll("(?i)(dvhe|dvav|dvh1|dva1)\\.0[57]\\.", "$1.08.");
    }

    public static boolean hasDolbyVisionProfile7Support() {
        try {
            android.media.MediaCodecInfo[] codecInfos = new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).getCodecInfos();
            for (android.media.MediaCodecInfo codecInfo : codecInfos) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && codecInfo.isEncoder()) continue;
                for (String type : codecInfo.getSupportedTypes()) {
                    android.media.MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType(type);
                    if (caps == null || caps.profileLevels == null) continue;
                    for (android.media.MediaCodecInfo.CodecProfileLevel level : caps.profileLevels) {
                        if (level.profile == 7) return true;
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    public static int getDv7HandlingMode() {
        return PlayerSetting.getDv7HandlingMode();
    }

    private static boolean shouldStripDv7() {
        int mode = getDv7HandlingMode();
        if (mode == PlayerSetting.DV7_STRIP) return true;
        if (mode == PlayerSetting.DV7_CONVERT) return true;
        if (mode == PlayerSetting.DV7_AUTO) return !hasDolbyVisionProfile7Support();
        return false;
    }

    private static long videoScore(Format format) {
        long width = Math.max(format.width, 0);
        long height = Math.max(format.height, 0);
        long bitrate = Math.max(format.bitrate, 0);
        return width * height * 1_000_000L + bitrate;
    }

    private static CaptionStyleCompat getCaptionStyle() {
        return PlayerSetting.isCaption() ? CaptionStyleCompat.createFromCaptionStyle(((CaptioningManager) App.get().getSystemService(Context.CAPTIONING_SERVICE)).getUserStyle()) : new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null);
    }

    public static MediaItem getMediaItem(PlaySpec spec, int decode) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(spec.getUri());
        builder.setSubtitleConfigurations(buildSubtitleConfigs(spec.getSubs()));
        builder.setDrmConfiguration(buildDrmConfig(spec.getDrm()));
        builder.setRequestMetadata(buildRequestMetadata(spec));
        builder.setMediaMetadata(spec.getMetadata());
        builder.setAdblock(Setting.isAdblock());
        builder.setMimeType(getDv7AwareMimeType(spec.getFormat()));
        builder.setImageDurationMs(15000);
        builder.setMediaId(spec.getKey());
        builder.setDecode(decode);
        return builder.build();
    }

    private static String getDv7AwareMimeType(String format) {
        return format;
    }

    private static MediaItem.RequestMetadata buildRequestMetadata(PlaySpec spec) {
        return new MediaItem.RequestMetadata.Builder().setMediaUri(spec.getUri()).setExtras(PlayerHelper.toBundle(spec.getHeaders())).build();
    }

    private static List<MediaItem.SubtitleConfiguration> buildSubtitleConfigs(List<Sub> subs) {
        List<MediaItem.SubtitleConfiguration> configs = new ArrayList<>();
        if (subs != null) for (Sub sub : subs) configs.add(buildSubConfig(sub));
        return configs;
    }

    private static MediaItem.SubtitleConfiguration buildSubConfig(Sub sub) {
        return new MediaItem.SubtitleConfiguration.Builder(Uri.parse(UrlUtil.convert(sub.getUrl()))).setLabel(sub.getName()).setMimeType(sub.getFormat()).setSelectionFlags(sub.getFlag()).setLanguage(sub.getLang()).build();
    }

    private static MediaItem.DrmConfiguration buildDrmConfig(Drm drm) {
        return drm == null ? null : new MediaItem.DrmConfiguration.Builder(drm.getUUID()).setMultiSession(!C.CLEARKEY_UUID.equals(drm.getUUID())).setForceDefaultLicenseUri(drm.isForceKey()).setLicenseRequestHeaders(drm.getHeader()).setLicenseUri(drm.getKey()).build();
    }

    private static final class ExoRenderersFactory extends DefaultRenderersFactory {

        @Nullable private final AudioProcessor audioProcessor;
        @Nullable private final TextOutput secondaryTextOutput;
        private final int decode;

        private ExoRenderersFactory(@Nullable AudioProcessor audioProcessor, @Nullable TextOutput secondaryTextOutput, int decode) {
            super(App.get());
            this.audioProcessor = audioProcessor;
            this.secondaryTextOutput = secondaryTextOutput;
            this.decode = decode;
            setEnableDecoderFallback(true);
            setExtensionRendererMode(getRenderMode(decode));
        }

        private static int getRenderMode(int decode) {
            return decode == PlayerEngine.HARD ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
        }

        @Override
        protected AudioSink buildAudioSink(@NonNull Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParams) {
            return ExoUtil.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams, audioProcessor);
        }

        @Override
        protected void buildTextRenderers(@NonNull Context context, @NonNull TextOutput output, @NonNull Looper outputLooper, int extensionRendererMode, @NonNull ArrayList<Renderer> out) {
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out);
            if (secondaryTextOutput != null) out.add(new TextRenderer(secondaryTextOutput, outputLooper));
        }
    }
}
