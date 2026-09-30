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

package com.nageoffer.ai.ragent.calendar.parse;

import java.time.LocalDate;
import java.time.MonthDay;
import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 页面日期短语解析（转译 replay.py 日期段）：只接受完整明确日期/区间/onwards/
 * 模糊窗四种形态；「TBD」「Late October 2026」类模糊窗不伪造具体日（不落
 * date 字段，只落 fuzzy 桶）。
 *
 * <p>解析失败一律返回 empty——由调用方归 UNKNOWN 并触发整源退化；本类不抛异常、
 * 不做任何兜底猜测（无当前时钟、无「宽泛年度范围内就近取值」）。
 */
public final class CalendarDates {

    /**
     * 月份名→月号（页面全拼月名）
     */
    static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("january", 1), Map.entry("february", 2), Map.entry("march", 3),
            Map.entry("april", 4), Map.entry("may", 5), Map.entry("june", 6),
            Map.entry("july", 7), Map.entry("august", 8), Map.entry("september", 9),
            Map.entry("october", 10), Map.entry("november", 11), Map.entry("december", 12));

    /**
     * 页面月名（首字母大写形态）交替正则段
     */
    static final String MON_ALT = "January|February|March|April|May|June|July|August|September|October|November|December";

    static final String DAY_RE = "(\\d{1,2}) (" + MON_ALT + ") (\\d{4})";

    /**
     * 模糊窗（Late October 2026 / Mid of March 2027 / Middle of March 2027——Mid/Middle 别名同桶）
     */
    static final Pattern FUZZY_RE = Pattern.compile("(Late|Early|Mid|Middle)(?: of)? ([A-Z][a-z]+) (\\d{4})");

    private static final Pattern P_DAY = Pattern.compile("^" + DAY_RE + "$");
    private static final Pattern P_RANGE_FULL = Pattern.compile("^" + DAY_RE + " to " + DAY_RE + "$");
    private static final Pattern P_RANGE_SAME_MONTH = Pattern.compile("^(\\d{1,2}) to (\\d{1,2}) (" + MON_ALT + ") (\\d{4})$");
    private static final Pattern P_RANGE_CROSS_MONTH = Pattern.compile("^(\\d{1,2}) (" + MON_ALT + ") to (\\d{1,2}) (" + MON_ALT + ") (\\d{4})$");
    private static final Pattern P_ONWARDS = Pattern.compile("^" + DAY_RE + " onwards$");

    private CalendarDates() {
    }

    /**
     * 明确单日「d Month yyyy」；非该形态返回 empty
     */
    public static Optional<LocalDate> parseDay(String text) {
        Matcher m = P_DAY.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return mk(Integer.parseInt(m.group(1)), m.group(2), Integer.parseInt(m.group(3)));
    }

    /**
     * 区间或单日三型：跨月全式（1 September 2026 to 15 September 2026）、
     * 同月缩式（3 to 18 December 2026）、跨月缩式（22 April to 8 May 2027）。
     * 返回 [precision, start, end]；不可解析返回 null（调用方归 UNKNOWN）
     */
    public static ParsedDate parseRangeOrDay(String text) {
        String t = text.strip();
        Matcher m = P_RANGE_FULL.matcher(t);
        if (m.matches()) {
            return new ParsedDate("exact-range",
                    mk(Integer.parseInt(m.group(1)), m.group(2), Integer.parseInt(m.group(3))).orElse(null),
                    mk(Integer.parseInt(m.group(4)), m.group(5), Integer.parseInt(m.group(6))).orElse(null));
        }
        m = P_RANGE_SAME_MONTH.matcher(t);
        if (m.matches()) {
            return new ParsedDate("exact-range",
                    mk(Integer.parseInt(m.group(1)), m.group(3), Integer.parseInt(m.group(4))).orElse(null),
                    mk(Integer.parseInt(m.group(2)), m.group(3), Integer.parseInt(m.group(4))).orElse(null));
        }
        m = P_RANGE_CROSS_MONTH.matcher(t);
        if (m.matches()) {
            return new ParsedDate("exact-range",
                    mk(Integer.parseInt(m.group(1)), m.group(2), Integer.parseInt(m.group(5))).orElse(null),
                    mk(Integer.parseInt(m.group(3)), m.group(4), Integer.parseInt(m.group(5))).orElse(null));
        }
        Optional<LocalDate> d = parseDay(t);
        return d.map(localDate -> new ParsedDate("exact-day", localDate, null)).orElse(null);
    }

    /**
     * 「d Month yyyy onwards」开放起点（保留开始日与开放结束语义，首批不进 ICS）
     */
    public static Optional<LocalDate> parseOnwards(String text) {
        Matcher m = P_ONWARDS.matcher(text.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        return mk(Integer.parseInt(m.group(1)), m.group(2), Integer.parseInt(m.group(3)));
    }

    /**
     * 模糊窗桶（late-october-2026 等）；Middle 归 mid（词表别名同桶）。非模糊形态返回 null
     */
    public static String fuzzyBucket(String text) {
        Matcher m = FUZZY_RE.matcher(text.strip());
        if (!m.matches()) {
            return null;
        }
        String q = "Middle".equals(m.group(1)) ? "mid" : m.group(1).toLowerCase();
        return q + "-" + m.group(2).toLowerCase() + "-" + m.group(3);
    }

    /**
     * 学年窗 [8/1/起始年, 9/30/次年年]（学年证据校验用；不是撤回授权）
     */
    public static LocalDate[] ayWindow(String ay) {
        int y = Integer.parseInt(ay.substring(0, 4));
        return new LocalDate[]{LocalDate.of(y, 8, 1), LocalDate.of(y + 1, 9, 30)};
    }

    private static Optional<LocalDate> mk(int day, String month, int year) {
        Integer mi = MONTHS.get(month.toLowerCase());
        if (mi == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(year, mi, day));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }

    /**
     * 解析结果载体（precision + 端点；exact-range 两端必须合法有序——由门禁复核）
     */
    public record ParsedDate(String precision, LocalDate dateStart, LocalDate dateEnd) {
    }
}
