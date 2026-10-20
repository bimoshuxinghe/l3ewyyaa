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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import androidx.media3.common.PlaybackException;
import androidx.media3.mpvplayer.MpvLibrary;
import is.xyz.mpv.MPVLib;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

public final class MpvNativeLifecycleTest {

  private Object owner;
  private Object nextOwner;

  @Before
  public void setUp() {
    owner = new Object();
    nextOwner = new Object();
  }

  @After
  public void tearDown() {
    MpvNativeSessionRegistry.release(owner);
    MpvNativeSessionRegistry.release(nextOwner);
  }

  @Test
  public void release_handsOffSessionAfterNativeShutdown() {
    FakeNativeClient client = new FakeNativeClient();
    FakeHost host = new FakeHost();
    MpvNativeState state = new MpvNativeState();
    state.setCreated(true);
    state.setInitialized(true);
    MpvNativeLifecycle lifecycle = createLifecycle(client, state, host);
    AtomicInteger availableCalls = new AtomicInteger();
    assertThat(MpvNativeSessionRegistry.acquire(owner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);

    lifecycle.release();

    assertThat(
            MpvNativeSessionRegistry.acquire(nextOwner, availableCalls::incrementAndGet))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.PENDING);
    assertThat(client.destroyCalls).isEqualTo(1);
    assertThat(host.nativeSessionEnded).isFalse();
    assertThat(availableCalls.get()).isEqualTo(0);

    lifecycle.onShutdown();

    assertThat(client.clearPendingRequestsCalls).isEqualTo(1);
    assertThat(client.removeObserverCalls).isEqualTo(1);
    assertThat(host.nativeSessionEnded).isTrue();
    assertThat(state.isCreated()).isFalse();
    assertThat(state.isInitialized()).isFalse();
    assertThat(availableCalls.get()).isEqualTo(1);
    assertThat(MpvNativeSessionRegistry.acquire(nextOwner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
  }

  @Test
  public void ensureInitialized_releasingSession_retriesAfterHandoff() {
    FakeNativeClient ownerClient = new FakeNativeClient();
    FakeHost ownerHost = new FakeHost();
    MpvNativeState ownerState = new MpvNativeState();
    ownerState.setCreated(true);
    ownerState.setInitialized(true);
    MpvNativeLifecycle ownerLifecycle =
        createLifecycle(owner, ownerClient, ownerState, ownerHost);
    FakeNativeClient nextClient = new FakeNativeClient();
    FakeHost nextHost = new FakeHost();
    MpvNativeLifecycle nextLifecycle =
        createLifecycle(nextOwner, nextClient, new MpvNativeState(), nextHost);
    assertThat(MpvNativeSessionRegistry.acquire(owner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);

    try (MockedStatic<MpvLibrary> library = Mockito.mockStatic(MpvLibrary.class)) {
      library.when(MpvLibrary::isAvailable).thenReturn(true);
      ownerLifecycle.release();

      assertThat(nextLifecycle.ensureInitialized()).isFalse();
      assertThat(nextHost.initializationFailureCalls).isEqualTo(0);
      assertThat(nextClient.createCalls).isEqualTo(0);

      ownerLifecycle.onShutdown();

      assertThat(nextHost.nativeSessionAvailableCalls).isEqualTo(1);
      assertThat(nextLifecycle.ensureInitialized()).isTrue();
      assertThat(nextClient.createCalls).isEqualTo(1);
      assertThat(nextClient.initCalls).isEqualTo(1);
    }
  }

  @Test
  public void cancelPendingInitialization_preventsRetryAfterHandoff() {
    FakeNativeClient ownerClient = new FakeNativeClient();
    MpvNativeState ownerState = new MpvNativeState();
    ownerState.setCreated(true);
    ownerState.setInitialized(true);
    MpvNativeLifecycle ownerLifecycle =
        createLifecycle(owner, ownerClient, ownerState, new FakeHost());
    FakeHost nextHost = new FakeHost();
    MpvNativeLifecycle nextLifecycle =
        createLifecycle(nextOwner, new FakeNativeClient(), new MpvNativeState(), nextHost);
    assertThat(MpvNativeSessionRegistry.acquire(owner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);

    try (MockedStatic<MpvLibrary> library = Mockito.mockStatic(MpvLibrary.class)) {
      library.when(MpvLibrary::isAvailable).thenReturn(true);
      ownerLifecycle.release();
      assertThat(nextLifecycle.ensureInitialized()).isFalse();

      nextLifecycle.cancelPendingInitialization();
      ownerLifecycle.onShutdown();

      assertThat(nextHost.nativeSessionAvailableCalls).isEqualTo(0);
      assertThat(MpvNativeSessionRegistry.acquire(nextOwner, () -> {}))
          .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
    }
  }

  private MpvNativeLifecycle createLifecycle(
      FakeNativeClient client, MpvNativeState state, FakeHost host) {
    return createLifecycle(owner, client, state, host);
  }

  private static MpvNativeLifecycle createLifecycle(
      Object lifecycleOwner, FakeNativeClient client, MpvNativeState state, FakeHost host) {
    MpvClient errorClient = mock(MpvClient.class);
    MpvEventAdapter eventAdapter = mock(MpvEventAdapter.class);
    when(eventAdapter.observeProperties(client)).thenReturn(true);
    return new MpvNativeLifecycle(
        mock(Context.class),
        lifecycleOwner,
        client,
        state,
        eventAdapter,
        new MpvPlaybackErrorFactory(errorClient),
        host);
  }

  private static final class FakeNativeClient implements MpvNativeClient {

    private int createCalls;
    private int clearPendingRequestsCalls;
    private int destroyCalls;
    private int initCalls;
    private int removeObserverCalls;

    @Override
    public void clearLastResult() {}

    @Override
    public void clearPendingRequests() {
      clearPendingRequestsCalls++;
    }

    @Override
    public boolean create(Context context) {
      createCalls++;
      return true;
    }

    @Override
    public boolean init() {
      initCalls++;
      return true;
    }

    @Override
    public boolean destroy() {
      destroyCalls++;
      return true;
    }

    @Override
    public void addObserver(MPVLib.EventObserver observer) {}

    @Override
    public void removeObserver(MPVLib.EventObserver observer) {
      removeObserverCalls++;
    }

    @Override
    public void addLogObserver() {}

    @Override
    public void removeLogObserver() {}

    @Override
    public boolean observeProperty(String property, int format) {
      return true;
    }

    @Override
    public boolean hasLastFailure() {
      return false;
    }
  }

  private static final class FakeHost implements MpvNativeLifecycle.Host {

    private int initializationFailureCalls;
    private int nativeSessionAvailableCalls;
    private boolean nativeSessionEnded;

    @Override
    public void applyPreInitOptions() {}

    @Override
    public void onInitialized() {}

    @Override
    public void onInitializationFailed(PlaybackException error) {
      initializationFailureCalls++;
    }

    @Override
    public void releaseAudioFocus() {}

    @Override
    public void onNativeSessionEnded() {
      nativeSessionEnded = true;
    }

    @Override
    public void onNativeReleaseFailed(PlaybackException error) {}

    @Override
    public void onShutdown() {}

    @Override
    public void runOnPlayerLooperAfterRelease(Runnable runnable) {
      runnable.run();
    }

    @Override
    public void onNativeSessionAvailable() {
      nativeSessionAvailableCalls++;
    }
  }
}
