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

import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.Player;
import org.junit.Test;

public final class MpvPlaybackStateTest {

  private static final long DURATION_MS = 120_000;

  @Test
  public void endFile_preservesTimelineDuration() {
    MpvPlaybackState playbackState = createEndedPlaybackState();

    assertThat(playbackState.getState()).isEqualTo(Player.STATE_ENDED);
    assertThat(playbackState.getPositionMs()).isEqualTo(DURATION_MS);
    assertThat(playbackState.getTimelineDurationMs()).isEqualTo(DURATION_MS);
  }

  @Test
  public void resetCurrentMedia_afterEndFileClearsTimelineDuration() {
    MpvPlaybackState playbackState = createEndedPlaybackState();

    playbackState.resetCurrentMedia(0);

    assertThat(playbackState.getTimelineDurationMs()).isEqualTo(C.TIME_UNSET);
  }

  private static MpvPlaybackState createLoadedPlaybackState() {
    MpvPlaybackState playbackState = new MpvPlaybackState();
    playbackState.startLoading(0, 0, C.TIME_END_OF_SOURCE);
    playbackState.onDurationProperty(DURATION_MS / 1000.0);
    playbackState.onFileLoaded();
    return playbackState;
  }

  private static MpvPlaybackState createEndedPlaybackState() {
    MpvPlaybackState playbackState = createLoadedPlaybackState();
    playbackState.onEndFile();
    return playbackState;
  }
}
