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

import com.nageoffer.ai.ragent.calendar.model.Disposition;
import com.nageoffer.ai.ragent.calendar.model.KeyDateCandidate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #3 cal-timetable-exam-results 解析器（转译 replay.py parse_cte）。
 *
 * <p>写域=发布窗（考试/课堂时间表发布）；考试区间、成绩发布、开课日只校验。
 * 一格多事件按日期短语起点机械切分（如 July 格 3 事件）。class-tt-release 的
 * 目标学年按页面证据推导（deriveClassTtTargetAy）：发布窗服务的学期开始年份
 * 由页内 Commencement 行自证——页内存在晚于发布窗的该学期 Commencement → 取
 * 其标注学年；否则该发布窗服务下一学年实例（发布窗先于学期开始的页面自证模式
 * + S1 于 8 月末开学）。不使用抓取时钟，不套整页学年（合同§3.1：页面覆盖学年
 * 与目标事件学年分开）。
 */
public class TimetableExamResultsParser implements CalendarPageParser {

    /**
     * 日期短语起点（切分锚）：同月/跨月区间式 + 单日式 + 模糊窗式（与 Python
     * alternation 顺序一致，finditer 全部不重叠命中）
     */
    private static final Pattern SPLIT_RE = Pattern.compile(
            "\\d{1,2}(?: to \\d{1,2})? [A-Z][a-z]+ \\d{4}"
                    + "|\\d{1,2} [A-Z][a-z]+ to \\d{1,2} [A-Z][a-z]+ \\d{4}"
                    + "|(?:Late|Early|Mid|Middle)(?: of)? [A-Z][a-z]+ \\d{4}");

    private static final String TERM_GROUP = "(Semester One|Semester Two|Summer Term)";
    private static final String FUZZY_GROUP = "((?:Late|Early|Mid|Middle)(?: of)? [A-Z][a-z]+ \\d{4})";

    private static final Pattern RE_EXAM_TT = Pattern.compile(
            "^" + FUZZY_GROUP + " Release of Examination Timetable \\(" + TERM_GROUP + "\\)$");
    private static final Pattern RE_CLASS_TT = Pattern.compile(
            "^" + FUZZY_GROUP + " Release of Class Timetable \\(" + TERM_GROUP + "\\)$");
    private static final Pattern RE_EXAM_PERIOD = Pattern.compile(
            "^(.*?) Examination Period \\(" + TERM_GROUP + "\\)$");
    private static final Pattern RE_SUBJECT_RESULTS = Pattern.compile(
            "^(\\d{1,2} \\w+ \\d{4}) Release of Subject Results \\(" + TERM_GROUP + "\\)$");
    private static final Pattern RE_OVERALL_RESULTS = Pattern.compile(
            "^(\\d{1,2} \\w+ \\d{4}) Release of Overall Results \\(" + TERM_GROUP + "\\)$");

    @Override
    public String sourceKey() {
        return "cal-timetable-exam-results";
    }

    @Override
    public List<KeyDateCandidate> parse(List<List<String>> rows, String pageText) {
        return parse(rows, pageText, commencements(rows));
    }

    /**
     * 全参解析（复用预提取的 Commencement 映射；测试注入变体行时同源）
     */
    public List<KeyDateCandidate> parse(List<List<String>> rows, String pageText,
                                        Map<String, String[]> commencements) {
        String ay = CalendarPageParser.pageAy(sourceKey(), pageText, null);
        String ayEvidence = ay != null
                ? "页面散文 '…Assessment Results for Academic Year %s'".formatted(ay)
                : null;
        List<KeyDateCandidate> out = new ArrayList<>();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            if (ri == 0 || r.isEmpty()) {
                continue;
            }
            String act = r.get(r.size() - 1).strip();
            if ("---".equals(act)) {
                out.add(new KeyDateCandidate(sourceKey(), "r" + ri + ":f0", "---",
                        Disposition.KNOWN_SKIP, "空月份占位符 ---"));
                continue;
            }
            List<String> segs = splitActivities(act);
            for (int fi = 0; fi < segs.size(); fi++) {
                out.add(classifyFragment(segs.get(fi), "r" + ri + ":f" + fi, ay, ayEvidence, commencements));
            }
        }
        return out;
    }

    /**
     * 页内 Commencement 行 → {term: [iso 日期, 学年标注]}——发布窗目标学年推导的
     * 页面证据源（全表任何单元格命中即收）
     */
    public static Map<String, String[]> commencements(List<List<String>> rows) {
        Map<String, String[]> com = new LinkedHashMap<>();
        for (List<String> r : rows) {
            for (String cell : r) {
                Matcher mc = CalendarLexicon.COMMENCE_RE.matcher(cell.strip());
                if (mc.matches()) {
                    String date = CalendarDates.parseDay(mc.group(1)).map(LocalDate::toString).orElse(null);
                    if (date != null) {
                        com.putIfAbsent(CalendarLexicon.TERM_MAP.get(mc.group(2)), new String[]{date, mc.group(3)});
                    }
                }
            }
        }
        return com;
    }

    /**
     * class-tt-release 目标学年推导（仅页面证据；返回 [目标学年, 证据描述]）。
     * 规则A：页内存在晚于发布窗的该学期 Commencement → 取其标注学年；
     * 规则B：无 → 发布窗服务下一学年实例（页面自证模式：S2 tt Late Dec→次年
     * 1/11 开学、SU tt Early Mar→同年 5/24 开学、S1 于 8 月末开学，故 Late July
     * 发布的 S1 时间表指向次年 8 月末开学的 S1）
     */
    static String[] deriveClassTtTargetAy(String pageAy, String term, String bucket,
                                          Map<String, String[]> commencements) {
        String[] parts = bucket.split("-");
        int year = Integer.parseInt(parts[parts.length - 1]);
        String mon = parts[parts.length - 2];
        LocalDate relIso = LocalDate.of(year, CalendarDates.MONTHS.get(mon), 15);
        if (commencements.containsKey(term)) {
            String[] com = commencements.get(term);
            LocalDate cdate = LocalDate.parse(com[0]);
            if (cdate.isAfter(relIso)) {
                return new String[]{com[1],
                        "页内 %s Commencement=%s（标注 %s）晚于发布窗 → 目标学年=%s"
                                .formatted(term, com[0], com[1], com[1])};
            }
        }
        int y = Integer.parseInt(pageAy.substring(0, 4));
        String next = "%d/%02d".formatted(y + 1, (y + 2) % 100);
        return new String[]{next,
                "页内无晚于发布窗的 %s Commencement；页面模式=发布窗先于学期开始（S2 tt Late Dec→1月开学、"
                        .formatted(term)
                        + "SU tt Early Mar→5月开学）+ S1 于 8 月末开学 → Late July 的 S1 时间表指向 "
                        + (y + 1) + " 年 8 月末开学的 S1 → 目标学年=" + next + "（未使用抓取时钟/整页学年）"};
    }

    private KeyDateCandidate classifyFragment(String seg, String floc, String ay, String ayEvidence,
                                              Map<String, String[]> commencements) {
        Matcher m = CalendarLexicon.COMMENCE_RE.matcher(seg);
        if (m.matches()) {
            KeyDateCandidate c = new KeyDateCandidate(sourceKey(), floc, seg, Disposition.VERIFY,
                    "校验：开课日权威在 cal-academic-calendar（semester-teaching/start）");
            c.setAy(m.group(3));
            c.setTerm(CalendarLexicon.TERM_MAP.get(m.group(2)));
            c.setEventCode("semester-teaching");
            c.setAudienceCode("all");
            c.setSlot("start");
            c.setPrecision("exact-day");
            CalendarDates.parseDay(m.group(1)).ifPresent(c::setDateStart);
            c.setAyEvidence(ayEvidence);
            return c;
        }
        if ((m = RE_EXAM_TT.matcher(seg)).matches()) {
            KeyDateCandidate c = new KeyDateCandidate(sourceKey(), floc, seg, Disposition.WRITE,
                    "写域：考试时间表发布窗（考试周在本页 AY 内，目标学年=页面学年）");
            c.setAy(ay);
            c.setTerm(CalendarLexicon.TERM_MAP.get(m.group(2)));
            c.setEventCode("exam-tt-release");
            c.setAudienceCode("all");
            c.setSlot("window");
            c.setPrecision("fuzzy");
            c.setFuzzyBucket(CalendarDates.fuzzyBucket(m.group(1)));
            c.setAyEvidence(ayEvidence);
            return c;
        }
        if ((m = RE_CLASS_TT.matcher(seg)).matches()) {
            String bucket = CalendarDates.fuzzyBucket(m.group(1));
            String term = CalendarLexicon.TERM_MAP.get(m.group(2));
            String[] target = ay != null
                    ? deriveClassTtTargetAy(ay, term, bucket, commencements)
                    : null;
            KeyDateCandidate c = new KeyDateCandidate(sourceKey(), floc, seg, Disposition.WRITE,
                    "写域：开课时间表发布窗（目标学期=括注学期；目标学年按下述页面证据推导）");
            c.setAy(target != null ? target[0] : null);
            c.setTerm(term);
            c.setEventCode("class-tt-release");
            c.setAudienceCode("all");
            c.setSlot("window");
            c.setPrecision("fuzzy");
            c.setFuzzyBucket(bucket);
            c.setAyEvidence(target != null ? target[1] : ayEvidence);
            return c;
        }
        if ((m = RE_EXAM_PERIOD.matcher(seg)).matches()) {
            CalendarDates.ParsedDate p = CalendarDates.parseRangeOrDay(m.group(1));
            if (p == null) {
                return new KeyDateCandidate(sourceKey(), floc, seg,
                        Disposition.UNKNOWN, "考试周日期不可解析");
            }
            KeyDateCandidate c = new KeyDateCandidate(sourceKey(), floc, seg, Disposition.VERIFY,
                    "校验：考试区间权威在 cal-academic-calendar（exam-period/window）");
            c.setAy(ay);
            c.setTerm(CalendarLexicon.TERM_MAP.get(m.group(2)));
            c.setEventCode("exam-period");
            c.setAudienceCode("all");
            c.setSlot("window");
            c.setPrecision(p.precision());
            c.setDateStart(p.dateStart());
            c.setDateEnd(p.dateEnd());
            c.setAyEvidence(ayEvidence);
            return c;
        }
        if ((m = RE_SUBJECT_RESULTS.matcher(seg)).matches()) {
            KeyDateCandidate c = new KeyDateCandidate(sourceKey(), floc, seg, Disposition.VERIFY,
                    "校验：科目成绩发布权威在 cal-assessment-results（results-subject-release/subject）");
            c.setAy(ay);
            c.setTerm(CalendarLexicon.TERM_MAP.get(m.group(2)));
            c.setEventCode("results-subject-release");
            c.setAudienceCode("all");
            c.setSlot("subject");
            c.setPrecision("exact-day");
            CalendarDates.parseDay(m.group(1)).ifPresent(c::setDateStart);
            c.setAyEvidence(ayEvidence);
            return c;
        }
        if ((m = RE_OVERALL_RESULTS.matcher(seg)).matches()) {
            KeyDateCandidate c = new KeyDateCandidate(sourceKey(), floc, seg, Disposition.VERIFY,
                    "校验：总评成绩发布权威在 cal-assessment-results（results-overall-release/overall）");
            c.setAy(ay);
            c.setTerm(CalendarLexicon.TERM_MAP.get(m.group(2)));
            c.setEventCode("results-overall-release");
            c.setAudienceCode("all");
            c.setSlot("overall");
            c.setPrecision("exact-day");
            CalendarDates.parseDay(m.group(1)).ifPresent(c::setDateStart);
            c.setAyEvidence(ayEvidence);
            return c;
        }
        return new KeyDateCandidate(sourceKey(), floc, seg, Disposition.UNKNOWN, "片段未命中词表");
    }

    /**
     * 一格多事件切分：按日期短语起点切（跨月区间式先于单日式命中——alternation
     * 顺序与 Python 版一致）；无日期短语返回原格单片段
     */
    static List<String> splitActivities(String act) {
        List<int[]> spans = new ArrayList<>();
        Matcher m = SPLIT_RE.matcher(act);
        while (m.find()) {
            spans.add(new int[]{m.start(), m.end()});
        }
        if (spans.isEmpty()) {
            return List.of(act);
        }
        List<String> segs = new ArrayList<>();
        int prev = spans.get(0)[0];
        for (int i = 1; i < spans.size(); i++) {
            segs.add(act.substring(prev, spans.get(i)[0]));
            prev = spans.get(i)[0];
        }
        segs.add(act.substring(prev));
        return segs.stream()
                .map(s -> CalendarHtmlTables.normalizeWhitespace(s))
                .map(s -> stripEdges(s, " ;"))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * 只剥两端指定字符集（等价 Python str.strip(" ;")；不用 Java strip()——那是
     * Unicode 空白语义，不同口径）
     */
    private static String stripEdges(String s, String chars) {
        int start = 0;
        int end = s.length();
        while (start < end && chars.indexOf(s.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && chars.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        return s.substring(start, end);
    }
}
