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
import com.nageoffer.ai.ragent.calendar.parse.ExamTimetableParser;
import com.nageoffer.ai.ragent.calendar.parse.FeePaymentAnnualParser;
import com.nageoffer.ai.ragent.calendar.parse.SourceGate;
import com.nageoffer.ai.ragent.calendar.parse.TimetableExamResultsParser;
import com.nageoffer.ai.ragent.calendar.sync.KeyDateSourceStateMachine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #192 反例 K01–K12 复跑（票面验收第二条）：在 Java 实现上重放 #189 的反例
 * 变异，与 replay.py 反例执行记录逐条对齐。K01–K06/K08/K10/K11/K12（状态机
 * 面）为纯内存；K07/K09 的存储面（同 UID 改期/撤回/恢复、跨学年零撤回、退化
 * 零写、源级原子性）在真库门控 IT {@code KeyDateSyncPgIt} 复跑。
 */
class CalendarCounterCaseTests {

    private static final String SNAP_DIR = "/fixtures/calendar/snapshots/";

    private static String snap(String sourceKey) throws IOException {
        try (InputStream in = CalendarCounterCaseTests.class.getResourceAsStream(SNAP_DIR + sourceKey + ".html")) {
            assertNotNull(in, "fixture 缺失：" + sourceKey);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<List<String>> deepCopy(List<List<String>> rows) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> r : rows) {
            copy.add(new ArrayList<>(r));
        }
        return copy;
    }

    private static SourceGate.GateResult cfp(UnaryOperator<List<List<String>>> mutation) throws IOException {
        String html = snap("cal-fee-payment-annual");
        List<List<String>> rows = mutation.apply(deepCopy(CalendarHtmlTables.mainRows(html)));
        return SourceGate.evaluate(new FeePaymentAnnualParser(), rows, CalendarHtmlTables.visibleText(html));
    }

    private static long count(SourceGate.GateResult r, Disposition d) {
        return r.candidates().stream().filter(c -> c.getDisposition() == d).count();
    }

    private static String uid(KeyDateCandidate c) {
        return KeyDateUid.uidOf(c.getAy(), c.getTerm(), c.getEventCode(), c.getAudienceCode(), c.getSlot());
    }

    // --------------------------------------------------------------- K01

    /**
     * K01 同语义事件延期 60 天且 raw_text 日期改变 → UID 不变；不受任何天数阈值
     * 影响（身份数组无日期字段位——uidOf 签名只有五个身份参数）
     */
    @Test
    void k01Reschedule60DaysKeepsUid() throws IOException {
        SourceGate.GateResult r = cfp(main -> {
            int hits = 0;
            for (List<String> row : main) {
                for (int i = 0; i < row.size(); i++) {
                    if (row.get(i).contains("1 September 2026")) {
                        row.set(i, row.get(i).replace("1 September 2026", "31 October 2026"));
                        hits++;
                    }
                }
            }
            assertEquals(1, hits, "变异应恰好命中 1 处");
            return main;
        });
        KeyDateCandidate target = r.candidates().stream()
                .filter(c -> c.getDisposition() == Disposition.WRITE
                        && "fee-deadline".equals(c.getEventCode())
                        && "initial".equals(c.getSlot()) && "S1".equals(c.getTerm()))
                .findFirst().orElseThrow();
        String uidOld = KeyDateUid.uidOf("2026/27", "S1", "fee-deadline", "current-students", "initial");
        assertEquals(uidOld, uid(target), "延期 60 天后 UID 不变");
        assertEquals(LocalDate.of(2026, 10, 31), target.getDateStart());
        // 跨实现一致锚：与 replay.py K01 实际输出同值
        assertEquals("e477f88087fe6e6fff439db18d8ee1314295e43ff34a356f6a2025efc41fcb3c", uidOld);
    }

    // --------------------------------------------------------------- K02

    /**
     * K02 旧事件消失，30 天内插入另一事件 → 不因日期邻近继承旧 UID；
     * 无法区分/未知 → 整源退化
     */
    @Test
    void k02NearDateInsertNoUidInheritance() throws IOException {
        // 变体A：已知类别近日期插入（S1 initial 行删除，插入 S2 initial 行，截止相距 9 天）
        SourceGate.GateResult a = cfp(main -> {
            main.remove(1);
            main.add(1, List.of("", "", "[Notification: 20 August 2026; Payment Deadline: 10 September 2026] "
                    + "Payment of Tuition Fee for Current Students (Semester Two)"));
            return main;
        });
        String oldUid = KeyDateUid.uidOf("2026/27", "S1", "fee-deadline", "current-students", "initial");
        Set<String> newUids = new HashSet<>();
        a.candidates().stream().filter(c -> c.getDisposition() == Disposition.WRITE).forEach(c -> newUids.add(uid(c)));
        assertFalse(newUids.contains(oldUid), "旧 UID 不得被近日期新事件继承");
        assertTrue(newUids.contains(KeyDateUid.uidOf("2026/27", "S2", "fee-deadline", "current-students", "initial")),
                "新事件获得自身新 UID");
        assertEquals(9, Math.abs(LocalDate.of(2026, 9, 10).toEpochDay() - LocalDate.of(2026, 9, 1).toEpochDay()));

        // 变体B：未知类别近日期插入 → 未知=1 → 整源退化，不发布
        SourceGate.GateResult b = cfp(main -> {
            main.remove(1);
            main.add(1, List.of("", "", "29 August 2026 Payment of Miscellaneous Deposit (Special round)"));
            return main;
        });
        assertTrue(b.degraded(), "未知候选 → 整源退化");
        assertEquals(1, count(b, Disposition.UNKNOWN));
    }

    // --------------------------------------------------------------- K03

    /**
     * K03 S1 initial/remaining 重排 + 插入未知缴费类别 → 既有身份不变；
     * 未知类别不误撤回（退化轮零事件写——存储面在真库 IT）
     */
    @Test
    void k03ReorderKeepsIdentityUnknownDegrades() throws IOException {
        String html = snap("cal-fee-payment-annual");
        String pageText = CalendarHtmlTables.visibleText(html);
        SourceGate.GateResult base = SourceGate.evaluate(new FeePaymentAnnualParser(),
                CalendarHtmlTables.mainRows(html), pageText);

        SourceGate.GateResult swapped = cfp(main -> {
            List<String> r1 = main.get(1);
            main.set(1, main.get(4));
            main.set(4, r1);
            return main;
        });
        Set<String> baseUids = new HashSet<>();
        base.events().forEach(e -> baseUids.add(uid(e)));
        Set<String> swappedUids = new HashSet<>();
        swapped.events().forEach(e -> swappedUids.add(uid(e)));
        assertEquals(baseUids, swappedUids, "重排后 UID 集与基线全等（initial/remaining 是语义槽，不是顺序）");

        SourceGate.GateResult unknown = cfp(main -> {
            main.add(5, List.of("", "March", "[Notification: 5 March 2027; Payment Deadline: 20 March 2027] "
                    + "Payment of Tuition Fees (Outstanding balance)"));
            return main;
        });
        assertTrue(unknown.degraded(), "未知新类别 → 整源退化");
        assertEquals(1, count(unknown, Disposition.UNKNOWN));
    }

    // --------------------------------------------------------------- K04

    /**
     * K04 锚点仍齐但 1 条 deadline 无法归类 → 整源退化；其余候选仍正常解析
     * （锚点/词表命中不受影响）。存储面零变化在真库 IT
     */
    @Test
    void k04OneUnclassifiableDegradesWholeSource() throws IOException {
        SourceGate.GateResult r = cfp(main -> {
            main.get(1).set(2, "[Notification: 14 August 2026; Payment Deadline: ▮▮▮] "
                    + "Payment of Tuition Fee for Current Students (Semester One)");
            return main;
        });
        assertTrue(r.degraded(), "未知=1 → 整源退化");
        assertEquals(1, count(r, Disposition.UNKNOWN));
        assertTrue(count(r, Disposition.WRITE) >= 10, "其余缴费/SID 锚点仍命中");
        assertFalse(r.reasons().isEmpty());
    }

    // --------------------------------------------------------------- K05

    /**
     * K05 丢失月份头/年份段头，日期仍落宽泛年度范围 → 退化；不得仅凭范围检查
     * 发布（星期列校验+三级组装失败拦截——2026-09-29 裁决②的验收锚）
     */
    @Test
    void k05MissingMonthOrYearContextDegrades() throws IOException {
        String html = snap("cal-academic-calendar");
        String pageText = CalendarHtmlTables.visibleText(html);
        AcademicCalendarParser parser = new AcademicCalendarParser();

        SourceGate.GateResult noMonth = SourceGate.evaluate(parser, mutate(html, main -> {
            main.remove(4); // "September" 月份头
            return main;
        }), pageText);
        assertTrue(noMonth.degraded(), "缺月头 → 整源退化（尽管日期仍落学年窗内）");
        assertTrue(count(noMonth, Disposition.UNKNOWN) >= 2, "9 月行无法组装 → 未知");

        SourceGate.GateResult noYear = SourceGate.evaluate(parser, mutate(html, main -> {
            main.remove(26); // "2027" 年份段头
            return main;
        }), pageText);
        assertTrue(noYear.degraded(), "缺年段头 → 学年双证据失败 + 1 月起行无年 → 退化（无时钟兜底）");
        assertTrue(count(noYear, Disposition.UNKNOWN) >= 2);
        assertTrue(noYear.reasons().stream().anyMatch(s -> s.contains("学年")));
    }

    private static List<List<String>> mutate(String html, UnaryOperator<List<List<String>>> fn) {
        return fn.apply(deepCopy(CalendarHtmlTables.mainRows(html)));
    }

    // --------------------------------------------------------------- K06

    /**
     * K06 时钟跨年但页面不变 → UID/覆盖域不变：解析器为纯函数（签名无时钟），
     * 同输入两次解析逐候选一致；覆盖学年仍为页面声明的 2026/27（显示覆盖缺口
     * 而非猜新年）
     */
    @Test
    void k06ParsersAreClockFreeDeterministic() throws IOException {
        String html = snap("cal-academic-calendar");
        String pageText = CalendarHtmlTables.visibleText(html);
        AcademicCalendarParser parser = new AcademicCalendarParser();
        List<KeyDateCandidate> first = parser.parse(CalendarHtmlTables.mainRows(html), pageText);
        List<KeyDateCandidate> second = parser.parse(CalendarHtmlTables.mainRows(html), pageText);
        assertEquals(first, second, "页面不变则输出逐候选一致（时钟不可能参与——解析无时钟依赖）");
        assertEquals("2026/27", com.nageoffer.ai.ragent.calendar.parse.CalendarPageParser
                .pageAy("cal-academic-calendar", pageText, CalendarHtmlTables.mainRows(html)));
        // 五页全量同口径：输出对重复解析稳定
        for (String key : List.of("cal-fee-payment-annual", "cal-timetable-exam-results",
                "cal-exam-timetable", "cal-assessment-results")) {
            String h = snap(key);
            String pt = CalendarHtmlTables.visibleText(h);
            SourceGate.GateResult r1 = SourceGate.evaluate(parserFor(key), CalendarHtmlTables.mainRows(h), pt);
            SourceGate.GateResult r2 = SourceGate.evaluate(parserFor(key), CalendarHtmlTables.mainRows(h), pt);
            assertEquals(r1.candidates(), r2.candidates(), key + " 重复解析稳定");
        }
    }

    // --------------------------------------------------------------- K08

    /**
     * K08 一格多事件/括号对/跨月合并 → provenance 完整，事件数与类型正确
     */
    @Test
    void k08ProvenanceAndMergedRanges() throws IOException {
        Map<String, SourceGate.GateResult> all = runAllFive();
        List<KeyDateCandidate> cteJuly = all.get("cal-timetable-exam-results").candidates().stream()
                .filter(c -> c.getLocator().startsWith("r17:")).toList();
        assertEquals(3, cteJuly.size(), "cte r17 一格切 3 片段");
        assertEquals(List.of("VERIFY", "WRITE", "VERIFY"),
                cteJuly.stream().map(c -> c.getDisposition().name()).toList());
        assertEquals("class-tt-release", cteJuly.get(1).getEventCode());
        assertEquals("2027/28", cteJuly.get(1).getAy(), "July 发布的 S1 时间表目标学年=2027/28（页面证据推导）");

        List<KeyDateCandidate> cfpR1 = all.get("cal-fee-payment-annual").candidates().stream()
                .filter(c -> c.getLocator().startsWith("r1:")).toList();
        assertEquals(2, cfpR1.size(), "cfp r1 括号对 → 2 写事件");
        assertEquals(Set.of("fee-notification", "fee-deadline"),
                Set.copyOf(cfpR1.stream().map(KeyDateCandidate::getEventCode).toList()));
        assertTrue(cfpR1.stream().allMatch(c -> "initial".equals(c.getSlot())));

        KeyDateCandidate cong = all.get("cal-academic-calendar").events().stream()
                .filter(e -> "congregation".equals(e.getEventCode())).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 10, 31), cong.getDateStart());
        assertEquals(LocalDate.of(2026, 11, 21), cong.getDateEnd());
        assertEquals(List.of("r12", "r14"), cong.getProvenance(), "跨月两段合并保留出处映射");

        KeyDateCandidate examS2 = all.get("cal-academic-calendar").events().stream()
                .filter(e -> "exam-period".equals(e.getEventCode()) && "S2".equals(e.getTerm()))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2027, 4, 22), examS2.getDateStart(), "跨月区间 '22 April to 8 May 2027' 端月正确");
        assertEquals(LocalDate.of(2027, 5, 8), examS2.getDateEnd());
    }

    // --------------------------------------------------------------- K10

    /**
     * K10 规范序列化确定性 + SHA-256 恰 64 hex；日期/标题变动不改变身份
     * （含中文受众/冒号逗号长标签——与 replay.py K10 实际输出逐值一致）
     */
    @Test
    void k10CanonicalUidDeterministic64Hex() {
        Map<String[], String> cases = Map.of(
                new String[]{"2026/27", "S1", "fee-deadline", "current-students", "initial"},
                "e477f88087fe6e6fff439db18d8ee1314295e43ff34a356f6a2025efc41fcb3c",
                new String[]{"2026/27", "AY", "general-holiday", "非本地新生", "christmas-day"},
                "4204f27a185008f85d9a8c0b71bf3924b3f2c6f478f957f0c990f9f59d3901ed",
                new String[]{"2027/28", "S1", "class-tt-release", "all:cohort:2033", "window-with:colon,comma"},
                "42b8fcb9bfc73d33cb1401673e8dd0ceef1576995dbae51e47068a95f89a1893",
                new String[]{"2027/28", "S1", "semester-teaching", "all", "start"},
                "67bdb86d89ce368ca7c74491c702b9ad0224b3707bfb9ecd47c037a7f0cd26cd");
        for (Map.Entry<String[], String> e : cases.entrySet()) {
            String[] f = e.getKey();
            String canonical = KeyDateUid.canonicalIdentity(f[0], f[1], f[2], f[3], f[4]);
            assertFalse(canonical.contains(" "), "无空白");
            String u = KeyDateUid.uidOf(f[0], f[1], f[2], f[3], f[4]);
            assertEquals(64, u.length());
            assertTrue(u.matches("^[0-9a-f]{64}$"));
            assertEquals(e.getValue(), u, "与 Python 参考实现逐字节一致");
            assertEquals(u, KeyDateUid.uidOf(f[0], f[1], f[2], f[3], f[4]), "同输入同 uid");
        }
        // 数组顺序固定（version, ay, term, event_code, audience, slot）
        assertEquals("[\"polyu-keydate-v1\",\"2026/27\",\"S1\",\"fee-deadline\",\"current-students\",\"initial\"]",
                KeyDateUid.canonicalIdentity("2026/27", "S1", "fee-deadline", "current-students", "initial"));
        // 改日期不换 UID（构造上无日期位）+ 跨学年新 UID
        String s1 = KeyDateUid.uidOf("2026/27", "S1", "semester-teaching", "all", "start");
        assertEquals(s1, KeyDateUid.uidOf("2026/27", "S1", "semester-teaching", "all", "start"));
        assertNotEquals(s1, KeyDateUid.uidOf("2027/28", "S1", "semester-teaching", "all", "start"),
                "跨学年=新 UID");
    }

    // --------------------------------------------------------------- K11

    /**
     * K11 校验源与写者冲突 → 只 discrepancy 告警；校验源零事件写径；
     * 权威值保持（存储面在真库 IT，这里验解析面 crossVerify）
     */
    @Test
    void k11VerifierConflictOnlyWarns() throws IOException {
        Map<String, SourceGate.GateResult> all = runAllFive();
        String html = snap("cal-exam-timetable");
        String pageText = CalendarHtmlTables.visibleText(html);
        SourceGate.GateResult tampered = SourceGate.evaluate(new ExamTimetableParser(),
                mutate(html, main -> {
                    main.get(1).set(1, "4 to 19 December 2026"); // 与权威 3–18 冲突
                    return main;
                }), pageText);
        all.put("cal-exam-timetable", tampered);
        List<String[]> disc = SourceGate.crossVerify(all);
        assertTrue(disc.size() >= 1, "产生 discrepancy（记录双方值），仅告警");
        assertTrue(disc.stream().anyMatch(d -> "cal-exam-timetable".equals(d[0]) && "exam-period".equals(d[2])),
                "exam-period/S1 discrepancy 记录在案");
        assertTrue(tampered.events().isEmpty(), "校验源（含被篡改后）写事件数恒为 0——零写径");
        KeyDateCandidate examS1 = all.get("cal-academic-calendar").events().stream()
                .filter(e -> "exam-period".equals(e.getEventCode()) && "S1".equals(e.getTerm()))
                .findFirst().orElseThrow();
        assertEquals(LocalDate.of(2026, 12, 3), examS1.getDateStart(), "权威值保持，不被校验源覆盖");
        assertEquals(LocalDate.of(2026, 12, 18), examS1.getDateEnd());
    }

    // --------------------------------------------------------------- K12（状态机面）

    /**
     * K12 自动隔离两次完整探测可恢复；人工停用不复活；哈希长期不变不判死。
     * 退化轮零撤回（存储面）在真库 IT
     */
    @Test
    void k12IsolationRecoveryManualStays() {
        KeyDateSourceStateMachine st = new KeyDateSourceStateMachine();
        List<String> seq = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            seq.add(st.onParse(true).name());
        }
        assertEquals(List.of("ACTIVE", "ACTIVE", "AUTO_ISOLATED"), seq, "连续 3 轮退化 → auto_isolated");
        assertEquals(KeyDateSourceStateMachine.Mode.AUTO_ISOLATED, st.onParse(false), "第 1 轮完整探测仍隔离");
        assertEquals(KeyDateSourceStateMachine.Mode.ACTIVE, st.onParse(false), "第 2 轮完整探测恢复 active");

        KeyDateSourceStateMachine manual = new KeyDateSourceStateMachine(
                KeyDateSourceStateMachine.Mode.MANUAL_DISABLED, 0, 0);
        for (int i = 0; i < 5; i++) {
            assertEquals(KeyDateSourceStateMachine.Mode.MANUAL_DISABLED, manual.onParse(false),
                    "manual_disabled 即使连续完整也不自动复活");
        }

        KeyDateSourceStateMachine hash = new KeyDateSourceStateMachine();
        for (int i = 0; i < 30; i++) {
            hash.onParse(false);
        }
        assertEquals(KeyDateSourceStateMachine.Mode.ACTIVE, hash.getMode(),
                "哈希 30 天不变 → 仍 active（年度表常态，不判死）");
    }

    // --------------------------------------------------------------- 共用

    private static Map<String, SourceGate.GateResult> runAllFive() throws IOException {
        java.util.Map<String, SourceGate.GateResult> res = new java.util.LinkedHashMap<>();
        for (String key : List.of("cal-academic-calendar", "cal-fee-payment-annual",
                "cal-timetable-exam-results", "cal-exam-timetable", "cal-assessment-results")) {
            String html = snap(key);
            res.put(key, SourceGate.evaluate(parserFor(key),
                    CalendarHtmlTables.mainRows(html), CalendarHtmlTables.visibleText(html)));
        }
        return res;
    }

    private static com.nageoffer.ai.ragent.calendar.parse.CalendarPageParser parserFor(String key) {
        return switch (key) {
            case "cal-academic-calendar" -> new AcademicCalendarParser();
            case "cal-fee-payment-annual" -> new FeePaymentAnnualParser();
            case "cal-timetable-exam-results" -> new TimetableExamResultsParser();
            case "cal-exam-timetable" -> new ExamTimetableParser();
            case "cal-assessment-results" -> new AssessmentResultsParser();
            default -> throw new IllegalArgumentException(key);
        };
    }
}
