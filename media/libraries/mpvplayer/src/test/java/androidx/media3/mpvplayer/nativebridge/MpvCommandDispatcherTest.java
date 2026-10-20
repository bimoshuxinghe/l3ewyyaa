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
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.net.Uri;
import java.util.Collections;
import org.junit.Test;

public final class MpvCommandDispatcherTest {

  @Test
  public void toggleGeneralStats_queuesCommand() {
    MpvClient client = mock(MpvClient.class);
    MpvCommandDispatcher dispatcher = new MpvCommandDispatcher(client);
    String[] command = {"script-binding", "stats/display-page-1-toggle"};
    when(client.command(aryEq(command))).thenReturn(true);

    boolean queued = dispatcher.toggleGeneralStats();

    assertThat(queued).isTrue();
    verify(client).command(aryEq(command));
    verifyNoMoreInteractions(client);
  }

  @Test
  public void loadFile_queuesCommand() {
    MpvClient client = mock(MpvClient.class);
    MpvCommandDispatcher dispatcher = new MpvCommandDispatcher(client);
    Uri uri = mock(Uri.class);
    String[] command = {"loadfile", "https://example.test/video.m3u8", "replace"};
    when(uri.toString()).thenReturn("https://example.test/video.m3u8");
    when(client.command(aryEq(command))).thenReturn(true);

    boolean queued = dispatcher.loadFile(uri, Collections.emptyList());

    assertThat(queued).isTrue();
    verify(client).command(aryEq(command));
    verifyNoMoreInteractions(client);
  }
}
