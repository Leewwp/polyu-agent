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
 * #5 cal-assessment-results 解析器（转译 replay.py parse_car）——成绩发布日
 * 权威写者：各学期科目成绩（subject）与总评成绩（overall）发布日期。两个
 * event_code 独立；定稿日期（#1 的 results-finalisation）与对外发布日期不能合并
 * （合同§3.1）。
 *
 * <p>fail-closed（#195 审核修正，防线 A）：表头行（r0）与空行之外的非空数据行
 * 学期标签未命中词表 → 该行落 UNKNOWN（整源退化）——与其余三源同口径（词表未
 * 命中不静默跳过，防「结构改版后 0 候选静默过门」）；数据列缺失同理落 UNKNOWN
 * 而非抛 IndexOutOfBounds。
 */
public class AssessmentResultsParser implements CalendarPageParser {

    /**
     * 学期行标签→学期码
     */
    private static final Map<String, String> TERM_ROWS = Map.of(
            "Semester One", "S1", "Semester Two", "S2", "Summer Term", "SU");

    @Override
    public String sourceKey() {
        return "cal-assessment-results";
    }

    @Override
    public List<KeyDateCandidate> parse(List<List<String>> rows, String pageText) {
        String ay = CalendarPageParser.pageAy(sourceKey(), pageText, null);
        String ayEvidence = ay != null ? "页面散文 'Academic Year %s'（紧邻主表前）".formatted(ay) : null;
        List<KeyDateCandidate> out = new ArrayList<>();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            if (ri == 0 || r.isEmpty()) {
                continue; // 表头行/空行合法跳过（#2/#3 先例同口径）
            }
            if (!TERM_ROWS.containsKey(r.get(0))) {
                out.add(new KeyDateCandidate(sourceKey(), "r" + ri, String.join(" | ", r),
                        Disposition.UNKNOWN, "学期行标签未命中词表：" + r.get(0)));
                continue;
            }
            String term = TERM_ROWS.get(r.get(0));
            emitRelease(out, ri, 1, r, term, "results-subject-release", "subject", "科目", ay, ayEvidence);
            emitRelease(out, ri, 2, r, term, "results-overall-release", "overall", "总评", ay, ayEvidence);
        }
        return out;
    }

    private void emitRelease(List<KeyDateCandidate> out, int ri, int ci, List<String> r, String term,
                             String code, String slot, String zh, String ay, String ayEvidence) {
        if (ci >= r.size()) {
            out.add(new KeyDateCandidate(sourceKey(), "r" + ri + ":c" + ci, String.join(" | ", r),
                    Disposition.UNKNOWN, "数据列缺失（行仅 " + r.size() + " 列，取不到第 " + ci + " 列）"));
            return;
        }
        KeyDateCandidate c = new KeyDateCandidate(sourceKey(), "r" + ri + ":c" + ci,
                r.get(0) + " | " + r.get(ci).strip(), Disposition.WRITE,
                "写域：%s成绩发布日".formatted(zh));
        c.setAy(ay);
        c.setTerm(term);
        c.setEventCode(code);
        c.setAudienceCode("all");
        c.setSlot(slot);
        c.setPrecision("exact-day");
        CalendarDates.parseDay(r.get(ci).strip()).ifPresent(c::setDateStart);
        c.setAyEvidence(ayEvidence);
        out.add(c);
    }
}
