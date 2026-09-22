package com.fongmi.android.tv.live.cctv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 央视频频道表——由 Python 版 {@code live_ysp.py} 的 {@code CHANNELS} / {@code CHANNEL_GROUPS} 移植。
 *
 * <p>每个频道三个关键参数：
 * <ul>
 *   <li>{@code cnlid} —— 内容 ID，用于生成 cKey（{@link CKeyManager#generateCkey}）</li>
 *   <li>{@code livepid} —— 直播节目 ID，随请求下发给取流接口</li>
 *   <li>{@code defn} —— 清晰度（{@code fhd} 高清 / {@code shd} 标清）</li>
 * </ul>
 *
 * <p>表内容逐字保留自 Python 版，改动会导致取流失败。
 */
public final class YspChannels {

    /** 一个央视频频道。 */
    public static final class Channel {
        public final String id;
        public final String name;
        public final String cnlid;
        public final String livepid;
        public final String defn;

        Channel(String id, String name, String cnlid, String livepid, String defn) {
            this.id = id;
            this.name = name;
            this.cnlid = cnlid;
            this.livepid = livepid;
            this.defn = defn;
        }
    }

    private static final Map<String, Channel> CHANNELS;
    private static final Map<String, List<String>> GROUPS;

    static {
        Map<String, Channel> channels = new LinkedHashMap<>();
        channels.put("cctv1", new Channel("cctv1", "CCTV1", "2024078201", "600001859", "fhd"));
        channels.put("cctv2", new Channel("cctv2", "CCTV2", "2024075401", "600001800", "fhd"));
        channels.put("cctv3", new Channel("cctv3", "CCTV3", "2024068501", "600001801", "fhd"));
        channels.put("cctv4", new Channel("cctv4", "CCTV4", "2029797101", "600001814", "fhd"));
        channels.put("cctv5", new Channel("cctv5", "CCTV5", "2024078401", "600001818", "fhd"));
        channels.put("cctv5p", new Channel("cctv5p", "CCTV5+", "2024078001", "600001817", "fhd"));
        channels.put("cctv6", new Channel("cctv6", "CCTV6", "2013693901", "600108442", "fhd"));
        channels.put("cctv7", new Channel("cctv7", "CCTV7", "2024072001", "600004092", "fhd"));
        channels.put("cctv8", new Channel("cctv8", "CCTV8", "2029793001", "600001803", "fhd"));
        channels.put("cctv9", new Channel("cctv9", "CCTV9", "2024078601", "600004078", "fhd"));
        channels.put("cctv10", new Channel("cctv10", "CCTV10", "2024078701", "600001805", "fhd"));
        channels.put("cctv11", new Channel("cctv11", "CCTV11", "2027248701", "600001806", "fhd"));
        channels.put("cctv12", new Channel("cctv12", "CCTV12", "2027248801", "600001807", "fhd"));
        channels.put("cctv13", new Channel("cctv13", "CCTV13", "2029797201", "600001811", "fhd"));
        channels.put("cctv14", new Channel("cctv14", "CCTV14", "2027248901", "600001809", "fhd"));
        channels.put("cctv15", new Channel("cctv15", "CCTV15", "2027249001", "600001815", "fhd"));
        channels.put("cctv16", new Channel("cctv16", "CCTV16", "2027249101", "600098637", "fhd"));
        channels.put("cctv164k", new Channel("cctv164k", "CCTV16(4K)", "2027249301", "600099502", "fhd"));
        channels.put("cctv17", new Channel("cctv17", "CCTV17", "2027249401", "600001810", "fhd"));
        channels.put("cctv4k", new Channel("cctv4k", "CCTV4K", "2029810301", "600002264", "fhd"));
        channels.put("cctv8k", new Channel("cctv8k", "CCTV8K", "2026774101", "600156816", "fhd"));
        channels.put("cgtn", new Channel("cgtn", "CGTN", "2024181701", "600014550", "fhd"));
        channels.put("cgtnfy", new Channel("cgtnfy", "CGTN法语频道", "2024181801", "600084704", "fhd"));
        channels.put("cgtney", new Channel("cgtney", "CGTN俄语频道", "2024181901", "600084758", "fhd"));
        channels.put("cgtnalby", new Channel("cgtnalby", "CGTN阿拉伯语频道", "2024182001", "600084782", "fhd"));
        channels.put("cgtnxby", new Channel("cgtnxby", "CGTN西班牙语频道", "2024182101", "600084744", "fhd"));
        channels.put("cgtnwyjl", new Channel("cgtnwyjl", "CGTN外语纪录频道", "2024182301", "600084781", "fhd"));
        channels.put("cctvfyjc", new Channel("cctvfyjc", "风云剧场", "2025637103", "600099658", "shd"));
        channels.put("cctvdyjc", new Channel("cctvdyjc", "第一剧场", "2026874203", "600099655", "shd"));
        channels.put("cctvhjjc", new Channel("cctvhjjc", "怀旧剧场", "2026874303", "600099620", "shd"));
        channels.put("cctvsjdl", new Channel("cctvsjdl", "世界地理", "2026874403", "600099637", "shd"));
        channels.put("cctvfyyy", new Channel("cctvfyyy", "风云音乐", "2026874503", "600099660", "shd"));
        channels.put("cctvbqkj", new Channel("cctvbqkj", "兵器科技", "2026874603", "600099649", "shd"));
        channels.put("cctvfyzq", new Channel("cctvfyzq", "风云足球", "2026966203", "600099636", "shd"));
        channels.put("cctvgeqwq", new Channel("cctvgeqwq", "高尔夫·网球", "2026874703", "600099659", "shd"));
        channels.put("cctvnxss", new Channel("cctvnxss", "女性时尚", "2026874803", "600099650", "shd"));
        channels.put("cctvyswhjp", new Channel("cctvyswhjp", "央视文化精品", "2026874903", "600099653", "shd"));
        channels.put("cctvystq", new Channel("cctvystq", "央视台球", "2026875003", "600099652", "shd"));
        channels.put("cctvdszn", new Channel("cctvdszn", "电视指南", "2026875103", "600099656", "shd"));
        channels.put("cctvwsjk", new Channel("cctvwsjk", "卫生健康", "2025637003", "600099651", "shd"));
        channels.put("bjws", new Channel("bjws", "北京卫视", "2024052703", "600002309", "fhd"));
        channels.put("jsws", new Channel("jsws", "江苏卫视", "2024171103", "600002521", "fhd"));
        channels.put("dfws", new Channel("dfws", "东方卫视", "2024054503", "600002483", "fhd"));
        channels.put("zjws", new Channel("zjws", "浙江卫视", "2024054703", "600002520", "fhd"));
        channels.put("hnws", new Channel("hnws", "湖南卫视", "2024054803", "600002475", "fhd"));
        channels.put("hbws", new Channel("hbws", "湖北卫视", "2024171203", "600002508", "fhd"));
        channels.put("gdws", new Channel("gdws", "广东卫视", "2024060903", "600002485", "fhd"));
        channels.put("gxws", new Channel("gxws", "广西卫视", "2024060703", "600002509", "fhd"));
        channels.put("hljws", new Channel("hljws", "黑龙江卫视", "2029797003", "600002498", "fhd"));
        channels.put("hnws2", new Channel("hnws2", "海南卫视", "2024055603", "600002506", "fhd"));
        channels.put("cqws", new Channel("cqws", "重庆卫视", "2024061103", "600002531", "fhd"));
        channels.put("szws", new Channel("szws", "深圳卫视", "2024061303", "600002481", "fhd"));
        channels.put("scws", new Channel("scws", "四川卫视", "2024061403", "600002516", "fhd"));
        channels.put("henanws", new Channel("henanws", "河南卫视", "2029797303", "600002525", "fhd"));
        channels.put("fjdnhz", new Channel("fjdnhz", "东南卫视", "2024061503", "600002484", "fhd"));
        channels.put("gzhws", new Channel("gzhws", "贵州卫视", "2024061603", "600002490", "fhd"));
        channels.put("jxws", new Channel("jxws", "江西卫视", "2024061703", "600002503", "fhd"));
        channels.put("lnws", new Channel("lnws", "辽宁卫视", "2024171303", "600002505", "fhd"));
        channels.put("ahws", new Channel("ahws", "安徽卫视", "2024171403", "600002532", "fhd"));
        channels.put("hbws2", new Channel("hbws2", "河北卫视", "2024171503", "600002493", "fhd"));
        channels.put("sdws", new Channel("sdws", "山东卫视", "2029787903", "600002513", "fhd"));
        channels.put("tjws", new Channel("tjws", "天津卫视", "2019927003", "600152137", "fhd"));
        channels.put("jlws", new Channel("jlws", "吉林卫视", "2025561503", "600190405", "fhd"));
        channels.put("shanxiws", new Channel("shanxiws", "陕西卫视", "2029795103", "600190400", "fhd"));
        channels.put("nxws", new Channel("nxws", "宁夏卫视", "2025608503", "600190737", "fhd"));
        channels.put("nmgws", new Channel("nmgws", "内蒙古卫视", "2025561203", "600190401", "fhd"));
        channels.put("ynws", new Channel("ynws", "云南卫视", "2025561303", "600190402", "fhd"));
        channels.put("shanxiws2", new Channel("shanxiws2", "山西卫视", "2025560803", "600190407", "fhd"));
        channels.put("qhws", new Channel("qhws", "青海卫视", "2025559103", "600190406", "fhd"));
        channels.put("xzws", new Channel("xzws", "西藏卫视", "2025558003", "600190403", "fhd"));
        channels.put("cetv1", new Channel("cetv1", "中国教育电视台1", "2022823801", "600171827", "fhd"));
        channels.put("gxpd", new Channel("gxpd", "国学频道", "2029360403", "600213139", "fhd"));
        channels.put("xjws", new Channel("xjws", "新疆卫视", "2019927403", "600152138", "fhd"));
        CHANNELS = Collections.unmodifiableMap(channels);

        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put("央视", List.of(
                "cctv1", "cctv2", "cctv3", "cctv4", "cctv5", "cctv5p",
                "cctv6", "cctv7", "cctv8", "cctv9", "cctv10", "cctv11",
                "cctv12", "cctv13", "cctv14", "cctv15", "cctv16", "cctv164k",
                "cctv17", "cctv4k", "cctv8k", "cgtn", "cgtnfy", "cgtney",
                "cgtnalby", "cgtnxby", "cgtnwyjl"
        ));
        groups.put("卫视", List.of(
                "bjws", "jsws", "dfws", "zjws", "hnws", "hbws",
                "gdws", "gxws", "hljws", "hnws2", "cqws", "szws",
                "scws", "henanws", "fjdnhz", "gzhws", "jxws", "lnws",
                "ahws", "hbws2", "sdws", "tjws", "jlws", "shanxiws",
                "nxws", "nmgws", "ynws", "shanxiws2", "qhws", "xzws",
                "cetv1", "xjws"
        ));
        groups.put("数字付费", List.of(
                "cctvfyjc", "cctvdyjc", "cctvhjjc", "cctvsjdl", "cctvfyyy", "cctvbqkj",
                "cctvfyzq", "cctvgeqwq", "cctvnxss", "cctvyswhjp", "cctvystq", "cctvdszn",
                "cctvwsjk", "gxpd"
        ));
        GROUPS = Collections.unmodifiableMap(groups);
    }

    private YspChannels() {
    }

    /** 按 pid 取频道；不存在返回 null。 */
    public static Channel get(String id) {
        return id == null ? null : CHANNELS.get(id);
    }

    /** 全部频道（保持 Python 版的声明顺序）。 */
    public static Map<String, Channel> all() {
        return CHANNELS;
    }

    /** 分组 → 频道 pid 列表（央视 / 卫视 / 数字付费），保持 Python 版顺序。 */
    public static Map<String, List<String>> groups() {
        return GROUPS;
    }

    /** 按 cnlid 反查 pid（回看降级到直播时用）。找不到返回 null。 */
    public static String findIdByCnlid(String cnlid) {
        if (cnlid == null) return null;
        for (Channel ch : CHANNELS.values()) {
            if (cnlid.equals(ch.cnlid)) return ch.id;
        }
        return null;
    }

    /** 频道总数。 */
    public static int size() {
        return CHANNELS.size();
    }

    /**
     * 央视频 EPG 接口使用的频道名（{@code c=} 参数）。
     *
     * <p>{@code api.cntv.cn} 的节目单接口只认这套小写 ID，且部分频道与我们本地 pid 不同名。
     * 返回 {@code null} 表示该频道无节目单。
     *
     * <p><b>黑名单是必需的</b>：该接口对不认识的频道名会返回
     * {@code {"errcode":"1001","msg":"params error"}}，而<b>批量请求中只要有一个非法名，
     * 整批都会失败</b>。实测 {@code cctv14} 与 CGTN 系列均被拒绝，必须提前剔除，
     * 否则整个节目单都拉不回来。
     */
    public static String epgCode(Channel ch) {
        if (ch == null) return null;
        if ("cctv5p".equals(ch.id)) return "cctv5plus"; // 接口用 cctv5plus，cctv5p 会报 params error
        if ("cctv164k".equals(ch.id)) return "cctv16";  // 4K 版复用 16 标清频道的节目单
        if ("cctv4k".equals(ch.id) || "cctv8k".equals(ch.id)) return null;
        if ("cctv14".equals(ch.id)) return null;        // 实测该接口拒绝 cctv14
        if (ch.id.startsWith("cctv")) return ch.id;     // cctv1 .. cctv17（除上面剔除的）
        return null;                                     // CGTN 系列、卫视、付费频道均无节目单
    }
}
