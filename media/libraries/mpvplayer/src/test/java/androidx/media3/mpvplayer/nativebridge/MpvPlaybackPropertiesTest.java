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

import static androidx.media3.mpvplayer.nativebridge.MpvConstants.PROP_IDLE_ACTIVE;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.Test;

public final class MpvPlaybackPropertiesTest {

  @Test
  public void hasActiveFile_idleActiveFalse_returnsTrue() {
    MpvPropertyAccessor properties = mock(MpvPropertyAccessor.class);
    when(properties.getBoolean(PROP_IDLE_ACTIVE)).thenReturn(false);

    boolean hasActiveFile = new MpvPlaybackProperties(properties).hasActiveFile(false);

    assertThat(hasActiveFile).isTrue();
  }

  @Test
  public void hasActiveFile_idleActiveTrue_returnsFalse() {
    MpvPropertyAccessor properties = mock(MpvPropertyAccessor.class);
    when(properties.getBoolean(PROP_IDLE_ACTIVE)).thenReturn(true);

    boolean hasActiveFile = new MpvPlaybackProperties(properties).hasActiveFile(true);

    assertThat(hasActiveFile).isFalse();
  }

  @Test
  public void hasActiveFile_idleActiveUnavailable_returnsFallback() {
    MpvPropertyAccessor properties = mock(MpvPropertyAccessor.class);
    when(properties.getBoolean(PROP_IDLE_ACTIVE)).thenReturn(null);

    assertThat(new MpvPlaybackProperties(properties).hasActiveFile(true)).isTrue();
    assertThat(new MpvPlaybackProperties(properties).hasActiveFile(false)).isFalse();
  }
}
