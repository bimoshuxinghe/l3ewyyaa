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
package androidx.media3.mpvplayer.video;

import static androidx.media3.mpvplayer.nativebridge.MpvConstants.OPT_FORCE_WINDOW;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.PROP_ANDROID_OSD_SURFACE_SIZE;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.PROP_ANDROID_SURFACE_SIZE;
import static androidx.media3.mpvplayer.nativebridge.MpvConstants.VALUE_YES;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.Surface;
import androidx.media3.common.util.Size;
import androidx.media3.mpvplayer.nativebridge.MpvClient;
import org.junit.Test;
import org.mockito.InOrder;

public final class MpvNativeSurfaceTest {

  @Test
  public void setSurface_withKnownSize_queuesSurfaceUpdates() {
    MpvClient client = mock(MpvClient.class);
    MpvSurfaceController.Host host = mock(MpvSurfaceController.Host.class);
    Surface surface = mock(Surface.class);
    when(host.isInitialized()).thenReturn(true);
    when(surface.isValid()).thenReturn(true);
    when(client.attachSurface(surface)).thenReturn(true);
    MpvNativeSurface nativeSurface = new MpvNativeSurface(client, host);
    nativeSurface.setSurfaceSize(new Size(1920, 1080));
    clearInvocations(client);

    MpvNativeSurface.SurfaceChange change = nativeSurface.setSurface(surface);

    assertThat(change.changed()).isTrue();
    InOrder order = inOrder(client);
    order.verify(client).attachSurface(surface);
    order.verify(client).setPropertyString(PROP_ANDROID_SURFACE_SIZE, "1920x1080");
    order.verify(client).setPropertyString(OPT_FORCE_WINDOW, VALUE_YES);
  }

  @Test
  public void setOsdSurfaceSize_queuesPropertyUpdate() {
    MpvClient client = mock(MpvClient.class);
    MpvSurfaceController.Host host = mock(MpvSurfaceController.Host.class);
    when(host.isInitialized()).thenReturn(true);
    MpvNativeOsdSurface nativeSurface = new MpvNativeOsdSurface(client, host);

    nativeSurface.setSurfaceSize(new Size(1920, 1080));

    verify(client).setPropertyString(PROP_ANDROID_OSD_SURFACE_SIZE, "1920x1080");
  }
}
