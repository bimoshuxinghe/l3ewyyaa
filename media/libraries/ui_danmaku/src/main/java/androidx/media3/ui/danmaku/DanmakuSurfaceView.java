/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.ui.danmaku;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.os.SystemClock;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * 把弹幕绘制搬到独立 Surface 的独立渲染线程上执行。
 *
 * <p>默认接入方式是把 {@link DanmakuView} 直接挂到播放器 overlay 上，由主 UI 线程在
 * {@code onDraw} 里逐帧绘制。主 UI 线程同时负责遥控器按键分发，机顶盒播放 4K 时它本来就
 * 被解码抢占，弹幕再每帧贴上去，按键响应就会被拖长到「遥控器失灵」的程度。
 *
 * <p>本类把弹幕绘制彻底挪出主 UI 线程：
 * <ul>
 *   <li>绘制在独立线程上直接渲染到 {@link SurfaceHolder} 的 canvas，不参与 View 树 traversal；
 *   <li>Surface 以 {@code ZOrderMediaOverlay} 叠在视频之上，由 HWComposer 合成，几乎不占 CPU；
 *   <li>没有弹幕时停止绘制，不空占 CPU。
 * </ul>
 *
 * <p>弹幕的布局与绘制逻辑完全复用 {@link DanmakuView}，只是不再由系统 View 绘制流程驱动。
 */
public final class DanmakuSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

  /** 默认渲染帧率：弹幕是缓慢滚动的文字，30fps 与 60fps 肉眼几乎无差别。 */
  private static final int DEFAULT_FPS = 30;
  /** 空闲（无弹幕）时的轮询间隔，用于及时唤醒并绘制新弹幕。 */
  private static final long IDLE_INTERVAL_MS = 200L;
  /** 暂停时的轮询间隔。 */
  private static final long PAUSED_INTERVAL_MS = 500L;

  private final DanmakuView danmakuView;

  @Nullable private RenderThread renderThread;
  private volatile long intervalMs;
  private volatile boolean pendingClear;
  private volatile boolean released;

  public DanmakuSurfaceView(Context context) {
    super(context);
    danmakuView = new DanmakuView(context);
    intervalMs = 1000L / DEFAULT_FPS;
    getHolder().addCallback(this);
    getHolder().setFormat(PixelFormat.TRANSLUCENT);
    // 叠在视频 SurfaceView 之上，由硬件合成器直接叠加。
    setZOrderMediaOverlay(true);
  }

  /** 供 {@link DanmakuController} 绑定的实际弹幕视图。 */
  public DanmakuView getDanmakuView() {
    return danmakuView;
  }

  /** 设置弹幕渲染帧率，0 表示不限帧。 */
  public void setRenderFps(int fps) {
    intervalMs = fps <= 0 ? 0L : Math.max(8L, 1000L / fps);
  }

  /** 释放资源并停止渲染线程。 */
  public void release() {
    released = true;
    stopRenderThread();
    getHolder().removeCallback(this);
    danmakuView.release();
  }

  @Override
  public void surfaceCreated(@NonNull SurfaceHolder holder) {
    startRenderThreadIfNeeded();
  }

  @Override
  public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
    danmakuView.setOffscreenSize(width, height);
    startRenderThreadIfNeeded();
  }

  @Override
  public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
    stopRenderThread();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    // attach 时 Surface 可能早已就绪（例如全屏切换复用同一实例）。
    if (getHolder().getSurface() != null && getHolder().getSurface().isValid()) {
      startRenderThreadIfNeeded();
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    stopRenderThread();
    super.onDetachedFromWindow();
  }

  private void startRenderThreadIfNeeded() {
    if (released || renderThread != null) {
      return;
    }
    RenderThread thread = new RenderThread();
    renderThread = thread;
    thread.start();
  }

  private void stopRenderThread() {
    RenderThread thread = renderThread;
    renderThread = null;
    if (thread == null) {
      return;
    }
    thread.interrupt();
    try {
      thread.join(200L);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 绘制一帧，返回下次绘制前应等待的毫秒数。 */
  private long renderOnce() {
    if (danmakuView.isPaused()) {
      return PAUSED_INTERVAL_MS;
    }
    int activeBefore = danmakuView.getActiveCount();
    if (activeBefore <= 0 && !pendingClear) {
      return IDLE_INTERVAL_MS;
    }
    SurfaceHolder holder = getHolder();
    @Nullable Canvas canvas = null;
    int active = activeBefore;
    try {
      canvas = holder.lockCanvas();
      if (canvas == null) {
        return IDLE_INTERVAL_MS;
      }
      // Surface 的 buffer 会保留上一帧，必须先清空，否则弹幕会拖出残影。
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      active = danmakuView.renderToCanvas(canvas);
    } catch (Throwable e) {
      return IDLE_INTERVAL_MS;
    } finally {
      if (canvas != null) {
        try {
          holder.unlockCanvasAndPost(canvas);
        } catch (Throwable e) {
          // Surface 已失效，忽略。
        }
      }
    }
    // 弹幕全部消失后再补一帧空绘制，把残留的最后一帧清掉。
    pendingClear = active > 0;
    return active > 0 ? intervalMs : 0L;
  }

  private final class RenderThread extends Thread {

    private RenderThread() {
      super("DanmakuRender");
    }

    @Override
    public void run() {
      while (!isInterrupted()) {
        long frameStartMs = SystemClock.uptimeMillis();
        long sleepMs = renderOnce();
        long remainMs = sleepMs - (SystemClock.uptimeMillis() - frameStartMs);
        if (remainMs > 0) {
          try {
            Thread.sleep(remainMs);
          } catch (InterruptedException e) {
            interrupt();
            return;
          }
        }
      }
    }
  }
}
