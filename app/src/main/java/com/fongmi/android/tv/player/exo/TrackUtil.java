package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Format;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.PlayerHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TrackUtil {

    public static int count(Tracks tracks, int type) {
        return tracks.getGroups().stream().filter(trackGroup -> trackGroup.getType() == type).mapToInt(trackGroup -> trackGroup.length).sum();
    }

    public static void reset(Player player) {
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().clearOverrides().build());
    }

    /**
     * 音轨的描述可能完全相同（网盘多音轨常见：两条 AAC/AC3 参数一致），
     * 保存时会带上 ",##序号" 后缀（独立 CSV 段，不影响 MPV 引擎取首段 id），
     * 表示同一类型里的第 N 条同名轨道；这里按同样的遍历顺序数出现次数，
     * 保证能精确选中第 2、3 条音轨。旧数据没有后缀时默认匹配第 0 条。
     */
    private static TrackInfo find(Player player, Track track) {
        if (track.getFormat() == null) return null;
        String base = track.getFormat();
        int want = 0;
        int idx = base.lastIndexOf(",##");
        if (idx >= 0) {
            try {
                want = Integer.parseInt(base.substring(idx + 3));
                base = base.substring(0, idx);
            } catch (NumberFormatException ignore) {
                want = 0;
            }
        }
        Tracks currentTracks = player.getCurrentTracks();
        Map<String, Integer> seen = new HashMap<>();
        for (Tracks.Group trackGroup : currentTracks.getGroups()) {
            if (trackGroup.getType() != track.getType()) continue;
            for (int i = 0; i < trackGroup.length; i++) {
                Format format = trackGroup.getTrackFormat(i);
                String desc = PlayerHelper.describeFormat(format);
                if (!base.equals(desc)) continue;
                int occurrence = seen.merge(desc, 1, Integer::sum) - 1;
                if (occurrence == want) return new TrackInfo(trackGroup, i);
            }
        }
        return null;
    }

    public static void setTrackSelection(Player player, List<Track> tracks) {
        // 1. 按所在 TrackGroup 分别下发 override：多条音轨分属不同 TrackGroup 时，
        //    旧的按 type 覆盖会互相覆盖导致选错甚至选中空轨
        // 2. 只对用户真正选中的轨道下发 override，不再给“未选中”轨道下发空 override，
        //    避免整条音轨被置空导致无声
        // 3. 下发前先清掉同类型的旧 override，防止多次切换后残留的 override 冲突
        Map<Tracks.Group, Integer> overrideMap = new LinkedHashMap<>();
        Set<Integer> types = new HashSet<>();
        for (Track track : tracks) {
            if (!track.isSelected()) continue;
            TrackInfo info = find(player, track);
            if (info == null) continue;
            types.add(info.trackGroup.getType());
            overrideMap.put(info.trackGroup, info.trackIndex);
        }
        if (overrideMap.isEmpty()) return;
        TrackSelectionParameters.Builder builder = player.getTrackSelectionParameters().buildUpon();
        for (int type : types) builder.clearOverridesOfType(type);
        overrideMap.forEach((group, selectedIndex) -> builder.setOverrideForType(new TrackSelectionOverride(group.getMediaTrackGroup(), List.of(selectedIndex))));
        player.setTrackSelectionParameters(builder.build());
    }

    private record TrackInfo(Tracks.Group trackGroup, int trackIndex) {
    }
}
