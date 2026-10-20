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
import static is.xyz.mpv.MPVLib.MpvError.MPV_ERROR_LOADING_FAILED;
import static is.xyz.mpv.MPVLib.MpvError.MPV_ERROR_UNKNOWN_FORMAT;

import android.net.Uri;
import androidx.media3.common.PlaybackException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public final class MpvErrorMapperTest {

  @Test
  public void loadingFailed_networkUri_mapsToIoUnspecified() {
    int errorCode =
        MpvErrorMapper.getErrorCode(
            Uri.parse("https://cdn6.cc.cd/163189/fhzx"), MPV_ERROR_LOADING_FAILED);

    assertThat(errorCode).isEqualTo(PlaybackException.ERROR_CODE_IO_UNSPECIFIED);
  }

  @Test
  public void unknownFormat_networkUri_mapsToContainerUnsupported() {
    int errorCode =
        MpvErrorMapper.getErrorCode(
            Uri.parse("https://cdn6.cc.cd/163189/fhzx"), MPV_ERROR_UNKNOWN_FORMAT);

    assertThat(errorCode)
        .isEqualTo(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED);
  }
}
