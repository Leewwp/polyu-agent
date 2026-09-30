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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #1 cal-academic-calendar 解析器（转译 replay.py parse_cac，合同§2 写域最宽源）。
 *
 * <p>主表三列：日 | 星期 | 事件。日期三级组装：年份段头（单格四位年）→ 月份头
 * （单格月名）→ 行内日列；日列 d 或 d-d（同月区间）。星期列一致性校验
 * （合同§4 门 1，2026-09-29 裁决②批准）：组装日期的星期 ≠ 页面星期列 → 该行
 * 判 UNKNOWN→整源退化——纯页面证据、无时钟依赖，防 K05 型「月份头缺失→日期
 * 静默归入上月且仍落学年窗内」；源站星期印错时同样拦下存疑，不写错数据。
 *
 * <p>考试区间/复习日行为合并「半行」：单行只含区间一端（commences/ends），
 * 候选附 half/halfDate 线索由 {@link CandidateMerger} 白名单合并为单区间事件。
 */
public class AcademicCalendarParser implements CalendarPageParser {

    private static final Map<String, Integer> WEEKDAYS = Map.of(
            "Monday", 0, "Tuesday", 1, "Wednesday", 2, "Thursday", 3,
            "Friday", 4, "Saturday", 5, "Sunday", 6);

    private static final Pattern RE_YEAR = Pattern.compile("^\\d{4}$");
    private static final Pattern RE_DAY_CELL = Pattern.compile("^\\d{1,2}(-\\d{1,2})?$");
    private static final Pattern RE_DAY_RANGE = Pattern.compile("^(\\d{1,2})-(\\d{1,2})$");
    private static final Pattern RE_TEACHING = Pattern.compile("^(Semester One|Semester Two|Summer Term) teaching (commences|ends)");
    private static final Pattern RE_ADDDROP = Pattern.compile("^Add/Drop period for (Semester One|Semester Two|Summer Term) (commences|ends)");
    private static final Pattern RE_EXAM = Pattern.compile("^Examination Period for (Semester One|Semester Two|Summer Term) (commences|ends)");
    private static final Pattern RE_REVISION = Pattern.compile("^Revision days for (Semester One|Semester Two) examinations (commences|ends)");
    private static final Pattern RE_CONGREGATION = Pattern.compile("Congregation$");
    private static final Pattern RE_FINAL_SUBJECT = Pattern.compile("^Finalisation of all subject assessment results for (Semester One|Semester Two|Summer Term)");
    private static final Pattern RE_FINAL_OVERALL = Pattern.compile("^Finalisation of overall assessment results for (Semester One|Semester Two|Summer Term)");
    private static final Pattern RE_ANNOUNCE = Pattern.compile("^Announcement of (Semester One|Semester Two|Summer Term) overall assessment results");
    private static final Pattern RE_AY_ENDS = Pattern.compile("^Academic Year (\\d{4}/\\d{2}) ends");
    private static final Pattern RE_GENERAL_HOLIDAY = Pattern.compile("^General holiday \\((.+)\\)$");
    private static final Pattern RE_SUSPENSION = Pattern.compile("^(.+?) \\((all (?:day-time and )?evening classes/examinations suspended)\\)$");

    @Override
    public String sourceKey() {
        return "cal-academic-calendar";
    }

    @Override
    public List<KeyDateCandidate> parse(List<List<String>> rows, String pageText) {
        String ay = CalendarPageParser.pageAy(sourceKey(), pageText, rows);
        String yearHeads = String.join("/", rows.stream()
                .filter(r -> r.size() == 1 && RE_YEAR.matcher(r.get(0)).matches())
                .map(r -> r.get(0)).distinct().sorted().toList());
        String ayEvidence = ay != null
                ? "表内行 'Academic Year %s ends' + 互异年份段头相差1年（%s）".formatted(ay, yearHeads)
                : null;

        List<KeyDateCandidate> out = new ArrayList<>();
        Ctx ctx = new Ctx();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            String locRaw = String.join(" | ", r);
            if (r.size() == 1 && RE_YEAR.matcher(r.get(0)).matches()) {
                ctx.year = Integer.parseInt(r.get(0));
                ctx.month = null;
                continue;
            }
            if (r.size() == 1 && CalendarDates.MONTHS.containsKey(r.get(0).toLowerCase())) {
                ctx.month = r.get(0);
                continue;
            }
            if (r.size() != 3) {
                out.add(new KeyDateCandidate(sourceKey(), "r" + ri, locRaw,
                        Disposition.UNKNOWN, "非三列数据行"));
                continue;
            }
            String dayCell = r.get(0);
            String wdText = r.get(1);
            String text = r.get(2);
            ctx.weekday = WEEKDAYS.get(wdText);
            String raw = dayCell + " | " + text;
            classifyRow(out, ri, raw, dayCell, text, ay, ayEvidence, ctx);
        }
        return out;
    }

    private void classifyRow(List<KeyDateCandidate> out, int ri, String raw, String dayCell,
                             String text, String ay, String ayEvidence, Ctx ctx) {
        Matcher m;
        if ((m = RE_TEACHING.matcher(text)).find()) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域：教学起讫",
                    CalendarLexicon.TERM_MAP.get(m.group(1)), "semester-teaching",
                    "commences".equals(m.group(2)) ? "start" : "end", null, null);
            return;
        }
        if ((m = RE_ADDDROP.matcher(text)).find()) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域：Add-Drop 起/止（端点各自成事件，止点具 deadline 语义）",
                    CalendarLexicon.TERM_MAP.get(m.group(1)), "adddrop",
                    "commences".equals(m.group(2)) ? "start" : "end", null, null);
            return;
        }
        if ((m = RE_EXAM.matcher(text)).find()) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域：考试区间（与同学期对行白名单合并为单区间事件；本行为其一半）",
                    CalendarLexicon.TERM_MAP.get(m.group(1)), "exam-period", "window", null,
                    "commences".equals(m.group(2)) ? "start" : "end");
            return;
        }
        if ((m = RE_REVISION.matcher(text)).find()) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域扩展：复习日窗口（与同行对合并；本行为其一半）",
                    CalendarLexicon.TERM_MAP.get(m.group(1)), "revision-days", "window", true,
                    "commences".equals(m.group(2)) ? "start" : "end");
            return;
        }
        if (RE_CONGREGATION.matcher(text).find()) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域：同届毕业典礼总体区间（跨月两行白名单合并）",
                    "AY", "congregation", "overall-window", null, null);
            return;
        }
        if ((m = RE_FINAL_SUBJECT.matcher(text)).find()) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域：成绩定稿（subject，独立于对外发布）",
                    CalendarLexicon.TERM_MAP.get(m.group(1)), "results-finalisation", "subject",
                    null, null);
            return;
        }
        if ((m = RE_FINAL_OVERALL.matcher(text)).find()) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域：成绩定稿（overall）",
                    CalendarLexicon.TERM_MAP.get(m.group(1)), "results-finalisation", "overall",
                    null, null);
            return;
        }
        if ((m = RE_ANNOUNCE.matcher(text)).find()) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.VERIFY, "校验：成绩对外发布权威在 cal-assessment-results（results-overall-release），本源不重复写",
                    CalendarLexicon.TERM_MAP.get(m.group(1)), "results-overall-release", "overall",
                    null, null);
            return;
        }
        if ((m = RE_AY_ENDS.matcher(text)).find()) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域：学年结束（本行同时是页面覆盖学年证据）",
                    "AY", "academic-year-end", "main", null, null);
            return;
        }
        if ((m = RE_GENERAL_HOLIDAY.matcher(text)).matches()
                && CalendarLexicon.HOLIDAY_CODES.containsKey(m.group(1))) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域：已识别假期（稳定语义码）",
                    "AY", "general-holiday", CalendarLexicon.HOLIDAY_CODES.get(m.group(1)),
                    null, null);
            return;
        }
        if ((m = RE_SUSPENSION.matcher(text)).matches()
                && CalendarLexicon.SUSPENSION_CODES.containsKey(m.group(1))) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域扩展：教学暂停日（页面明示停课，非 General holiday）",
                    "AY", "teaching-suspension", CalendarLexicon.SUSPENSION_CODES.get(m.group(1)),
                    true, null);
            return;
        }
        if (CalendarLexicon.HOLIDAY_CODES.containsKey(text)) {
            emitDated(out, ri, raw, dayCell, ctx, ay, ayEvidence,
                    Disposition.WRITE, "写域：已识别假期（裸标签，词表命中）",
                    "AY", "general-holiday", CalendarLexicon.HOLIDAY_CODES.get(text),
                    null, null);
            return;
        }
        out.add(new KeyDateCandidate(sourceKey(), "r" + ri, raw,
                Disposition.UNKNOWN, "词表/模式未命中：" + text));
    }

    /**
     * 带 dated() 组装的发射（转译 replay.py emit）：WRITE/VERIFY 须日期三级组装
     * 成功且星期列一致，否则该行落 UNKNOWN（整源退化的判据）；合并半行在候选上
     * 附加 half/halfDate 线索。domainExtension 三态：null=不变（默认 false）、
     * true/false=显式（已批扩展标记保留供审计）
     */
    private void emitDated(List<KeyDateCandidate> out, int ri, String raw, String dayCell,
                           Ctx ctx, String ay, String ayEvidence,
                           Disposition disposition, String reason,
                           String term, String eventCode, String slot,
                           Boolean domainExtension, String half) {
        Dated d = dated(dayCell, ctx);
        KeyDateCandidate c = new KeyDateCandidate(sourceKey(), "r" + ri, raw, disposition, reason);
        if (ay != null) {
            c.setAy(ay);
        }
        c.setAyEvidence(ayEvidence);
        c.setTerm(term);
        c.setEventCode(eventCode);
        c.setSlot(slot);
        c.setAudienceCode("all");
        if (Boolean.TRUE.equals(domainExtension)) {
            c.setDomainExtension(true);
        }
        if (disposition == Disposition.WRITE || disposition == Disposition.VERIFY) {
            if (d == null) {
                c.setDisposition(Disposition.UNKNOWN);
                c.setReason("日期三级组装失败或星期列矛盾（年/月上下文缺失/可疑或日列不可解析）");
                out.add(c);
                return;
            }
            c.setPrecision(d.precision());
            c.setDateStart(d.dateStart());
            c.setDateEnd(d.dateEnd());
            if (half != null) {
                c.setHalf(half);
                c.setHalfDate(d.dateStart());
            }
        }
        out.add(c);
    }

    /**
     * 日期三级组装（段头年+月+行内日列）+ 星期列一致性校验。上下文缺失/日列
     * 不可解析/星期矛盾 → null（调用方落 UNKNOWN）——绝无时钟或邻近兜底
     */
    private Dated dated(String dayCell, Ctx ctx) {
        if (ctx.year == null || ctx.month == null || !RE_DAY_CELL.matcher(dayCell).matches()) {
            return null;
        }
        int month = CalendarDates.MONTHS.get(ctx.month.toLowerCase());
        Matcher m = RE_DAY_RANGE.matcher(dayCell);
        try {
            if (m.matches()) {
                LocalDate start = LocalDate.of(ctx.year, month, Integer.parseInt(m.group(1)));
                LocalDate end = LocalDate.of(ctx.year, month, Integer.parseInt(m.group(2)));
                if (ctx.weekday != null && start.getDayOfWeek().getValue() - 1 != ctx.weekday) {
                    return null;
                }
                return new Dated("exact-range", start, end);
            }
            LocalDate d = LocalDate.of(ctx.year, month, Integer.parseInt(dayCell));
            if (ctx.weekday != null && d.getDayOfWeek().getValue() - 1 != ctx.weekday) {
                return null;
            }
            return new Dated("exact-day", d, null);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    /**
     * 行间上下文（年段头/月份头/当前行星期）
     */
    private static final class Ctx {
        Integer year;
        String month;
        Integer weekday;
    }

    private record Dated(String precision, LocalDate dateStart, LocalDate dateEnd) {
    }
}
