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
 *
 */
package androidx.media3.ui.danmaku;

import android.net.Uri;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import okhttp3.OkHttpClient;

/**
 * A controller that manages danmaku playback for a {@link PlayerView}.
 *
 * <p>This class wraps {@link DanmakuController} and provides a convenient API for integrating
 * danmaku into video playback.
 */
public final class DanmakuPlayerViewController {

  private final DanmakuController controller;
  @Nullable private PlayerView playerView;

  public DanmakuPlayerViewController() {
    controller = new DanmakuController();
  }

  /**
   * Binds this controller to a {@link PlayerView}.
   *
   * <p>This must be called before the danmaku source can be set. The controller will automatically
   * track the player's position and synchronize danmaku display.
   */
  public void bind(PlayerView playerView) {
    if (this.playerView == playerView) {
      return;
    }
    detach();
    this.playerView = playerView;
    controller.setView(playerView.getDanmakuView());
  }

  /**
   * Sets the {@link OkHttpClient} used to fetch HTTP/HTTPS danmaku sources.
   *
   * <p>Must be called before loading any danmaku source.
   */
  public void setOkHttpClient(@Nullable OkHttpClient client) {
    controller.setOkHttpClient(client);
  }

  /**
   * Sets the danmaku rendering configuration.
   */
  public void setConfig(DanmakuConfig config) {
    controller.setConfig(config);
  }

  /**
   * Sets whether danmaku rendering is enabled.
   */
  public void setEnabled(boolean enabled) {
    controller.setEnabled(enabled);
  }

  /**
   * Sets the danmaku source URI.
   *
   * <p>Call this method to load danmaku from a remote or local source. Pass {@code null} to clear
   * the current danmaku items.
   */
  public void setDataSource(@Nullable Uri uri) {
    controller.setDataSource(uri);
  }

  /**
   * Sends a danmaku item at the current playback position.
   */
  public void sendNow(String text) {
    controller.sendNow(text);
  }

  /**
   * Releases all resources held by this controller.
   *
   * <p>Must be called when the controller is no longer needed to avoid memory leaks.
   */
  public void close() {
    detach();
    controller.release();
  }

  DanmakuController getInternalController() {
    return controller;
  }

  private void detach() {
    if (playerView != null) {
      controller.setView(null);
      playerView = null;
    }
  }
}
