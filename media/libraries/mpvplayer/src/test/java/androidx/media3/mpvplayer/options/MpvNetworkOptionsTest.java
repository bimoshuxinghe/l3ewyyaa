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
import androidx.media3.common.MimeTypes;
import org.junit.Test;

public final class MpvNetworkOptionsTest {

  @Test
  public void apply_explicitHlsWithNonStandardUri_relaxesExtensionCheck() {
    MpvPerFileOptions options = new MpvPerFileOptions();

    MpvNetworkOptions.apply(
        uri("https://cdn6.cc.cd/163189/fhzx", "fhzx"),
        MimeTypes.APPLICATION_M3U8,
        options::add);

    assertThat(options.build())
        .containsExactly(
            "demuxer-lavf-format=hls",
            "demuxer-lavf-o-add=extension_picky=0,http_persistent=0")
        .inOrder();
  }

  @Test
  public void apply_standardHlsUri_keepsExtensionCheck() {
    MpvPerFileOptions options = new MpvPerFileOptions();

    MpvNetworkOptions.apply(
        uri("https://example.test/live.m3u8", "live.m3u8"),
        /* mimeType= */ null,
        options::add);

    assertThat(options.build())
        .containsExactly(
            "demuxer-lavf-format=hls", "demuxer-lavf-o-add=http_persistent=0")
        .inOrder();
  }

  @Test
  public void apply_nonHlsUri_addsNoOptions() {
    MpvPerFileOptions options = new MpvPerFileOptions();

    MpvNetworkOptions.apply(
        uri("https://example.test/video", "video"),
        /* mimeType= */ null,
        options::add);

    assertThat(options.build()).isEmpty();
  }

  private static Uri uri(String value, String lastPathSegment) {
    Uri uri = mock(Uri.class);
    when(uri.getScheme()).thenReturn("https");
    when(uri.toString()).thenReturn(value);
    when(uri.getLastPathSegment()).thenReturn(lastPathSegment);
    when(uri.getPath()).thenReturn("/" + lastPathSegment);
    return uri;
  }
}
