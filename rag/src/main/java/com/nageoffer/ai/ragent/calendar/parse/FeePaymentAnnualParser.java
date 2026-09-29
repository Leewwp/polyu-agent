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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #2 cal-fee-payment-annual 解析器（转译 replay.py parse_cfp）。
 *
 * <p>主表 Year|Month|Activities 三列，rowspan 扁平化后年/月仅在组首行的前置格
 * 出现——活动取行末格。括号对「[Notification: …; Payment Deadline: …] 标题」
 * 一格拆双事件（fee-notification-initial + fee-deadline-initial，语义槽区分
 * 首轮/剩余轮，不靠出现序号）；SID 领取=onwards 开放窗（受众码区分到期人群
 * 周期，实际领取日不入键）；开课日只校验（权威在 #1）。Remarks 尾注显式截除，
 * --- 空占位显式跳过。
 */
public class FeePaymentAnnualParser implements CalendarPageParser {

    private static final Pattern RE_REMAINING = Pattern.compile("Remaining fee after .*Add/Drop Period");
    private static final Pattern RE_SEMESTER = Pattern.compile("Semester (One|Two)");
    private static final Pattern RE_INITIAL = Pattern.compile("Payment of Tuition Fee for Current Students \\(Semester (One|Two)\\)");

    @Override
    public String sourceKey() {
        return "cal-fee-payment-annual";
    }

    @Override
    public List<KeyDateCandidate> parse(List<List<String>> rows, String pageText) {
        String ay = CalendarPageParser.pageAy(sourceKey(), pageText, null);
        String ayEvidence = ay != null
                ? "页面散文 '…Collection of Student Identity Card for Academic Year %s'".formatted(ay)
                : null;
        List<KeyDateCandidate> out = new ArrayList<>();
        for (int ri = 0; ri < rows.size(); ri++) {
            List<String> r = rows.get(ri);
            if (ri == 0 || r.isEmpty()) {
                continue;
            }
            String act = r.get(r.size() - 1).strip();
            String loc = "r" + ri + ":f0";
            if ("---".equals(act)) {
                out.add(new KeyDateCandidate(sourceKey(), loc, "---",
                        Disposition.KNOWN_SKIP, "空月份占位符 ---（显式排除规则）"));
                continue;
            }
            Matcher mb = CalendarLexicon.BRACKET_RE.matcher(act);
            if (mb.find()) {
                String notif = mb.group(1);
                String deadline = mb.group(2);
                String title = mb.group(3).strip();
                String[] subtypeTerm = feeSubtype(title);
                if (subtypeTerm == null) {
                    out.add(new KeyDateCandidate(sourceKey(), loc, act,
                            Disposition.UNKNOWN, "缴费活动文案无法映射已知阶段"));
                    continue;
                }
                String subtype = subtypeTerm[0];
                String term = subtypeTerm[1];
                emitFeeEvent(out, ri, "f0", "Notification", notif, "fee-notification", title, subtype, term, ay, ayEvidence);
                emitFeeEvent(out, ri, "f1", "Payment Deadline", deadline, "fee-deadline", title, subtype, term, ay, ayEvidence);
                continue;
            }
            Matcher ms = CalendarLexicon.SID_RE.matcher(act);
            if (ms.matches() && CalendarLexicon.SID_AUDIENCE.containsKey(ms.group(2))) {
                KeyDateCandidate c = new KeyDateCandidate(sourceKey(), loc, act, Disposition.WRITE,
                        "写域：SID 卡领取（到期人群→受众码区分轮次；实际领取日不入键）");
                c.setAy(ay);
                c.setTerm("AY");
                c.setEventCode("sid-card-collection");
                c.setAudienceCode(CalendarLexicon.SID_AUDIENCE.get(ms.group(2)));
                c.setSlot("window");
                c.setPrecision("onwards");
                CalendarDates.parseOnwards(ms.group(1) + " onwards")
                        .ifPresent(c::setDateStart);
                c.setAudienceText("For " + ms.group(2) + " only");
                c.setAyEvidence(ayEvidence);
                out.add(c);
                continue;
            }
            Matcher mc = CalendarLexicon.COMMENCE_RE.matcher(act);
            if (mc.matches()) {
                KeyDateCandidate c = new KeyDateCandidate(sourceKey(), loc, act, Disposition.VERIFY,
                        "校验：开课日权威在 cal-academic-calendar（semester-teaching/start），本源不写");
                c.setAy(mc.group(3));
                c.setTerm(CalendarLexicon.TERM_MAP.get(mc.group(2)));
                c.setEventCode("semester-teaching");
                c.setAudienceCode("all");
                c.setSlot("start");
                c.setPrecision("exact-day");
                CalendarDates.parseDay(mc.group(1)).ifPresent(c::setDateStart);
                c.setAyEvidence(ayEvidence);
                out.add(c);
                continue;
            }
            out.add(new KeyDateCandidate(sourceKey(), loc, act,
                    Disposition.UNKNOWN, "活动文案未命中任何规则"));
        }
        return out;
    }

    private void emitFeeEvent(List<KeyDateCandidate> out, int ri, String frag, String fragLabel,
                              String dateText, String code, String title,
                              String subtype, String term, String ay, String ayEvidence) {
        KeyDateCandidate c = new KeyDateCandidate(sourceKey(), "r" + ri + ":" + frag,
                fragLabel + ": " + dateText + " — " + title, Disposition.WRITE,
                "写域：缴费%s（%s 轮）".formatted(code.split("-")[1], subtype));
        c.setAy(ay);
        c.setTerm(term);
        c.setEventCode(code);
        c.setAudienceCode("current-students");
        c.setSlot(subtype);
        c.setPrecision("exact-day");
        CalendarDates.parseDay(dateText).ifPresent(c::setDateStart);
        c.setAudienceText("Current Students");
        c.setAyEvidence(ayEvidence);
        out.add(c);
    }

    /**
     * 缴费标题→（语义槽, 学期码）：remaining=Add/Drop 后剩余费轮 / summer=暑期 /
     * initial=首轮。同一 term 下 initial/remaining 是不同身份（语义槽），不靠
     * occ=1/2 区分；无法映射返回 null → UNKNOWN
     */
    static String[] feeSubtype(String title) {
        if (RE_REMAINING.matcher(title).find()) {
            Matcher m = RE_SEMESTER.matcher(title);
            if (m.find()) {
                return new String[]{"remaining", CalendarLexicon.TERM_MAP.get(m.group(0))};
            }
        }
        if (title.contains("(Summer Term)")) {
            return new String[]{"summer", "SU"};
        }
        Matcher m = RE_INITIAL.matcher(title);
        if (m.find()) {
            return new String[]{"initial", CalendarLexicon.TERM_MAP.get("Semester " + m.group(1))};
        }
        return null;
    }
}
