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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * #4 cal-exam-timetable 解析器（转译 replay.py parse_cet）——纯校验源，零事件
 * 写径（合同§2：考试区间与考试时间表发布窗的校验；不一致只产生 discrepancy
 * 记录，不得自动取得写权）。
 *
 * <p>主表：学期行 | 考试区间列 | 时间表发布窗列。值可为区间式或模糊窗式
 * （Middle/Mid 别名同桶）。
 */
public class ExamTimetableParser implements CalendarPageParser {

    /**
     * 学期行标签→学期码
     */
    private static final Map<String, String> TERM_ROWS = Map.of(
            "Semester One", "S1", "Semester Two", "S2", "Summer Term", "SU");

    @Override
    public String sourceKey() {
        return "cal-exam-timetable";
    }

    @Override
    public List<KeyDateCandidate> parse(List<List<String>> rows, String pageText) {
        String ay = CalendarPageParser.pageAy(sourceKey(), pageText, null);
        String ayEvidence = ay != null ? "表头单元格 'Academic Year %s'".formatted(ay) : null;
        List<KeyDateCandidate> out = new ArrayList<>();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            if (ri == 0 || r.isEmpty() || !TERM_ROWS.containsKey(r.get(0))) {
                continue;
            }
            String term = TERM_ROWS.get(r.get(0));
            verifyCell(out, ri, 1, r, term, "exam-period", "window",
                    "考试区间权威在 cal-academic-calendar", ay, ayEvidence);
            verifyCell(out, ri, 2, r, term, "exam-tt-release", "window",
                    "考试时间表发布窗权威在 cal-timetable-exam-results", ay, ayEvidence);
        }
        return out;
    }

    private void verifyCell(List<KeyDateCandidate> out, int ri, int ci, List<String> r, String term,
                            String code, String slot, String note, String ay, String ayEvidence) {
        String val = r.get(ci).strip();
        String raw = r.get(0) + " | " + val;
        String loc = "r" + ri + ":c" + ci;
        String fb = CalendarDates.fuzzyBucket(val);
        KeyDateCandidate c = new KeyDateCandidate(sourceKey(), loc, raw, Disposition.VERIFY,
                "校验：" + note);
        if (fb != null) {
            c.setPrecision("fuzzy");
            c.setFuzzyBucket(fb);
        } else {
            CalendarDates.ParsedDate p = CalendarDates.parseRangeOrDay(val);
            if (p == null) {
                out.add(new KeyDateCandidate(sourceKey(), loc, raw,
                        Disposition.UNKNOWN, "校验值不可解析"));
                return;
            }
            c.setPrecision(p.precision());
            c.setDateStart(p.dateStart());
            c.setDateEnd(p.dateEnd());
        }
        c.setAy(ay);
        c.setTerm(term);
        c.setEventCode(code);
        c.setAudienceCode("all");
        c.setSlot(slot);
        c.setAyEvidence(ayEvidence);
        out.add(c);
    }
}
