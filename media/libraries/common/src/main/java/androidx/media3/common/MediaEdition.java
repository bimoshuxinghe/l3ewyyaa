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

/** Represents a selectable edition in the current media item. */
@UnstableApi
public final class MediaEdition {

  /** Zero-based index used by the player to select this entry. */
  public final int index;

  /** Entry duration in microseconds, or {@link C#TIME_UNSET} if unknown. */
  public final long durationUs;

  /** Human-readable label, e.g. {@code "Title 1"}. */
  public final String label;

  /** Whether this entry is currently selected. */
  public final boolean selected;

  private MediaEdition(int index, long durationUs, String label, boolean selected) {
    checkArgument(index >= 0);
    checkArgument(durationUs == C.TIME_UNSET || durationUs >= 0);
    this.index = index;
    this.durationUs = durationUs;
    this.label = checkNotNull(label);
    this.selected = selected;
  }

  /** Creates a selectable edition entry. */
  public static MediaEdition edition(int index, long durationUs, String label, boolean selected) {
    return new MediaEdition(index, durationUs, label, selected);
  }

  /** Returns a copy of this entry with {@link #selected} set to the given value. */
  public MediaEdition withSelected(boolean selected) {
    if (this.selected == selected) {
      return this;
    }
    return new MediaEdition(index, durationUs, label, selected);
  }

  /** Returns a {@link Bundle} representing this edition. */
  public Bundle toBundle() {
    Bundle bundle = new Bundle();
    bundle.putInt(FIELD_INDEX, index);
    bundle.putLong(FIELD_DURATION_US, durationUs);
    bundle.putString(FIELD_LABEL, label);
    bundle.putBoolean(FIELD_SELECTED, selected);
    return bundle;
  }

  /** Restores a {@code MediaEdition} from a {@link Bundle}. */
  public static MediaEdition fromBundle(Bundle bundle) {
    return new MediaEdition(
        bundle.getInt(FIELD_INDEX, /* defaultValue= */ 0),
        bundle.getLong(FIELD_DURATION_US, C.TIME_UNSET),
        bundle.getString(FIELD_LABEL, ""),
        bundle.getBoolean(FIELD_SELECTED, /* defaultValue= */ false));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MediaEdition)) {
      return false;
    }
    MediaEdition other = (MediaEdition) o;
    return index == other.index
        && durationUs == other.durationUs
        && selected == other.selected
        && Objects.equals(label, other.label);
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, durationUs, label, selected);
  }

  private static final String FIELD_INDEX = Util.intToStringMaxRadix(0);
  private static final String FIELD_DURATION_US = Util.intToStringMaxRadix(1);
  private static final String FIELD_LABEL = Util.intToStringMaxRadix(2);
  private static final String FIELD_SELECTED = Util.intToStringMaxRadix(3);
}
