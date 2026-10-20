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
package androidx.media3.common;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

import android.os.Bundle;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import java.util.Objects;

/** Represents a seekable chapter in the current media item. */
@UnstableApi
public final class MediaChapter {

  /** Zero-based index used by the player to select this entry. */
  public final int index;

  /** Chapter start time in microseconds, or {@link C#TIME_UNSET} if unknown. */
  public final long timeUs;

  /** Human-readable label, e.g. {@code "Chapter 1"}. */
  public final String label;

  /** Whether this entry is currently selected. */
  public final boolean selected;

  private MediaChapter(int index, long timeUs, String label, boolean selected) {
    checkArgument(index >= 0);
    checkArgument(timeUs == C.TIME_UNSET || timeUs >= 0);
    this.index = index;
    this.timeUs = timeUs;
    this.label = checkNotNull(label);
    this.selected = selected;
  }

  /** Creates a seekable chapter entry. */
  public static MediaChapter chapter(int index, long timeUs, String label, boolean selected) {
    return new MediaChapter(index, timeUs, label, selected);
  }

  /** Returns a copy of this entry with {@link #selected} set to the given value. */
  public MediaChapter withSelected(boolean selected) {
    if (this.selected == selected) {
      return this;
    }
    return new MediaChapter(index, timeUs, label, selected);
  }

  /** Returns a {@link Bundle} representing this chapter. */
  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    bundle.putInt(FIELD_INDEX, index);
    bundle.putLong(FIELD_TIME_US, timeUs);
    bundle.putString(FIELD_LABEL, label);
    bundle.putBoolean(FIELD_SELECTED, selected);
    return bundle;
  }

  /** Restores a {@code MediaChapter} from a {@link Bundle}. */
  public static MediaChapter fromBundle(Bundle bundle) {
    return new MediaChapter(
        bundle.getInt(FIELD_INDEX, /* defaultValue= */ 0),
        bundle.getLong(FIELD_TIME_US, C.TIME_UNSET),
        bundle.getString(FIELD_LABEL, ""),
        bundle.getBoolean(FIELD_SELECTED, /* defaultValue= */ false));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MediaChapter)) {
      return false;
    }
    MediaChapter other = (MediaChapter) o;
    return index == other.index
        && timeUs == other.timeUs
        && selected == other.selected
        && Objects.equals(label, other.label);
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, timeUs, label, selected);
  }

  private static final String FIELD_INDEX = Util.intToStringMaxRadix(0);
  private static final String FIELD_TIME_US = Util.intToStringMaxRadix(1);
  private static final String FIELD_LABEL = Util.intToStringMaxRadix(2);
  private static final String FIELD_SELECTED = Util.intToStringMaxRadix(3);
}
