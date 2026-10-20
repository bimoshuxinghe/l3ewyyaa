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

import android.content.Context;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import androidx.media3.mpvplayer.MpvSubtitleOptions;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class MpvSubtitleOptionDefaultsTest {

  @Test
  public void defaultOptions_doNotOverrideSubtitleConfiguration() {
    MpvPlayerConfig config =
        new MpvPlayerConfig.Builder()
            .addAndroidSubtitleOptions(mock(Context.class), new MpvSubtitleOptions.Builder().build())
            .build();
    List<String> applied = new ArrayList<>();

    config.applySubtitle(
        (name, value) -> applied.add(name + "=" + value),
        (name, value) -> applied.add(name + "=" + value));

    assertThat(applied).isEmpty();
  }

  @Test
  public void explicitOptions_onlyApplyConfiguredSubtitleValues() {
    MpvSubtitleOptions subtitleOptions =
        new MpvSubtitleOptions.Builder().setPosition(80.0).setScale(1.25).build();
    MpvPlayerConfig config =
        new MpvPlayerConfig.Builder()
            .addAndroidSubtitleOptions(mock(Context.class), subtitleOptions)
            .build();
    List<String> applied = new ArrayList<>();

    config.applySubtitle(
        (name, value) -> applied.add(name + "=" + value),
        (name, value) -> applied.add(name + "=" + value));

    assertThat(applied)
        .containsExactly("sub-scale-signs=yes", "sub-pos=80.0", "sub-scale=1.25");
  }
}
