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

    private static final Pattern TS_NUM = Pattern.compile("(\\d+)\\.(?:ts|jpg|m4s)");

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
        if (!hasAnySlice(content) && !content.contains("#EXT-X-DISCONTINUITY")) return content;
        String[] lines = content.split("\n", -1);
        // 路径聚类：广告切片路径前缀与正片不同（如日期/目录前缀差异）→ 先删广告块
        String[] pathCleaned = filterByPath(lines);
        if (pathCleaned != null) lines = pathCleaned;
        List<String> result = filterLines(lines);
        return String.join("\n", result);
    }

    /** 切片扩展名位置：.ts / .jpg（图片序列伪装）/ .m4s，非切片行返回 -1。 */
    private static int sliceExtIndex(String line) {
        int idx = line.indexOf(".ts");
        if (idx > 0) return idx;
        idx = line.indexOf(".jpg");
        if (idx > 0) return idx;
        idx = line.indexOf(".m4s");
        return idx > 0 ? idx : -1;
    }

    private static boolean hasAnySlice(String content) {
        return content.contains(".ts") || content.contains(".jpg") || content.contains(".m4s");
    }

    private static boolean isSliceLine(String line) {
        return sliceExtIndex(line) > 0;
    }

    /** 全清单切片名长度众数（正片参照）：用于判断片头块是否与正片同构。 */
    private static int majorityTsLen(String[] lines) {
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        for (String line : lines) {
            int e = sliceExtIndex(line);
            if (e > 0) freq.merge(e, 1, Integer::sum);
        }
        int best = 0, bestF = 0;
        for (java.util.Map.Entry<Integer, Integer> e : freq.entrySet()) {
            if (e.getValue() > bestF) {
                bestF = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    /**
     * 路径聚类删除：统计全部 ts 切片路径前缀，多数派为正片。
     * 非主流路径的 ts 视为广告块删除（连同其 EXTINF、广告 KEY、块边界 DISCONTINUITY）。
     * 仅当存在 2 种以上路径且主流占比 &gt;60% 时启用，避免误删合法多路径拼接。
     * 返回 null 表示无需路径过滤。
     */
    static String[] filterByPath(String[] lines) {
        java.util.Map<String, Integer> freq = new java.util.HashMap<>();
        int total = 0;
        for (String line : lines) {
            String prefix = tsPrefix(line);
            if (prefix != null) {
                freq.merge(prefix, 1, Integer::sum);
                total++;
            }
        }
        if (freq.size() < 2 || total == 0) return null;
        String main = null;
        int mainFreq = 0;
        for (java.util.Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() > mainFreq) {
                mainFreq = e.getValue();
                main = e.getKey();
            }
        }
        if (mainFreq <= total * 0.6) return null; // 主流占比不足，特征不可靠

        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : lines) {
            if (isSliceLine(line) && !line.startsWith("#")) {
                String prefix = tsPrefix(line);
                if (prefix != null && !prefix.equals(main)) {
                    // 广告 ts：回溯删除其 EXTINF / 广告 KEY / 边界 DISCONTINUITY
                    while (!out.isEmpty()) {
                        String last = out.get(out.size() - 1);
                        if (last.startsWith("#EXTINF")) {
                            out.remove(out.size() - 1);
                        } else if (last.startsWith("#EXT-X-KEY")) {
                            if (last.contains(main)) break; // 正片 KEY 保留
                            out.remove(out.size() - 1);
                        } else if (last.startsWith("#EXT-X-DISCONTINUITY")) {
                            out.remove(out.size() - 1);
                        } else {
                            break;
                        }
                    }
                    continue; // 广告 ts 不输出
                }
            }
            out.add(line);
        }
        // 删除头标签后的 DISCONTINUITY（片头广告块收尾标记，正片直接开始）
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).startsWith("#EXT-X-DISCONTINUITY") && isHeaderTag(out.toArray(new String[0]), i)) {
                out.remove(i);
                i--;
            }
        }
        return out.toArray(new String[0]);
    }

    /** 提取 ts 行的路径前缀（不含文件名），无路径（纯文件名）返回 null。 */
    private static String tsPrefix(String line) {
        int slash = line.lastIndexOf('/');
        if (slash <= 0) return null;
        return line.substring(0, slash);
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
                int theTsNameLen = sliceExtIndex(line);
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
        // 基准不继承检测阶段（避免片头广告污染）：首个正常切片重新设基准
        tsNameLen = 0;
        prevTsNameIndex = -1;
        int sameExtinfNameN = 0;
        int extXMode = 0;
        // 全清单切片名长度众数：片头块与正片同构（长度一致）时不删，避免误删正常多分段视频
        int mainTsLen = majorityTsLen(lines);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // 片头广告块：DISCONTINUITY 紧跟在清单头标签（EXTM3U/VERSION/TARGETDURATION/MEDIA-SEQUENCE 等）后
            // → 删除标记 + 广告切片块，直到命名突变/序号回跳（正片开始）或下一个 DISCONTINUITY
            if (line.startsWith("#EXT-X-DISCONTINUITY") && isHeaderTag(lines, i)) {
                int s = skipHeadAd(lines, i, mainTsLen);
                if (s >= 0) {
                    i = s;
                    continue;
                }
                // 与正片同构：保守保留（正常多分段视频的 DISCONTINUITY 不删）
                result.add(line);
                continue;
            }

            if (tsType == 0) {
                if (line.startsWith("#EXT-X-DISCONTINUITY") && i + 1 < lines.length && i + 2 < lines.length) {
                    // 仅 PLAYLIST-TYPE 后的 DISCONTINUITY 是正常分段（对齐 JS：EXTM3U/VERSION/MEDIA-SEQUENCE 后的片头广告不豁免）
                    if (i > 0 && lines[i - 1].startsWith("#EXT-X-PLAYLIST-TYPE")) {
                        result.add(line);
                        continue;
                    }
                    int theTsNameLen = sliceExtIndex(lines[i + 2]);
                    if (theTsNameLen > 0) {
                        if (tsNameLen <= 0) {
                            // 首个切片：设基准不判广告（保守，避免 MEDIA-SEQUENCE 非 0 误删）
                            tsNameLen = theTsNameLen;
                        } else if (Math.abs(theTsNameLen - tsNameLen) > TS_NAME_LEN_EXTEND) {
                            // 文件名长度突变（变长/变短都算）→ 广告块；删除时不污染长度基准
                            if (i + 3 < lines.length && lines[i + 3].startsWith("#EXT-X-DISCONTINUITY")) i += 3;
                            else i += 2;
                            continue;
                        } else {
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
                }
                if (line.startsWith("#EXTINF") && i + 1 < lines.length) {
                    int theTsNameLen = sliceExtIndex(lines[i + 1]);
                    if (theTsNameLen > 0) {
                        int theTsNameIndex = extractNumberBeforeTs(lines[i + 1]);
                        if (tsNameLen <= 0) {
                            // 首个切片：设基准不判广告
                            tsNameLen = theTsNameLen;
                            prevTsNameIndex = theTsNameIndex;
                        } else if (Math.abs(theTsNameLen - tsNameLen) > TS_NAME_LEN_EXTEND) {
                            if (i + 2 < lines.length && lines[i + 2].startsWith("#EXT-X-DISCONTINUITY")) i += 2;
                            else i += 1;
                            continue;
                        } else {
                            tsNameLen = theTsNameLen;
                            if (theTsNameIndex == prevTsNameIndex + 1) {
                                prevTsNameIndex++;
                            } else {
                                if (i + 2 < lines.length && lines[i + 2].startsWith("#EXT-X-DISCONTINUITY")) i += 2;
                                else i += 1;
                                continue;
                            }
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
                            && i + 2 < lines.length && sliceExtIndex(lines[i + 2]) > 0) {
                        // 与全清单同构（无广告特征）→ 保留分段标记
                        if (mainTsLen > 0 && sliceExtIndex(lines[i + 2]) == mainTsLen) {
                            result.add(line);
                            continue;
                        }
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
                    // 与全清单同构（无广告特征）→ 保留分段标记，避免破坏正常多分段视频
                    if (i + 1 < lines.length && i + 2 < lines.length) {
                        int e = sliceExtIndex(lines[i + 2]);
                        if (mainTsLen > 0 && e == mainTsLen) {
                            result.add(line);
                            continue;
                        }
                    }
                    continue;
                }
            }

            result.add(line);
        }

        return result;
    }

    /** 判断 DISCONTINUITY 前一行是否为清单头标签（片头广告特征，非 PLAYLIST-TYPE 正常分段）。 */
    private static boolean isHeaderTag(String[] lines, int i) {
        if (i <= 0) return false;
        String prev = lines[i - 1];
        if (prev.isEmpty()) return true;
        if (prev.startsWith("#EXTM3U")) return true;
        if (prev.startsWith("#EXT-X-PLAYLIST-TYPE")) return false;
        return prev.startsWith("#EXT-X-VERSION")
                || prev.startsWith("#EXT-X-TARGETDURATION")
                || prev.startsWith("#EXT-X-MEDIA-SEQUENCE")
                || prev.startsWith("#EXT-X-KEY")
                || prev.startsWith("#EXT-X-PROGRAM-DATE-TIME")
                || prev.startsWith("#EXT-X-ALLOW-CACHE")
                || prev.startsWith("#EXT-X-INDEPENDENT-SEGMENTS")
                || prev.startsWith("#EXT-X-START");
    }

    /**
     * 删除片头广告块：DISCONTINUITY + 后续 EXTINF+ts 广告切片。
     * 广告块在以下情况结束（返回该行 index，含删）：
     *  - 遇到下一个 DISCONTINUITY（广告块结束标记，一并删除）
     *  - 切片命名长度突变 / 序号回跳（正片开始）
     *  - 非 EXTINF+ts 结构（安全停止）
     * 最多删除 30 个切片防止异常清单无限循环。
     */
    private static int skipHeadAd(String[] lines, int i, int mainTsLen) {
        int firstLen = -1;
        int prevIdx = -1;
        int j = i + 1;
        int removed = 0;
        while (j + 1 < lines.length && removed < 30) {
            String l = lines[j];
            if (l.startsWith("#EXT-X-DISCONTINUITY")) {
                return j;
            }
            if (!l.startsWith("#EXTINF") || sliceExtIndex(lines[j + 1]) <= 0) {
                return j - 1;
            }
            int len = sliceExtIndex(lines[j + 1]);
            if (firstLen < 0) {
                // 与全清单正片同构（长度一致）→ 不是广告块，保守保留
                if (mainTsLen > 0 && len == mainTsLen) return -1;
                firstLen = len;
                prevIdx = extractNumberBeforeTs(lines[j + 1]);
            } else {
                int idx = extractNumberBeforeTs(lines[j + 1]);
                if (Math.abs(len - firstLen) > TS_NAME_LEN_EXTEND) return j - 1;
                if (idx >= 0 && idx < prevIdx) return j - 1;
                prevIdx = idx;
            }
            j += 2;
            removed++;
        }
        return j - 1;
    }
}
