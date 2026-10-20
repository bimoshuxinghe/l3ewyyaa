/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.mpvplayer.core;

import static is.xyz.mpv.MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR;
import static is.xyz.mpv.MPVLib.MpvError.MPV_ERROR_LOADING_FAILED;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.Uri;
import androidx.media3.common.PlaybackException;
import androidx.media3.mpvplayer.audio.MpvAudioFocusManager;
import androidx.media3.mpvplayer.media.MpvArtworkLoader;
import androidx.media3.mpvplayer.media.MpvChapterController;
import androidx.media3.mpvplayer.media.MpvEditionController;
import androidx.media3.mpvplayer.media.MpvEndFileGuard;
import androidx.media3.mpvplayer.media.MpvPlaybackNavigator;
import androidx.media3.mpvplayer.media.MpvPlaylist;
import androidx.media3.mpvplayer.media.MpvSubtitleController;
import androidx.media3.mpvplayer.nativebridge.MpvPlaybackErrorFactory;
import androidx.media3.mpvplayer.seek.MpvSeekController;
import androidx.media3.mpvplayer.trackselection.MpvTrackController;
import androidx.media3.mpvplayer.video.MpvVideoState;
import androidx.media3.mpvplayer.video.MpvVideoTrackEnableGate;
import org.junit.Test;

public final class MpvPlaybackEventHandlerTest {

  @Test
  public void onEndFile_loadFailureAfterNextStart_reportsPlayerError() {
    Uri uri = mock(Uri.class);
    PlaybackException error = mock(PlaybackException.class);
    MpvPlaylist playlist = mock(MpvPlaylist.class);
    MpvPlaybackState playbackState = mock(MpvPlaybackState.class);
    MpvEndFileGuard endFileGuard = new MpvEndFileGuard();
    MpvPlaybackErrorFactory errorFactory = mock(MpvPlaybackErrorFactory.class);
    MpvPlaybackEventHandler.Host host = mock(MpvPlaybackEventHandler.Host.class);
    endFileGuard.expect();
    when(playlist.currentUri()).thenReturn(uri);
    when(playbackState.onEndFile()).thenReturn(true);
    when(errorFactory.createEndFileFailure(
            uri,
            /* loadFailed= */ true,
            MPV_ERROR_LOADING_FAILED,
            "loading failed"))
        .thenReturn(error);
    MpvPlaybackEventHandler handler =
        new MpvPlaybackEventHandler(
            mock(MpvPlaybackEventState.class),
            playlist,
            mock(MpvPlaybackNavigator.class),
            playbackState,
            endFileGuard,
            mock(MpvChapterController.class),
            mock(MpvEditionController.class),
            mock(MpvSubtitleController.class),
            mock(MpvSeekController.class),
            mock(MpvTrackController.class),
            mock(MpvVideoState.class),
            mock(MpvVideoTrackEnableGate.class),
            mock(MpvPlaybackPropertyUpdater.class),
            mock(MpvAudioFocusManager.class),
            errorFactory,
            mock(MpvArtworkLoader.class),
            host);

    handler.onStartFile();
    handler.onEndFile(
        MPV_END_FILE_REASON_ERROR, MPV_ERROR_LOADING_FAILED, "loading failed");

    verify(host).fail(error);
  }
}
