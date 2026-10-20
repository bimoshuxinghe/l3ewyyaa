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
package androidx.media3.mpvplayer.nativebridge;

import static com.google.common.truth.Truth.assertThat;
import static is.xyz.mpv.MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR;
import static is.xyz.mpv.MPVLib.MpvError.MPV_ERROR_LOADING_FAILED;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import androidx.media3.common.Tracks;
import is.xyz.mpv.MPVLib;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class MpvEventAdapterTest {

  @Test
  public void durationInvalidated_doesNotInvalidateState() {
    MpvEventAdapter.PlayerEventHost playerEventHost =
        mock(MpvEventAdapter.PlayerEventHost.class);
    MpvEventAdapter.PropertyEventHost propertyEventHost =
        mock(MpvEventAdapter.PropertyEventHost.class);
    AtomicReference<Runnable> posted = capturePlayerLooperTask(playerEventHost);
    MpvEventAdapter adapter = createAdapter(playerEventHost, propertyEventHost);

    adapter.eventProperty(MpvConstants.PROP_DURATION_FULL);

    assertThat(posted.get()).isNotNull();
    posted.get().run();

    verify(playerEventHost, never()).invalidateState();
    verifyNoMoreInteractions(propertyEventHost);
  }

  @Test
  public void trackListChanged_beforeFileLoadedDoesNotReadTracks() {
    MpvEventAdapter.PlayerEventHost playerEventHost =
        mock(MpvEventAdapter.PlayerEventHost.class);
    MpvEventAdapter.PropertyEventHost propertyEventHost =
        mock(MpvEventAdapter.PropertyEventHost.class);
    AtomicReference<Runnable> posted = capturePlayerLooperTask(playerEventHost);
    MpvEventAdapter adapter = createAdapter(playerEventHost, propertyEventHost);

    adapter.eventProperty(MpvConstants.PROP_TRACK_LIST);

    verify(propertyEventHost, never()).readTracks();
    assertThat(posted.get()).isNull();
  }

  @Test
  public void fileLoaded_readsTracksBeforePostingAndAppliesOnPlayerLooper() {
    MpvEventAdapter.PlayerEventHost playerEventHost =
        mock(MpvEventAdapter.PlayerEventHost.class);
    MpvEventAdapter.PropertyEventHost propertyEventHost =
        mock(MpvEventAdapter.PropertyEventHost.class);
    AtomicReference<Runnable> posted = capturePlayerLooperTask(playerEventHost);
    when(propertyEventHost.readTracks()).thenReturn(Tracks.EMPTY);
    MpvEventAdapter adapter = createAdapter(playerEventHost, propertyEventHost);

    adapter.event(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED);

    verify(propertyEventHost).readTracks();
    verify(playerEventHost, never()).onFileLoaded(Tracks.EMPTY);
    assertThat(posted.get()).isNotNull();

    posted.get().run();

    verify(playerEventHost).onFileLoaded(Tracks.EMPTY);
  }

  @Test
  public void trackListChanged_afterFileLoadedReadsAndAppliesOnPlayerLooper() {
    MpvEventAdapter.PlayerEventHost playerEventHost =
        mock(MpvEventAdapter.PlayerEventHost.class);
    MpvEventAdapter.PropertyEventHost propertyEventHost =
        mock(MpvEventAdapter.PropertyEventHost.class);
    AtomicReference<Runnable> posted = capturePlayerLooperTask(playerEventHost);
    when(propertyEventHost.readTracks()).thenReturn(Tracks.EMPTY);
    when(propertyEventHost.onTrackPropertyChanged(Tracks.EMPTY)).thenReturn(true);
    MpvEventAdapter adapter = createAdapter(playerEventHost, propertyEventHost);
    adapter.event(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED);
    posted.get().run();
    clearInvocations(playerEventHost, propertyEventHost);
    posted.set(null);

    adapter.eventProperty(MpvConstants.PROP_TRACK_LIST);

    verify(propertyEventHost).readTracks();
    verify(propertyEventHost, never()).onTrackPropertyChanged(Tracks.EMPTY);
    assertThat(posted.get()).isNotNull();

    posted.get().run();

    verify(propertyEventHost).onTrackPropertyChanged(Tracks.EMPTY);
    verify(playerEventHost).invalidateState();
  }

  @Test
  public void albumArtChanged_readsBeforePostingAndAppliesOnPlayerLooper() {
    MpvEventAdapter.PlayerEventHost playerEventHost =
        mock(MpvEventAdapter.PlayerEventHost.class);
    MpvEventAdapter.PropertyEventHost propertyEventHost =
        mock(MpvEventAdapter.PropertyEventHost.class);
    AtomicReference<Runnable> posted = capturePlayerLooperTask(playerEventHost);
    byte[] artworkData = {1, 2, 3};
    when(propertyEventHost.readArtworkData()).thenReturn(artworkData);
    MpvEventAdapter adapter = createAdapter(playerEventHost, propertyEventHost);

    adapter.eventProperty(MpvConstants.PROP_CURRENT_VIDEO_ALBUMART, true);

    verify(propertyEventHost).readArtworkData();
    verify(propertyEventHost, never()).onAlbumArtProperty(true);
    verify(propertyEventHost, never()).onArtworkData(artworkData);
    assertThat(posted.get()).isNotNull();

    posted.get().run();

    verify(propertyEventHost).onAlbumArtProperty(true);
    verify(propertyEventHost).onArtworkData(artworkData);
    verify(playerEventHost).invalidateState();
  }

  @Test
  public void playbackRestart_readsPositionBeforePostingAndAppliesOnPlayerLooper() {
    MpvEventAdapter.PlayerEventHost playerEventHost =
        mock(MpvEventAdapter.PlayerEventHost.class);
    MpvEventAdapter.PropertyEventHost propertyEventHost =
        mock(MpvEventAdapter.PropertyEventHost.class);
    AtomicReference<Runnable> posted = capturePlayerLooperTask(playerEventHost);
    when(propertyEventHost.readPosition()).thenReturn(12.5);
    MpvEventAdapter adapter = createAdapter(playerEventHost, propertyEventHost);

    adapter.event(MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART);

    verify(propertyEventHost).readPosition();
    verify(playerEventHost, never()).onPlaybackRestart(12.5);
    assertThat(posted.get()).isNotNull();

    posted.get().run();

    verify(playerEventHost).onPlaybackRestart(12.5);
  }

  @Test
  public void commandReply_isAppliedOnPlayerLooper() {
    MpvEventAdapter.PlayerEventHost playerEventHost =
        mock(MpvEventAdapter.PlayerEventHost.class);
    MpvEventAdapter.PropertyEventHost propertyEventHost =
        mock(MpvEventAdapter.PropertyEventHost.class);
    MpvEventAdapter.CommandReplyListener listener =
        mock(MpvEventAdapter.CommandReplyListener.class);
    AtomicReference<Runnable> posted = capturePlayerLooperTask(playerEventHost);
    MpvEventAdapter adapter =
        new MpvEventAdapter(
            playerEventHost,
            propertyEventHost,
            listener,
            () -> {},
            new MpvLoadGeneration());

    adapter.eventCommandReply(7, -12);

    verify(listener, never()).onReply(7, -12);
    assertThat(posted.get()).isNotNull();

    posted.get().run();

    verify(listener).onReply(7, -12);
  }

  @Test
  public void endFile_queuedBeforeNextLoadRequest_isIgnored() {
    MpvEventAdapter.PlayerEventHost playerEventHost =
        mock(MpvEventAdapter.PlayerEventHost.class);
    MpvEventAdapter.PropertyEventHost propertyEventHost =
        mock(MpvEventAdapter.PropertyEventHost.class);
    AtomicReference<Runnable> posted = capturePlayerLooperTask(playerEventHost);
    MpvLoadGeneration loadGeneration = new MpvLoadGeneration();
    MpvEventAdapter adapter = createAdapter(playerEventHost, propertyEventHost, loadGeneration);

    adapter.eventEndFile(
        MPV_END_FILE_REASON_ERROR, MPV_ERROR_LOADING_FAILED, "loading failed");
    loadGeneration.onLoadRequested();
    posted.get().run();

    verify(playerEventHost, never())
        .onEndFile(MPV_END_FILE_REASON_ERROR, MPV_ERROR_LOADING_FAILED, "loading failed");
  }

  @Test
  public void endFile_fromActiveLoadAfterNextLoadRequest_isIgnored() {
    MpvEventAdapter.PlayerEventHost playerEventHost =
        mock(MpvEventAdapter.PlayerEventHost.class);
    MpvEventAdapter.PropertyEventHost propertyEventHost =
        mock(MpvEventAdapter.PropertyEventHost.class);
    AtomicReference<Runnable> posted = capturePlayerLooperTask(playerEventHost);
    MpvLoadGeneration loadGeneration = new MpvLoadGeneration();
    MpvEventAdapter adapter = createAdapter(playerEventHost, propertyEventHost, loadGeneration);
    loadGeneration.onLoadRequested();
    adapter.event(MPVLib.MpvEvent.MPV_EVENT_START_FILE);
    loadGeneration.onLoadRequested();

    adapter.eventEndFile(
        MPV_END_FILE_REASON_ERROR, MPV_ERROR_LOADING_FAILED, "loading failed");
    posted.get().run();

    verify(playerEventHost, never())
        .onEndFile(MPV_END_FILE_REASON_ERROR, MPV_ERROR_LOADING_FAILED, "loading failed");
  }

  @Test
  public void endFile_fromCurrentLoad_isAppliedOnPlayerLooper() {
    MpvEventAdapter.PlayerEventHost playerEventHost =
        mock(MpvEventAdapter.PlayerEventHost.class);
    MpvEventAdapter.PropertyEventHost propertyEventHost =
        mock(MpvEventAdapter.PropertyEventHost.class);
    AtomicReference<Runnable> posted = capturePlayerLooperTask(playerEventHost);
    MpvLoadGeneration loadGeneration = new MpvLoadGeneration();
    MpvEventAdapter adapter = createAdapter(playerEventHost, propertyEventHost, loadGeneration);
    loadGeneration.onLoadRequested();
    adapter.event(MPVLib.MpvEvent.MPV_EVENT_START_FILE);

    adapter.eventEndFile(
        MPV_END_FILE_REASON_ERROR, MPV_ERROR_LOADING_FAILED, "loading failed");
    posted.get().run();

    verify(playerEventHost)
        .onEndFile(MPV_END_FILE_REASON_ERROR, MPV_ERROR_LOADING_FAILED, "loading failed");
  }

  private static MpvEventAdapter createAdapter(
      MpvEventAdapter.PlayerEventHost playerEventHost,
      MpvEventAdapter.PropertyEventHost propertyEventHost) {
    return createAdapter(playerEventHost, propertyEventHost, new MpvLoadGeneration());
  }

  private static MpvEventAdapter createAdapter(
      MpvEventAdapter.PlayerEventHost playerEventHost,
      MpvEventAdapter.PropertyEventHost propertyEventHost,
      MpvLoadGeneration loadGeneration) {
    return new MpvEventAdapter(
        playerEventHost,
        propertyEventHost,
        (requestId, error) -> {},
        () -> {},
        loadGeneration);
  }

  private static AtomicReference<Runnable> capturePlayerLooperTask(
      MpvEventAdapter.PlayerEventHost playerEventHost) {
    AtomicReference<Runnable> posted = new AtomicReference<>();
    doAnswer(
            invocation -> {
              posted.set(invocation.getArgument(0));
              return null;
            })
        .when(playerEventHost)
        .runOnPlayerLooper(any());
    return posted;
  }
}
