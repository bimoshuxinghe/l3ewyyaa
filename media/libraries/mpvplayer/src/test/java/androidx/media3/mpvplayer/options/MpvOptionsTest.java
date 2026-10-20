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
package androidx.media3.mpvplayer.options;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.Uri;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public final class MpvOptionsTest {

  @Test
  public void deferVideoOutputUntilLoad_movesConfiguredOutputToPerFileOptions() {
    MpvOptions options = new MpvOptions(new MpvPlayerConfig.Builder().build(), null);
    AtomicReference<String> initializationOption = new AtomicReference<>();

    options.deferVideoOutputUntilLoad(
        "gpu-next",
        (name, value) -> initializationOption.set(name + "=" + value));
    MpvPerFileOptions perFileOptions = new MpvPerFileOptions();
    options.addPerFileOptions(localUri(), /* mimeType= */ null, perFileOptions);

    assertThat(initializationOption.get()).isEqualTo("vo=null");
    assertThat(perFileOptions.build()).contains("vo=gpu-next");
  }

  @Test
  public void deferVideoOutputUntilLoad_withNullOutput_doesNotAddPerFileOption() {
    MpvOptions options = new MpvOptions(new MpvPlayerConfig.Builder().build(), null);
    AtomicReference<String> initializationOption = new AtomicReference<>();

    options.deferVideoOutputUntilLoad(
        "null",
        (name, value) -> initializationOption.set(name + "=" + value));
    MpvPerFileOptions perFileOptions = new MpvPerFileOptions();
    options.addPerFileOptions(localUri(), /* mimeType= */ null, perFileOptions);

    assertThat(initializationOption.get()).isNull();
    assertThat(perFileOptions.build()).doesNotContain("vo=null");
  }

  private static Uri localUri() {
    Uri uri = mock(Uri.class);
    when(uri.getScheme()).thenReturn("file");
    return uri;
  }
}
