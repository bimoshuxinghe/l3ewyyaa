package com.fongmi.android.tv.player.exo;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能去广（移植自开源项目 ltxlong/M3U8-Filter-Ad-Script 的过滤规则）。
 * <p>
 * 采集站/解析站的 HLS 插播广告通常表现为：在 m3u8 清单中间插入一段
 * 带 #EXT-X-DISCONTINUITY 标记、且 ts 文件名长度或序号规律明显异常的切片块。
 * 本过滤器按以下三种模式识别并删除广告块，且尽量保证不误删正常切片：
 * <ul>
 *   <li>模式0（自动判断，推荐）：#EXT-X-DISCONTINUITY 标记 + ts 文件名长度突变 / 序号断连 → 删除广告块</li>
 *   <li>模式1（命名不规则）：EXTINF 重复特征 + DISCONTINUITY → 删除广告块或标记</li>
 *   <li>模式2（暴力兜底）：只删 DISCONTINUITY 标记，不删任何切片</li>
 * </ul>
 * 纯文本处理、无外部依赖，只处理 m3u8 清单文本，不影响切片直连。
 */
public class M3u8AdFilter {

    private static final Pattern TS_NUM = Pattern.compile("(\\d+)\\.ts");

    /** 文件名长度容差（超出视为广告特征）。 */
    private static final int TS_NAME_LEN_EXTEND = 1;
    /** 相同 EXTINF 基准计数（模式1 用）。 */
    private static final int EXTINF_BENCHMARK_N = 5;

    private M3u8AdFilter() {
    }

    /** 对 m3u8 清单文本做广告过滤；非 m3u8 内容原样返回。 */
    public static String filter(String content) {
        if (content == null || content.isEmpty()) return content;
        // 快速路径：没有任何可过滤特征直接返回
        if (!content.contains(".ts") && !content.contains("#EXT-X-DISCONTINUITY")) return content;
        String[] lines = content.split("\n", -1);
        List<String> result = filterLines(lines);
        return String.join("\n", result);
    }

    private static int extractNumberBeforeTs(String line) {
        Matcher m = TS_NUM.matcher(line);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static List<String> filterLines(String[] lines) {
        List<String> result = new ArrayList<>();

        // ---- 检测 ts 命名模式 ----
        int tsNameLen = 0;
        int tsType = 2;
        int prevTsNameIndex = -1;
        int firstTsNameIndex = -1;
        String firstExtinfRow = "";
        int extinfJudgeRowN = 0;
        {
            int normalN = 0;
            int diffN = 0;
            int lastTsNameLen = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (extinfJudgeRowN == 0 && line.startsWith("#EXTINF")) {
                    firstExtinfRow = line;
                    extinfJudgeRowN++;
                } else if (extinfJudgeRowN == 1 && line.startsWith("#EXTINF")) {
                    if (!line.equals(firstExtinfRow)) firstExtinfRow = "";
                    extinfJudgeRowN++;
                }
                int theTsNameLen = line.indexOf(".ts");
                if (theTsNameLen > 0) {
                    if (extinfJudgeRowN == 1) tsNameLen = theTsNameLen;
                    lastTsNameLen = theTsNameLen;
                    int tsNameIndex = extractNumberBeforeTs(line);
                    if (tsNameIndex < 0) {
                        if (extinfJudgeRowN == 1) {
                            tsType = 1;
                        } else if (extinfJudgeRowN == 2 && (tsType == 1 || theTsNameLen == tsNameLen)) {
                            tsType = 1;
                            break;
                        } else {
                            diffN++;
                        }
                    } else {
                        if (normalN == 0) {
                            firstTsNameIndex = tsNameIndex;
                            prevTsNameIndex = firstTsNameIndex - 1;
                        }
                        if (theTsNameLen != tsNameLen) {
                            if (theTsNameLen == lastTsNameLen + 1 && tsNameIndex == prevTsNameIndex + 1) {
                                if (diffN > 0) {
                                    if (tsNameIndex == prevTsNameIndex + 1) {
                                        tsType = 0;
                                        prevTsNameIndex = firstTsNameIndex - 1;
                                        break;
                                    } else {
                                        tsType = 2;
                                        break;
                                    }
                                }
                                normalN++;
                                prevTsNameIndex = tsNameIndex;
                            } else {
                                diffN++;
                            }
                        } else {
                            if (diffN > 0) {
                                if (tsNameIndex == prevTsNameIndex + 1) {
                                    tsType = 0;
                                    prevTsNameIndex = firstTsNameIndex - 1;
                                    break;
                                } else {
                                    tsType = 2;
                                    break;
                                }
                            }
                            normalN++;
                            prevTsNameIndex = tsNameIndex;
                        }
                    }
                    if (i == lines.length - 1) tsType = 2;
                }
            }
        }

        // ---- 过滤阶段 ----
        int sameExtinfNameN = 0;
        int extXMode = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (tsType == 0) {
                if (line.startsWith("#EXT-X-DISCONTINUITY") && i + 1 < lines.length && i + 2 < lines.length) {
                    // 前面紧跟 #EXT-X- 头（如 PLAYLIST-TYPE 后的正常分段）→ 保留
                    if (i > 0 && lines[i - 1].startsWith("#EXT-X-")) {
                        result.add(line);
                        continue;
                    }
                    int theTsNameLen = lines[i + 2].indexOf(".ts");
                    if (theTsNameLen > 0) {
                        // 文件名长度突变（变长/变短都算）→ 广告块；删除时不污染长度基准
                        if (Math.abs(theTsNameLen - tsNameLen) > TS_NAME_LEN_EXTEND) {
                            if (i + 3 < lines.length && lines[i + 3].startsWith("#EXT-X-DISCONTINUITY")) i += 3;
                            else i += 2;
                            continue;
                        }
                        tsNameLen = theTsNameLen;
                        // 序号断连或非数字命名 → 广告块（JS: undefined !== prev+1 恒真）
                        int theTsNameIndex = extractNumberBeforeTs(lines[i + 2]);
                        if (theTsNameIndex != prevTsNameIndex + 1) {
                            if (i + 3 < lines.length && lines[i + 3].startsWith("#EXT-X-DISCONTINUITY")) i += 3;
                            else i += 2;
                            continue;
                        }
                    }
                }
                if (line.startsWith("#EXTINF") && i + 1 < lines.length) {
                    int theTsNameLen = lines[i + 1].indexOf(".ts");
                    if (theTsNameLen > 0) {
                        if (Math.abs(theTsNameLen - tsNameLen) > TS_NAME_LEN_EXTEND) {
                            if (i + 2 < lines.length && lines[i + 2].startsWith("#EXT-X-DISCONTINUITY")) i += 2;
                            else i += 1;
                            continue;
                        }
                        tsNameLen = theTsNameLen;
                        int theTsNameIndex = extractNumberBeforeTs(lines[i + 1]);
                        if (theTsNameIndex == prevTsNameIndex + 1) {
                            prevTsNameIndex++;
                        } else {
                            if (i + 2 < lines.length && lines[i + 2].startsWith("#EXT-X-DISCONTINUITY")) i += 2;
                            else i += 1;
                            continue;
                        }
                    }
                }
            } else if (tsType == 1) {
                if (line.startsWith("#EXTINF")) {
                    if (line.equals(firstExtinfRow) && sameExtinfNameN <= EXTINF_BENCHMARK_N && extXMode == 0) {
                        sameExtinfNameN++;
                    } else {
                        extXMode = 1;
                    }
                    if (sameExtinfNameN > EXTINF_BENCHMARK_N) extXMode = 1;
                }
                if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                    if (i > 0 && lines[i - 1].startsWith("#EXT-X-PLAYLIST-TYPE")) {
                        result.add(line);
                        continue;
                    }
                    if (i + 1 < lines.length && lines[i + 1].startsWith("#EXTINF")
                            && i + 2 < lines.length && lines[i + 2].indexOf(".ts") > 0) {
                        boolean condition = false;
                        if (extXMode == 1) {
                            condition = !lines[i + 1].equals(firstExtinfRow) && sameExtinfNameN > EXTINF_BENCHMARK_N;
                        }
                        if (i + 3 < lines.length && lines[i + 3].startsWith("#EXT-X-DISCONTINUITY") && condition) {
                            i += 3;
                        }
                        // 否则只删 DISCONTINUITY 标记（不 push）
                        continue;
                    }
                    // 不是 EXTINF+ts 结构 → 保留（落空到末尾 push）
                }
            } else {
                // 模式2：暴力拆解，只删 DISCONTINUITY 标记
                if (line.startsWith("#EXT-X-DISCONTINUITY")) {
                    if (i > 0 && lines[i - 1].startsWith("#EXT-X-PLAYLIST-TYPE")) {
                        result.add(line);
                        continue;
                    }
                    continue;
                }
            }

            result.add(line);
        }

        return result;
    }
}
