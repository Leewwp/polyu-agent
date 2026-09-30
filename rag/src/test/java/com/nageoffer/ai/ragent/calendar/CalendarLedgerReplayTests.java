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

package com.nageoffer.ai.ragent.calendar;

import com.nageoffer.ai.ragent.calendar.model.Disposition;
import com.nageoffer.ai.ragent.calendar.model.KeyDateCandidate;
import com.nageoffer.ai.ragent.calendar.parse.AcademicCalendarParser;
import com.nageoffer.ai.ragent.calendar.parse.AssessmentResultsParser;
import com.nageoffer.ai.ragent.calendar.parse.CalendarHtmlTables;
import com.nageoffer.ai.ragent.calendar.parse.CalendarPageParser;
import com.nageoffer.ai.ragent.calendar.parse.ExamTimetableParser;
import com.nageoffer.ai.ragent.calendar.parse.FeePaymentAnnualParser;
import com.nageoffer.ai.ragent.calendar.parse.SourceGate;
import com.nageoffer.ai.ragent.calendar.parse.TimetableExamResultsParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #192 离线对账（票面验收第一条）：Java 解析器在 r3 五页快照上精确复现期望账本
 * 113 片段 = 74 写事件（严格写域 65 + 已批扩展 9）+ 24 校验 + 9 跳过 + 0 未知，
 * 精度分布 exact-day 58 / exact-range 7 / onwards 3 / fuzzy 6；五源零退化；
 * 跨源校验零 discrepancy。
 *
 * <p>机械核对字段与 replay.py CHECK_FIELDS 一致（去向与身份逐字段；reason/
 * raw_text/audience_text/ay_evidence 为人工撰写字段不核对）。快照与账本来自
 * #189 r3 执行产物（fixtures 拷贝自 research/campus-dates-contracts，来源与
 * 哈希见该目录 README/fetch-log）。
 */
class CalendarLedgerReplayTests {

    private static final String SNAP_DIR = "/fixtures/calendar/snapshots/";
    private static final String LEDGER = "/fixtures/calendar/ledger/期望账本.csv";

    private static final Map<String, CalendarPageParser> PARSERS = new LinkedHashMap<>();

    static {
        PARSERS.put("cal-academic-calendar", new AcademicCalendarParser());
        PARSERS.put("cal-fee-payment-annual", new FeePaymentAnnualParser());
        PARSERS.put("cal-timetable-exam-results", new TimetableExamResultsParser());
        PARSERS.put("cal-exam-timetable", new ExamTimetableParser());
        PARSERS.put("cal-assessment-results", new AssessmentResultsParser());
    }

    private static String loadFixture(String path) throws IOException {
        try (InputStream in = CalendarLedgerReplayTests.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("fixture 缺失：" + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 极简 CSV 读取（双引号包裹+引号转义；账本由 replay.py csv.DictReader 口径生成）
     */
    private static List<Map<String, String>> readCsv(String content) {
        List<Map<String, String>> rows = new ArrayList<>();
        List<String> header = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        List<String> line = new ArrayList<>();
        boolean inQuotes = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                line.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n') {
                line.add(cell.toString());
                cell.setLength(0);
                if (!line.stream().allMatch(String::isEmpty)) {
                    if (header.isEmpty()) {
                        header.addAll(line);
                    } else {
                        Map<String, String> row = new HashMap<>();
                        for (int c = 0; c < header.size(); c++) {
                            row.put(header.get(c), c < line.size() ? line.get(c) : "");
                        }
                        rows.add(row);
                    }
                }
                line.clear();
            } else if (ch != '\r') {
                cell.append(ch);
            }
        }
        if (!line.isEmpty() || cell.length() > 0) {
            line.add(cell.toString());
            if (!line.stream().allMatch(String::isEmpty) && !header.isEmpty()) {
                Map<String, String> row = new HashMap<>();
                for (int c = 0; c < header.size(); c++) {
                    row.put(header.get(c), c < line.size() ? line.get(c) : "");
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, SourceGate.GateResult> runAll() throws IOException {
        Map<String, SourceGate.GateResult> res = new LinkedHashMap<>();
        for (Map.Entry<String, CalendarPageParser> e : PARSERS.entrySet()) {
            String html = loadFixture(SNAP_DIR + e.getKey() + ".html");
            res.put(e.getKey(), SourceGate.evaluate(e.getValue(),
                    CalendarHtmlTables.mainRows(html), CalendarHtmlTables.visibleText(html)));
        }
        return res;
    }

    @Test
    void ledgerExactReplay74Write24Verify9Skip0Unknown() throws IOException {
        Map<String, SourceGate.GateResult> res = runAll();
        List<Map<String, String>> ledger = readCsv(loadFixture(LEDGER));
        assertEquals(113, ledger.size(), "账本行数");

        Map<String, Map<String, String>> byLoc = new TreeMap<>();
        for (Map<String, String> row : ledger) {
            byLoc.put(row.get("source_key") + ":" + row.get("locator"), row);
        }
        Map<String, KeyDateCandidate> parsed = new TreeMap<>();
        res.forEach((k, r) -> r.candidates().forEach(c -> parsed.put(k + ":" + c.getLocator(), c)));
        assertEquals(byLoc.keySet(), parsed.keySet(), "片段集合（源:locator）须与账本逐一对齐");

        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> e : byLoc.entrySet()) {
            Map<String, String> row = e.getValue();
            KeyDateCandidate c = parsed.get(e.getKey());
            assertEqualsString(problems, e.getKey(), "disposition", row.get("disposition"),
                    c.getDisposition().name());
            assertEqualsString(problems, e.getKey(), "event_code", row.get("event_code"), c.getEventCode());
            assertEqualsString(problems, e.getKey(), "ay", row.get("ay"), c.getAy());
            assertEqualsString(problems, e.getKey(), "term", row.get("term"), c.getTerm());
            assertEqualsString(problems, e.getKey(), "audience_code", row.get("audience_code"), c.getAudienceCode());
            assertEqualsString(problems, e.getKey(), "slot", row.get("slot"), c.getSlot());
            assertEqualsString(problems, e.getKey(), "precision", row.get("precision"), c.getPrecision());
            assertEqualsString(problems, e.getKey(), "date_start", row.get("date_start"),
                    c.getDateStart() == null ? null : c.getDateStart().toString());
            assertEqualsString(problems, e.getKey(), "date_end", row.get("date_end"),
                    c.getDateEnd() == null ? null : c.getDateEnd().toString());
            assertEqualsString(problems, e.getKey(), "fuzzy_bucket", row.get("fuzzy_bucket"), c.getFuzzyBucket());
            assertEqualsString(problems, e.getKey(), "domain_extension", row.get("domain_extension"),
                    String.valueOf(c.isDomainExtension()));
        }
        assertTrue(problems.isEmpty(), "字段不一致 " + problems.size() + " 处：\n" + String.join("\n", problems));

        Map<Disposition, Long> frags = new HashMap<>();
        res.values().forEach(r -> r.candidates()
                .forEach(c -> frags.merge(c.getDisposition(), 1L, Long::sum)));
        assertEquals(80L, frags.getOrDefault(Disposition.WRITE, 0L));
        assertEquals(24L, frags.getOrDefault(Disposition.VERIFY, 0L));
        assertEquals(9L, frags.getOrDefault(Disposition.KNOWN_SKIP, 0L));
        assertEquals(0L, frags.getOrDefault(Disposition.UNKNOWN, 0L));

        List<KeyDateCandidate> events = res.values().stream().flatMap(r -> r.events().stream()).toList();
        assertEquals(74, events.size(), "写事件总数");
        assertEquals(9, events.stream().filter(KeyDateCandidate::isDomainExtension).count(), "已批扩展事件");
        assertEquals(58, events.stream().filter(e -> "exact-day".equals(e.getPrecision())).count());
        assertEquals(7, events.stream().filter(e -> "exact-range".equals(e.getPrecision())).count());
        assertEquals(3, events.stream().filter(e -> "onwards".equals(e.getPrecision())).count());
        assertEquals(6, events.stream().filter(e -> "fuzzy".equals(e.getPrecision())).count());

        // 对账输出（票面验收：打印对账输出留 PR 描述）——与 replay.py --verify 口径一致
        System.out.println("== #192 Java 离线回放：账本核对（r3 快照 × 期望账本）==\n");
        System.out.println("片段总数（五页机械枚举）：" + res.values().stream().mapToInt(r -> r.candidates().size()).sum());
        System.out.println("片段去向：" + frags);
        System.out.println("写事件：" + events.size() + "（严格写域 "
                + (events.size() - events.stream().filter(KeyDateCandidate::isDomainExtension).count())
                + " + 已批扩展 " + events.stream().filter(KeyDateCandidate::isDomainExtension).count() + "）");
        System.out.println("精度分布：exact-day "
                + events.stream().filter(e -> "exact-day".equals(e.getPrecision())).count()
                + " / exact-range " + events.stream().filter(e -> "exact-range".equals(e.getPrecision())).count()
                + " / onwards " + events.stream().filter(e -> "onwards".equals(e.getPrecision())).count()
                + " / fuzzy " + events.stream().filter(e -> "fuzzy".equals(e.getPrecision())).count());
        res.forEach((key, r) -> System.out.println(String.format("  %-28s 片段=%d 写事件=%d 校验=%d 跳过=%d 未知=%d 退化=%s",
                key, r.candidates().size(), r.events().size(),
                r.candidates().stream().filter(c -> c.getDisposition() == Disposition.VERIFY).count(),
                r.candidates().stream().filter(c -> c.getDisposition() == Disposition.KNOWN_SKIP).count(),
                r.candidates().stream().filter(c -> c.getDisposition() == Disposition.UNKNOWN).count(),
                r.degraded())));
        System.out.println("账本不一致：0（113 片段逐字段对齐 CHECK_FIELDS）");
    }

    @Test
    void noSourceDegradedAndCrossVerifyClean() throws IOException {
        Map<String, SourceGate.GateResult> res = runAll();
        res.forEach((key, r) -> assertTrue(!r.degraded(),
                key + " 不应退化：" + r.reasons()));
        List<String[]> disc = SourceGate.crossVerify(res);
        assertTrue(disc.isEmpty(), "跨源校验 discrepancy：" + disc.stream()
                .map(d -> String.join("|", d)).toList());
    }

    /**
     * 逐片段 UID 唯一性：合并半行（账本中同身份两行）之外的写片段身份不重复；
     * UID 均为 64hex
     */
    @Test
    void writeFragmentUidsWellFormed() throws IOException {
        Map<String, SourceGate.GateResult> res = runAll();
        Map<String, Integer> seen = new HashMap<>();
        for (SourceGate.GateResult r : res.values()) {
            for (KeyDateCandidate c : r.candidates()) {
                if (c.getDisposition() != Disposition.WRITE) {
                    continue;
                }
                String uid = KeyDateUid.uidOf(c.getAy(), c.getTerm(), c.getEventCode(),
                        c.getAudienceCode(), c.getSlot());
                assertTrue(uid.matches("^[0-9a-f]{64}$"));
                if (!KeyDateCandidate.mergeable(c.getEventCode())) {
                    seen.merge(uid, 1, Integer::sum);
                }
            }
        }
        List<String> dup = seen.entrySet().stream()
                .filter(e -> e.getValue() > 1).map(e -> e.getKey()).toList();
        assertTrue(dup.isEmpty(), "非合并写片段出现重复身份：" + dup);
    }

    private static void assertEqualsString(List<String> problems, String where, String field,
                                           String ledger, String actual) {
        String lv = ledger == null ? "" : ledger.strip();
        String av = actual == null ? "" : actual;
        if ("domain_extension".equals(field)) {
            lv = "true".equalsIgnoreCase(lv) || "1".equals(lv) ? "true" : "false";
        }
        if (!lv.equals(av)) {
            problems.add("%s %s：账本=%s 解析=%s".formatted(where, field, lv, av));
        }
    }
}
