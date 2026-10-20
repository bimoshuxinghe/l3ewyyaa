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

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class MpvNativeSessionRegistryTest {

  private Object owner;
  private Object nextOwner;
  private Object thirdOwner;

  @Before
  public void setUp() {
    owner = new Object();
    nextOwner = new Object();
    thirdOwner = new Object();
  }

  @After
  public void tearDown() {
    MpvNativeSessionRegistry.release(owner);
    MpvNativeSessionRegistry.release(nextOwner);
    MpvNativeSessionRegistry.release(thirdOwner);
  }

  @Test
  public void acquire_activeSession_returnsUnavailable() {
    assertThat(MpvNativeSessionRegistry.acquire(owner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);

    assertThat(MpvNativeSessionRegistry.acquire(nextOwner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.UNAVAILABLE);
  }

  @Test
  public void acquire_releasingSession_handsOffAfterRelease() {
    AtomicInteger availableCalls = new AtomicInteger();
    assertThat(MpvNativeSessionRegistry.acquire(owner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
    MpvNativeSessionRegistry.beginRelease(owner);

    assertThat(
            MpvNativeSessionRegistry.acquire(nextOwner, availableCalls::incrementAndGet))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.PENDING);
    assertThat(availableCalls.get()).isEqualTo(0);

    MpvNativeSessionRegistry.release(owner);

    assertThat(availableCalls.get()).isEqualTo(1);
    assertThat(MpvNativeSessionRegistry.acquire(nextOwner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
  }

  @Test
  public void release_pendingSession_reservesSessionUntilAcquire() {
    assertThat(MpvNativeSessionRegistry.acquire(owner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
    MpvNativeSessionRegistry.beginRelease(owner);
    assertThat(MpvNativeSessionRegistry.acquire(nextOwner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.PENDING);

    MpvNativeSessionRegistry.release(owner);

    assertThat(MpvNativeSessionRegistry.acquire(thirdOwner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.UNAVAILABLE);
    assertThat(MpvNativeSessionRegistry.acquire(nextOwner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
  }

  @Test
  public void cancelPendingAcquire_reservedSession_releasesReservation() {
    assertThat(MpvNativeSessionRegistry.acquire(owner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
    MpvNativeSessionRegistry.beginRelease(owner);
    assertThat(MpvNativeSessionRegistry.acquire(nextOwner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.PENDING);
    MpvNativeSessionRegistry.release(owner);

    MpvNativeSessionRegistry.cancelPendingAcquire(nextOwner);

    assertThat(MpvNativeSessionRegistry.acquire(thirdOwner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
  }

  @Test
  public void cancelPendingAcquire_preventsHandoff() {
    AtomicInteger availableCalls = new AtomicInteger();
    assertThat(MpvNativeSessionRegistry.acquire(owner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
    MpvNativeSessionRegistry.beginRelease(owner);
    assertThat(
            MpvNativeSessionRegistry.acquire(nextOwner, availableCalls::incrementAndGet))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.PENDING);

    MpvNativeSessionRegistry.cancelPendingAcquire(nextOwner);
    MpvNativeSessionRegistry.release(owner);

    assertThat(availableCalls.get()).isEqualTo(0);
    assertThat(MpvNativeSessionRegistry.acquire(nextOwner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
  }

  @Test
  public void abortRelease_retriesPendingAcquireWithoutHandoff() {
    AtomicInteger availableCalls = new AtomicInteger();
    assertThat(MpvNativeSessionRegistry.acquire(owner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.ACQUIRED);
    MpvNativeSessionRegistry.beginRelease(owner);
    assertThat(
            MpvNativeSessionRegistry.acquire(nextOwner, availableCalls::incrementAndGet))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.PENDING);

    MpvNativeSessionRegistry.abortRelease(owner);

    assertThat(availableCalls.get()).isEqualTo(1);
    assertThat(MpvNativeSessionRegistry.acquire(nextOwner, () -> {}))
        .isEqualTo(MpvNativeSessionRegistry.AcquireResult.UNAVAILABLE);
  }
}
