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

import is.xyz.mpv.MPVLib;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class MpvClientTest {

  @Test
  public void commands_areSubmittedToNativeQueueInCallOrder() {
    FakeNativeCommandQueue nativeApi = new FakeNativeCommandQueue();
    MpvClient client = new MpvClient(nativeApi);
    String[] first = {"stop"};
    String[] second = {"loadfile", "https://example.test/video.m3u8", "replace"};

    assertThat(client.command(first)).isTrue();
    assertThat(client.command(second)).isTrue();

    assertThat(nativeApi.commands).hasSize(2);
    assertThat(nativeApi.commands.get(0)).isSameInstanceAs(first);
    assertThat(nativeApi.commands.get(1)).isSameInstanceAs(second);
    assertThat(nativeApi.requestIds.get(1)).isGreaterThan(nativeApi.requestIds.get(0));
  }

  @Test
  public void property_isEncodedAsCommandForNativeQueue() {
    FakeNativeCommandQueue nativeApi = new FakeNativeCommandQueue();
    MpvClient client = new MpvClient(nativeApi);

    assertThat(client.command(new String[] {"loadfile", "test.mp4", "replace"})).isTrue();
    client.setPropertyInt("chapter", 0);

    assertThat(nativeApi.operations)
        .containsExactly("command:loadfile", "property:chapter")
        .inOrder();
    assertThat(nativeApi.propertyValues).containsExactly("0");
    assertThat(nativeApi.requestIds.get(1)).isGreaterThan(nativeApi.requestIds.get(0));
  }

  @Test
  public void commandReply_withUnknownRequestIdIsIgnored() {
    FakeNativeCommandQueue nativeApi = new FakeNativeCommandQueue();
    MpvClient client = new MpvClient(nativeApi);
    AtomicBoolean callbackInvoked = new AtomicBoolean();

    assertThat(client.setPropertyDouble("time-pos", 12.5, result -> callbackInvoked.set(true)))
        .isTrue();

    client.onCommandReply(nativeApi.requestIds.get(0) + 1, /* error= */ 0);

    assertThat(callbackInvoked.get()).isFalse();

    client.onCommandReply(nativeApi.requestIds.get(0), /* error= */ 0);

    assertThat(callbackInvoked.get()).isTrue();
  }

  @Test
  public void enqueueFailure_isReportedWithoutLeavingPendingCallback() {
    FakeNativeCommandQueue nativeApi = new FakeNativeCommandQueue();
    nativeApi.result = MPVLib.MpvError.MPV_ERROR_UNINITIALIZED;
    MpvClient client = new MpvClient(nativeApi);
    AtomicBoolean callbackInvoked = new AtomicBoolean();

    assertThat(client.setPropertyDouble("time-pos", 12.5, result -> callbackInvoked.set(true)))
        .isFalse();
    assertThat(client.getLastError()).isEqualTo(MPVLib.MpvError.MPV_ERROR_UNINITIALIZED);

    client.onCommandReply(nativeApi.requestIds.get(0), MPVLib.MpvError.MPV_ERROR_SUCCESS);

    assertThat(callbackInvoked.get()).isFalse();
  }

  @Test
  public void commandReply_reportsPropertyFailure() {
    FakeNativeCommandQueue nativeApi = new FakeNativeCommandQueue();
    MpvClient client = new MpvClient(nativeApi);
    AtomicBoolean success = new AtomicBoolean(true);

    assertThat(client.setPropertyDouble("time-pos", 12.5, success::set)).isTrue();
    assertThat(client.command(new String[] {"stop"})).isTrue();

    client.onCommandReply(nativeApi.requestIds.get(0), MPVLib.MpvError.MPV_ERROR_COMMAND);

    assertThat(success.get()).isFalse();
    assertThat(client.getLastError()).isEqualTo(MPVLib.MpvError.MPV_ERROR_COMMAND);
    assertThat(nativeApi.operations).containsExactly("property:time-pos", "command:stop").inOrder();
    assertThat(nativeApi.propertyValues).containsExactly("12.5");
  }

  @Test
  public void commandReplyFailure_doesNotBlockLaterRequest() {
    FakeNativeCommandQueue nativeApi = new FakeNativeCommandQueue();
    MpvClient client = new MpvClient(nativeApi);

    assertThat(client.command(new String[] {"stop"})).isTrue();
    client.setPropertyInt("chapter", 0);

    client.onCommandReply(nativeApi.requestIds.get(0), MPVLib.MpvError.MPV_ERROR_COMMAND);

    assertThat(nativeApi.commands).hasSize(2);
    assertThat(nativeApi.operations).containsExactly("command:stop", "property:chapter").inOrder();
    assertThat(client.getLastError()).isEqualTo(MPVLib.MpvError.MPV_ERROR_COMMAND);

    client.onCommandReply(nativeApi.requestIds.get(1), MPVLib.MpvError.MPV_ERROR_SUCCESS);

    assertThat(client.getLastError()).isEqualTo(MPVLib.MpvError.MPV_ERROR_SUCCESS);
  }

  @Test
  public void commandReplyFailure_doesNotDropLaterSeekCallback() {
    FakeNativeCommandQueue nativeApi = new FakeNativeCommandQueue();
    MpvClient client = new MpvClient(nativeApi);
    AtomicBoolean callbackInvoked = new AtomicBoolean();
    AtomicBoolean callbackResult = new AtomicBoolean();

    assertThat(client.command(new String[] {"stop"})).isTrue();
    assertThat(
            client.setPropertyDouble(
                "time-pos",
                12.5,
                success -> {
                  callbackInvoked.set(true);
                  callbackResult.set(success);
                }))
        .isTrue();

    client.onCommandReply(nativeApi.requestIds.get(0), MPVLib.MpvError.MPV_ERROR_COMMAND);

    assertThat(nativeApi.operations).containsExactly("command:stop", "property:time-pos").inOrder();
    assertThat(callbackInvoked.get()).isFalse();

    client.onCommandReply(nativeApi.requestIds.get(1), MPVLib.MpvError.MPV_ERROR_SUCCESS);

    assertThat(callbackInvoked.get()).isTrue();
    assertThat(callbackResult.get()).isTrue();
  }

  private static final class FakeNativeCommandQueue implements MpvClient.NativeCommandQueue {

    private final List<Long> requestIds = new ArrayList<>();
    private final List<String[]> commands = new ArrayList<>();
    private final List<String> operations = new ArrayList<>();
    private final List<String> propertyValues = new ArrayList<>();
    private int result;

    @Override
    public int enqueue(long requestId, String[] command) {
      requestIds.add(requestId);
      commands.add(command);
      if (command.length == 3 && command[0].equals("set")) {
        operations.add("property:" + command[1]);
        propertyValues.add(command[2]);
      } else {
        operations.add("command:" + command[0]);
      }
      return result;
    }
  }
}
