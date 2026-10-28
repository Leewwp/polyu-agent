/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.core.chunk.dates;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 块文本日期事实抽取（#411，规则依据=#376 research/date-structure-376-20261011 结论）
 * <p>
 * 纯函数、无状态、不依赖 Spring——发布与截止两族语义各自独立（发布≠截止，不得互替）：
 * <ul>
 *   <li>发布时间（{@code sourceDate}）：页面发布/更新日期，只认显式发布标记
 *       （{@code Updated on 21 July 2026}、{@code 发布于 2026年7月21日} 等）；</li>
 *   <li>活动截止日期（{@code activityEndDate}）：报名/申请/缴费等条目级截止事实，
 *       只认显式截止标记（{@code Payment Deadline: 1 September 2026}、{@code 报名截止：…}
 *       及中文后缀式 {@code 2026年10月15日截止}）。</li>
 * </ul>
 * 缺失即缺失：无标记、仅年份、学年形态（{@code 26/27}、{@code 2026/27}）一律返回 null，
 * 不虚构、不推算、不做相对日期换算（#376 红线：学年误标为事件日期即制造错误）。
 * <p>
 * 同族多个标记命中时取正文出现位置最早的一处（确定性）；标记后的首个完整日期须在
 * 60 字符窗口内且历法有效（2 月 30 日按无效跳过继续找），窗口内无完整日期则该命中不产出。
 */
public final class ChunkDateFacts {

    /**
     * 抽取结果：两个日期各自独立可空，双 null = 本块无日期事实（「日期未知」语义）
     */
    public record Dates(LocalDate sourceDate, LocalDate activityEndDate) {

        public static final Dates EMPTY = new Dates(null, null);

        public boolean isEmpty() {
            return sourceDate == null && activityEndDate == null;
        }
    }

    private static final DateTimeFormatter ISO_DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    /** 月名 token：全称在前、缩写在后（Sept 先于 Sep），后邻字母即拒判（防 Mayday 之类粘连） */
    private static final String MONTHS =
            "January|February|March|April|May|June|July|August|September|October|November|December"
                    + "|Sept|Sep|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Oct|Nov|Dec";

    /**
     * 日期文法（完整日期=日+月+年三要素齐备，缺一即不成形）：
     * 英式日-月-年 / 美式月-日-年 / 中式年月日 / ISO 短横线 / ISO 斜杠；
     * 数字侧加不咬边界守卫（防从更长数字串中部起配）；学年形态只含年+一份两位数，天然不满足文法
     */
    private static final Pattern FULL_DATE = Pattern.compile(
            "(?i)(?:(?<!\\d)(\\d{1,2})\\s+(" + MONTHS + ")\\.?(?![A-Za-z])\\s+(\\d{4})(?!\\d))"
                    + "|(?:(" + MONTHS + ")\\.?(?![A-Za-z])\\s+(?<!\\d)(\\d{1,2})(?!\\d)\\s*,?\\s+(\\d{4})(?!\\d))"
                    + "|(?:(\\d{4})年(\\d{1,2})月(\\d{1,2})日?)"
                    + "|(?:(?<!\\d)(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d))"
                    + "|(?:(?<!\\d)(\\d{4})/(\\d{1,2})/(\\d{1,2})(?!\\d))");

    private static final Map<String, Integer> MONTH_INDEX = buildMonthIndex();

    /**
     * 发布标记：页面发布/更新署期。白名单保守——泛指时间（如 during 2026）不触发
     */
    private static final List<Pattern> SOURCE_MARKERS = List.of(
            Pattern.compile("(?i)\\b(?:last\\s+|page\\s+)?updated(?:\\s+on)?\\b"),
            Pattern.compile("(?i)\\bpublished?\\s+on\\b"),
            Pattern.compile("(?i)\\bpublication\\s+date\\b"),
            Pattern.compile("(?i)\\blast\\s+revised(?:\\s+on)?\\b"),
            Pattern.compile("(?i)\\blast\\s+reviewed(?:\\s+on)?\\b"),
            Pattern.compile("(?i)\\bdate\\s+of\\s+issue\\b"),
            Pattern.compile("(?i)\\bissue\\s+date\\b"),
            Pattern.compile("发布于|发布日期|更新于|更新日期|最后更新|最新更新|刊登日期"));

    /**
     * 截止标记：报名/申请/缴费等显式截止句式；en-022 语料形态（Registration was open until …）单列
     */
    private static final List<Pattern> DEADLINE_MARKERS = List.of(
            Pattern.compile("(?i)\\bdeadline\\b"),
            Pattern.compile("(?i)\\bclosing\\s+date\\b"),
            Pattern.compile("(?i)\\b(?:register|registration|apply|application|enrol|enrolment|enroll|enrollment|submit|submission|payment|pay)\\s+(?:by|before)\\b"),
            Pattern.compile("(?i)\\bopen\\s+until\\b"),
            Pattern.compile("(?i)\\b(?:registration|enrolment|enrollment|application)\\s+(?:was\\s+)?(?:open\\s+)?until\\b"),
            Pattern.compile("(?i)\\bclos(?:e|es|ed|ing)\\s+on\\b"),
            Pattern.compile("(?:报名|申请|登记|缴费|递交)?截止(?:日期|时间)?"));

    /** 中文后缀式截止：日期在前、标记在后（{@code 2026年10月15日截止}），仅归属截止族 */
    private static final Pattern SUFFIX_DEADLINE = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日?\\s*截止");

    /** 标记到日期的搜索窗口：超出视为日期不属于该标记（防跨句误配） */
    private static final int MARKER_DATE_WINDOW = 60;

    private ChunkDateFacts() {
    }

    /**
     * 从块文本抽取两个独立日期事实；content 为 null/空白返回 {@link Dates#EMPTY}
     */
    public static Dates extract(String content) {
        if (content == null || content.isBlank()) {
            return Dates.EMPTY;
        }
        LocalDate source = earliestMarkedDate(content, SOURCE_MARKERS);
        LocalDate deadline = earliestMarkedDate(content, DEADLINE_MARKERS);
        if (deadline == null) {
            deadline = suffixDeadline(content).orElse(null);
        }
        return new Dates(source, deadline);
    }

    /**
     * 扫全部标记族的全部命中：命中处窗口内能取到有效日期者参与竞选，取标记位置最早的一处
     */
    private static LocalDate earliestMarkedDate(String content, List<Pattern> markers) {
        LocalDate best = null;
        int bestPos = Integer.MAX_VALUE;
        for (Pattern marker : markers) {
            Matcher m = marker.matcher(content);
            while (m.find()) {
                if (m.start() >= bestPos) {
                    break;
                }
                LocalDate date = firstDateAfter(content, m.end());
                if (date != null) {
                    best = date;
                    bestPos = m.start();
                    break;
                }
                // 本命中窗口内无完整日期：继续看该标记更晚的命中
            }
        }
        return best;
    }

    /**
     * 从 from 起在窗口内找首个历法有效的完整日期；学年形态不满足文法，天然拒判
     */
    private static LocalDate firstDateAfter(String content, int from) {
        int windowEnd = Math.min(content.length(), from + MARKER_DATE_WINDOW);
        Matcher m = FULL_DATE.matcher(content);
        if (from > windowEnd) {
            return null;
        }
        m.region(from, windowEnd);
        while (m.find()) {
            LocalDate parsed = toLocalDate(m);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    /** 中文后缀式截止：日期即截止日，历法无效则不产出 */
    private static Optional<LocalDate> suffixDeadline(String content) {
        Matcher m = SUFFIX_DEADLINE.matcher(content);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(
                    Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 按文法分支还原日期；无效历法（如 2 月 30 日）返回 null 由调用方继续找下一匹配 */
    private static LocalDate toLocalDate(Matcher m) {
        try {
            if (m.group(1) != null) {
                return LocalDate.of(Integer.parseInt(m.group(3)), monthOf(m.group(2)), Integer.parseInt(m.group(1)));
            }
            if (m.group(4) != null) {
                return LocalDate.of(Integer.parseInt(m.group(6)), monthOf(m.group(4)), Integer.parseInt(m.group(5)));
            }
            if (m.group(7) != null) {
                return LocalDate.of(Integer.parseInt(m.group(7)), Integer.parseInt(m.group(8)),
                        Integer.parseInt(m.group(9)));
            }
            if (m.group(10) != null) {
                return LocalDate.of(Integer.parseInt(m.group(10)), Integer.parseInt(m.group(11)),
                        Integer.parseInt(m.group(12)));
            }
            if (m.group(13) != null) {
                return LocalDate.of(Integer.parseInt(m.group(13)), Integer.parseInt(m.group(14)),
                        Integer.parseInt(m.group(15)));
            }
        } catch (Exception ignored) {
            // 越界日期（2 月 30 日等）按无效丢弃，继续找下一个匹配
        }
        return null;
    }

    private static int monthOf(String token) {
        return MONTH_INDEX.get(token.toLowerCase());
    }

    private static Map<String, Integer> buildMonthIndex() {
        String[] full = {"january", "february", "march", "april", "may", "june",
                "july", "august", "september", "october", "november", "december"};
        String[] abbrev = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sept", "sep",
                "oct", "nov", "dec"};
        int[] abbrevMonth = {1, 2, 3, 4, 5, 6, 7, 8, 9, 9, 10, 11, 12};
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < full.length; i++) {
            index.put(full[i], i + 1);
        }
        for (int i = 0; i < abbrev.length; i++) {
            index.putIfAbsent(abbrev[i], abbrevMonth[i]);
        }
        return Map.copyOf(index);
    }

    /** yyyy-MM-dd 序列化（#275 口径，向量 metadata 与读回通道的统一格式） */
    public static String toIsoDay(LocalDate date) {
        return date == null ? null : date.format(ISO_DAY);
    }
}
