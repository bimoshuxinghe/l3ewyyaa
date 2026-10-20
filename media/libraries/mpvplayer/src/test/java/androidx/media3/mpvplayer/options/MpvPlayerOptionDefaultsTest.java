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

import androidx.media3.common.DolbyVisionOutputPolicy;
import androidx.media3.mpvplayer.MpvAndroidOptions;
import androidx.media3.mpvplayer.MpvPlayerConfig;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class MpvPlayerOptionDefaultsTest {

  @Test
  public void unsetGpuOptions_leaveApplicationOverridesEmpty() {
    MpvAndroidOptions androidOptions =
        new MpvAndroidOptions.Builder()
            .setAudioPassthroughEnabled(false)
            .setDolbyVisionOutputPolicy(DolbyVisionOutputPolicy.AUTO)
            .build();
    MpvPlayerConfig config =
        new MpvPlayerConfig.Builder().addAndroidDefaults(androidOptions).build();
    List<String> applied = new ArrayList<>();

    config.applyAppOwned((name, value) -> applied.add(name + "=" + value));

    assertThat(applied).isEmpty();
    assertThat(config.getRequestedPassthroughCodecs()).isEmpty();
    assertThat(config.isDolbyVisionOutputPolicySet()).isTrue();
  }

  @Test
  public void enabledGpuOptions_applyApplicationOverrides() {
    MpvAndroidOptions androidOptions =
        new MpvAndroidOptions.Builder()
            .setGpuNextEnabled(true)
            .setVulkanEnabled(true)
            .build();
    MpvPlayerConfig config =
        new MpvPlayerConfig.Builder().addAndroidDefaults(androidOptions).build();
    List<String> applied = new ArrayList<>();

    config.applyAppOwned((name, value) -> applied.add(name + "=" + value));

    assertThat(applied)
        .containsExactly("vo=gpu-next", "gpu-api=vulkan", "gpu-context=androidvk")
        .inOrder();
  }
}
