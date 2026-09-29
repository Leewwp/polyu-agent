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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateSourceDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateMapper;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateSourceMapper;
import com.nageoffer.ai.ragent.calendar.model.Disposition;
import com.nageoffer.ai.ragent.calendar.model.KeyDateCandidate;
import com.nageoffer.ai.ragent.calendar.parse.AcademicCalendarParser;
import com.nageoffer.ai.ragent.calendar.parse.CalendarDates;
import com.nageoffer.ai.ragent.calendar.parse.CalendarHtmlTables;
import com.nageoffer.ai.ragent.calendar.parse.ExamTimetableParser;
import com.nageoffer.ai.ragent.calendar.parse.FeePaymentAnnualParser;
import com.nageoffer.ai.ragent.calendar.parse.SourceGate;
import com.nageoffer.ai.ragent.calendar.sync.KeyDateSyncService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #192 校历同步真库实证（票面验收：源级原子性/退化保留最后完整版本/撤回门/
 * 隔离复归 各有测试；K07/K09 的存储面在此复跑）。mock 单测证明不了的部分：
 * 源级原子发布事务（upsert+限定域撤回+源行同事务）、退化零事件写（last_
 * complete_snapshot/last_success_at 不刷新）、事务回滚零残留、verifier 零写径、
 * 状态机持久化——在本地 polyu-pg 端到端跑真 SQL。
 *
 * <p>门控：CI 无 PG 不跑（默认跳过）；本地执行=本地栈 polyu-pg 起着且已应用
 * {@code 260930_key_date_ingest.sql} 后 {@code ./mvnw -pl rag test
 * -Dtest=KeyDateSyncPgIt -Dpolyu.pg.it=1}（-Dpolyu.pg.it.url/user/pass 可覆盖）。
 * 两表为本票新表无外部数据，每用例前全清自净。
 */
@EnabledIfSystemProperty(named = "polyu.pg.it", matches = "1")
class KeyDateSyncPgIt {

    private static final String SNAP_DIR = "/fixtures/calendar/snapshots/";

    private static SqlSessionFactory sqlSessionFactory;
    private static KeyDateMapper keyDateMapper;
    private static KeyDateSourceMapper sourceMapper;
    private static KeyDateSyncService syncService;
    private static KeyDateDO proofRow;

    @BeforeAll
    static void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getProperty("polyu.pg.it.url", "jdbc:postgresql://127.0.0.1:5434/ragent"));
        dataSource.setUser(System.getProperty("polyu.pg.it.user", "postgres"));
        dataSource.setPassword(System.getProperty("polyu.pg.it.pass", "postgres"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        // SpringManagedTransactionFactory：mapper 连接参与 TransactionTemplate 事务
        // （与生产同一路径；JdbcTransactionFactory 下事务模板回滚不覆盖 mapper 连接）
        configuration.setEnvironment(new Environment("calendar-it", new SpringManagedTransactionFactory(), dataSource));
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace(KeyDateMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, KeyDateDO.class);
        TableInfoHelper.initTableInfo(assistant, KeyDateSourceDO.class);
        configuration.addMapper(KeyDateMapper.class);
        configuration.addMapper(KeyDateSourceMapper.class);
        sqlSessionFactory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        // SqlSessionTemplate：每次 mapper 调用新开 SqlSession（事务内绑定 Spring 管理连接）；
        // 手动长活 session 会缓存已提交关闭的连接（SpringManagedTransaction 复用判例）
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        keyDateMapper = sessionTemplate.getMapper(KeyDateMapper.class);
        sourceMapper = sessionTemplate.getMapper(KeyDateSourceMapper.class);
        // 固定时钟：last_success_at 断言不受真实时间影响；事务模板与生产同一路径
        Clock fixed = Clock.fixed(Instant.parse("2027-08-15T00:00:00Z"), ZoneId.of("UTC"));
        TransactionTemplate txTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        syncService = new KeyDateSyncService(keyDateMapper, sourceMapper, txTemplate, fixed);
    }

    @AfterAll
    static void tearDown() {
        // SqlSessionTemplate 无需手动关闭
    }

    @AfterEach
    void cleanTables() {
        keyDateMapper.delete(null);
        sourceMapper.delete(null);
    }

    private static String snap(String sourceKey) throws IOException {
        try (InputStream in = KeyDateSyncPgIt.class.getResourceAsStream(SNAP_DIR + sourceKey + ".html")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<List<String>> rowsOf(String sourceKey, UnaryOperator<List<List<String>>> mutation)
            throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (List<String> r : CalendarHtmlTables.mainRows(snap(sourceKey))) {
            rows.add(new ArrayList<>(r));
        }
        return mutation.apply(rows);
    }

    private static SourceGate.GateResult gate(String sourceKey, UnaryOperator<List<List<String>>> mutation)
            throws IOException {
        String html = snap(sourceKey);
        return SourceGate.evaluate(parserFor(sourceKey), rowsOf(sourceKey, mutation),
                CalendarHtmlTables.visibleText(html));
    }

    private static SourceGate.GateResult gate(String sourceKey) throws IOException {
        return gate(sourceKey, UnaryOperator.identity());
    }

    private static com.nageoffer.ai.ragent.calendar.parse.CalendarPageParser parserFor(String key) {
        return switch (key) {
            case "cal-academic-calendar" -> new AcademicCalendarParser();
            case "cal-fee-payment-annual" -> new FeePaymentAnnualParser();
            case "cal-exam-timetable" -> new ExamTimetableParser();
            case "cal-timetable-exam-results" -> new com.nageoffer.ai.ragent.calendar.parse.TimetableExamResultsParser();
            case "cal-assessment-results" -> new com.nageoffer.ai.ragent.calendar.parse.AssessmentResultsParser();
            default -> throw new IllegalArgumentException(key);
        };
    }

    private static KeyDateSourceDO source(String key, String role) {
        return KeyDateSourceDO.builder()
                .sourceKey(key)
                .sourceUrl(CalendarLexiconUrls.url(key))
                .role(role)
                .enabled("1")
                .autoState("active")
                .degradedStreak(0)
                .probeOkStreak(0)
                .build();
    }

    private static KeyDateDO byUid(String uid) {
        return keyDateMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KeyDateDO>()
                .eq(KeyDateDO::getUid, uid));
    }

    private static String uid(KeyDateCandidate c) {
        return KeyDateUid.uidOf(c.getAy(), c.getTerm(), c.getEventCode(), c.getAudienceCode(), c.getSlot());
    }

    /**
     * K07 存储面：页面换学年（2026/27 表整体+1 年、星期列随新日期重算）→
     * 新学年新 UID（零交集）；旧学年 0 条撤回；未到期旧事件保持 published 不归档
     */
    @Test
    void k07CrossYearNewUidsZeroWithdrawalOfOldYear() throws IOException {
        KeyDateSourceDO cac = source("cal-academic-calendar", "writer");
        syncService.applyComplete(cac, "2026/27", gate("cal-academic-calendar").events());
        Set<String> oldUids = new HashSet<>(keyDateMapper.selectList(null).stream().map(KeyDateDO::getUid).toList());

        String html = snap("cal-academic-calendar");
        String pageText = CalendarHtmlTables.visibleText(html).replace("Academic Year 2026/27", "Academic Year 2027/28");
        List<List<String>> rows = shiftOneYear(rowsOf("cal-academic-calendar", UnaryOperator.identity()));
        SourceGate.GateResult nextGate = SourceGate.evaluate(new AcademicCalendarParser(), rows, pageText);
        assertTrue(!nextGate.degraded(), "换版页面解析完整：" + nextGate.reasons());
        syncService.applyComplete(cac, "2027/28", nextGate.events());

        List<KeyDateDO> all = keyDateMapper.selectList(null);
        Set<String> newUids = new HashSet<>();
        for (KeyDateDO rec : all) {
            if (!oldUids.contains(rec.getUid())) {
                newUids.add(rec.getUid());
            }
        }
        assertEquals(nextGate.events().size(), newUids.size(), "跨学年=新事件新 UID");
        for (KeyDateCandidate e : nextGate.events()) {
            assertTrue(newUids.contains(uid(e)));
        }
        assertEquals(0, all.stream().filter(r -> "withdrawn".equals(r.getStatus())).count(),
                "旧学年 0 条撤回（页面换年不撤回旧学年）");
        // 未到期旧事件（2027-08-29 学年结束 > 时钟 2027-08-15）保持 published 不归档
        KeyDateDO ayeOld = byUid(KeyDateUid.uidOf("2026/27", "AY", "academic-year-end", "all", "main"));
        assertNotNull(ayeOld);
        assertEquals("published", ayeOld.getStatus(), "未到期旧事件不误归档");
        assertTrue(ayeOld.getDateStart().isAfter(LocalDate.of(2027, 8, 15)));
    }

    /**
     * 页面整体 +1 年变异（年段头/学年标注替换、星期列按新日期重算——模拟真实
     * 换版，避免留旧星期造成自相矛盾页）
     */
    private static List<List<String>> shiftOneYear(List<List<String>> main) {
        Integer ctxYear = null;
        String ctxMonth = null;
        for (List<String> r : main) {
            for (int i = 0; i < r.size(); i++) {
                String cell = r.get(i);
                if (cell.matches("^2026$")) {
                    r.set(i, "2027");
                } else if (cell.matches("^2027$")) {
                    r.set(i, "2028");
                } else if (cell.contains("2026/27")) {
                    r.set(i, cell.replace("2026/27", "2027/28"));
                }
            }
        }
        for (List<String> r : main) {
            if (r.size() == 1 && r.get(0).matches("^\\d{4}$")) {
                ctxYear = Integer.parseInt(r.get(0));
                ctxMonth = null;
                continue;
            }
            if (r.size() == 1 && monthNames().contains(r.get(0).toLowerCase())) {
                ctxMonth = r.get(0);
                continue;
            }
            if (r.size() == 3 && ctxYear != null && ctxMonth != null && r.get(0).matches("^\\d{1,2}(-\\d{1,2})?$")) {
                int day = Integer.parseInt(r.get(0).split("-")[0]);
                LocalDate d = LocalDate.of(ctxYear, monthNo(ctxMonth), day);
                r.set(1, weekdayName(d));
            }
        }
        return main;
    }

    private static Set<String> monthNames() {
        return Set.of("january", "february", "march", "april", "may", "june",
                "july", "august", "september", "october", "november", "december");
    }

    private static int monthNo(String month) {
        return switch (month.toLowerCase()) {
            case "january" -> 1; case "february" -> 2; case "march" -> 3;
            case "april" -> 4; case "may" -> 5; case "june" -> 6; case "july" -> 7;
            case "august" -> 8; case "september" -> 9; case "october" -> 10;
            case "november" -> 11; default -> 12;
        };
    }

    private static String weekdayName(LocalDate d) {
        return switch (d.getDayOfWeek()) {
            case MONDAY -> "Monday";
            case TUESDAY -> "Tuesday";
            case WEDNESDAY -> "Wednesday";
            case THURSDAY -> "Thursday";
            case FRIDAY -> "Friday";
            case SATURDAY -> "Saturday";
            case SUNDAY -> "Sunday";
        };
    }

    /**
     * K09 存储面：同 UID 四步 published(rev1)→改期(rev2)→withdrawn(rev3)→
     * restored(rev4)；改期后日期=2026-09-04；精确→模糊变异整源退化（零写）
     */
    @Test
    void k09SameUidRescheduleWithdrawRestore() throws IOException {
        KeyDateSourceDO cfp = source("cal-fee-payment-annual", "writer");
        SourceGate.GateResult base = gate("cal-fee-payment-annual");
        SourceGate.GateResult rescheduled = gate("cal-fee-payment-annual", main -> replaceAll(main,
                "1 September 2026", "4 September 2026"));
        SourceGate.GateResult removed = gate("cal-fee-payment-annual", main -> {
            main.remove(1);
            return main;
        });
        syncService.applyComplete(cfp, "2026/27", base.events());
        String u = KeyDateUid.uidOf("2026/27", "S1", "fee-deadline", "current-students", "initial");
        KeyDateDO rec = byUid(u);
        assertEquals(1, rec.getRevision());
        assertEquals("published", rec.getStatus());

        syncService.applyComplete(cfp, "2026/27", rescheduled.events());
        rec = byUid(u);
        assertEquals(2, rec.getRevision(), "改期=同 UID 修订");
        assertEquals(LocalDate.of(2026, 9, 4), rec.getDateStart());

        syncService.applyComplete(cfp, "2026/27", removed.events());
        rec = byUid(u);
        assertEquals(3, rec.getRevision(), "撤回=rev 递增且保留 UID 与历史");
        assertEquals("withdrawn", rec.getStatus());
        assertNotNull(rec.getWithdrawnAt());

        syncService.applyComplete(cfp, "2026/27", rescheduled.events());
        rec = byUid(u);
        assertEquals(4, rec.getRevision(), "恢复=同 UID 再修订");
        assertEquals("published", rec.getStatus());
        assertNull(rec.getWithdrawnAt());
        assertEquals(LocalDate.of(2026, 9, 4), rec.getDateStart());

        // 精确→模糊：日列模糊化后整源退化——旧精确值不得被覆盖（保留最后完整版本）
        SourceGate.GateResult fuzzy = gate("cal-academic-calendar", main -> {
            for (List<String> r : main) {
                if (r.size() == 3 && r.get(2).startsWith("Add/Drop period for Semester One ends")) {
                    r.set(0, "Late September");
                }
            }
            return main;
        });
        assertTrue(fuzzy.degraded(), "精确→模糊变异 → 整源退化");
        KeyDateSourceDO cac = source("cal-academic-calendar", "writer");
        syncService.applyComplete(cac, "2026/27", gate("cal-academic-calendar").events());
        KeyDateDO adddropEnd = byUid(KeyDateUid.uidOf("2026/27", "S1", "adddrop", "all", "end"));
        LocalDate before = adddropEnd.getDateStart();
        syncService.applyDegraded(cac, fuzzy.reasons());
        assertEquals(before, byUid(KeyDateUid.uidOf("2026/27", "S1", "adddrop", "all", "end")).getDateStart(),
                "退化轮零事件写——旧精确值保留，不得再发布");
    }

    private static List<List<String>> replaceAll(List<List<String>> main, String from, String to) {
        for (List<String> r : main) {
            for (int i = 0; i < r.size(); i++) {
                if (r.get(i).contains(from)) {
                    r.set(i, r.get(i).replace(from, to));
                }
            }
        }
        return main;
    }

    /**
     * 退化保留最后完整版本：事件行逐字段不变 + last_success_at/last_complete_
     * snapshot 不刷新 + 状态机退化计数持久化（3 轮 → auto_isolated；探测两轮
     * 完整 → active 恢复）
     */
    @Test
    void degradedKeepsLastCompleteAndIsolationPersists() throws IOException {
        KeyDateSourceDO cfp = source("cal-fee-payment-annual", "writer");
        syncService.applyComplete(cfp, "2026/27", gate("cal-fee-payment-annual").events());
        List<KeyDateDO> before = keyDateMapper.selectList(null);
        KeyDateSourceDO sourceBefore = sourceMapper.selectList(null).get(0);
        assertNotNull(sourceBefore.getLastSuccessAt());
        String snapshotBefore = sourceBefore.getLastCompleteSnapshot();

        for (int i = 0; i < 3; i++) {
            syncService.applyDegraded(cfp, List.of("未知候选 1 个：r1:f0"));
        }
        List<KeyDateDO> after = keyDateMapper.selectList(null);
        assertEquals(before.size(), after.size(), "退化轮零事件写、零撤回");
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i), after.get(i), "事件行逐字段不变（含 status/revision/last_seen_at）");
        }
        KeyDateSourceDO isolated = sourceMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KeyDateSourceDO>()
                .eq(KeyDateSourceDO::getSourceKey, "cal-fee-payment-annual"));
        assertEquals("auto_isolated", isolated.getAutoState(), "3 轮退化 → 自动隔离落库");
        assertEquals(snapshotBefore, isolated.getLastCompleteSnapshot(), "退化不覆盖最后完整快照");
        assertEquals(sourceBefore.getLastSuccessAt(), isolated.getLastSuccessAt(), "退化不刷新 last_success_at");

        syncService.recordProbe(cfp, false);
        assertEquals("auto_isolated", sourceMapper.selectList(null).get(0).getAutoState(), "第 1 轮完整探测仍隔离");
        syncService.recordProbe(cfp, false);
        assertEquals("active", sourceMapper.selectList(null).get(0).getAutoState(), "第 2 轮完整探测恢复");
        assertEquals(0, keyDateMapper.selectList(null).stream()
                .filter(r -> "withdrawn".equals(r.getStatus())).count(), "探测期零发布零撤回");
    }

    /**
     * 撤回门：完整候选缺席事件才撤回——限定本源+本覆盖学年+published；
     * 他源同身份域（校验域/旧学年）不进撤回集
     */
    @Test
    void withdrawalGateLimitedToCoverageDomain() throws IOException {
        KeyDateSourceDO cfp = source("cal-fee-payment-annual", "writer");
        SourceGate.GateResult base = gate("cal-fee-payment-annual");
        syncService.applyComplete(cfp, "2026/27", base.events());
        int before = keyDateMapper.selectList(null).size();

        // 完整候选但删除 S1 initial 行（通知+截止两事件缺席）→ 仅这两事件 withdrawn
        SourceGate.GateResult removed = gate("cal-fee-payment-annual", main -> {
            main.remove(1);
            return main;
        });
        assertTrue(!removed.degraded());
        var result = syncService.applyComplete(cfp, "2026/27", removed.events());
        assertEquals(2, result.withdrawn().size(), "缺席的两事件被撤回");
        String notification = KeyDateUid.uidOf("2026/27", "S1", "fee-notification", "current-students", "initial");
        String deadline = KeyDateUid.uidOf("2026/27", "S1", "fee-deadline", "current-students", "initial");
        assertEquals("withdrawn", byUid(notification).getStatus());
        assertEquals("withdrawn", byUid(deadline).getStatus());
        String remaining = KeyDateUid.uidOf("2026/27", "S1", "fee-deadline", "current-students", "remaining");
        assertEquals("published", byUid(remaining).getStatus(), "在场事件不受影响");
        assertEquals(before, keyDateMapper.selectList(null).size(), "撤回不物理删除");
    }

    /**
     * 源级原子性：发布事务中途失败（超长 title 违反 VARCHAR(256)）→ 全量回滚，
     * 事件与源行零残留
     */
    @Test
    void atomicPublishRollsBackCompletely() throws IOException {
        KeyDateSourceDO cfp = source("cal-fee-payment-annual", "writer");
        List<KeyDateCandidate> events = gate("cal-fee-payment-annual").events();
        KeyDateCandidate poison = events.get(0);
        // 原型候选注入超长身份 → 词表兜底模板产出超长英文标题触发列宽违约
        KeyDateCandidate bad = poison.copy();
        bad.setEventCode("x".repeat(300));
        List<KeyDateCandidate> withPoison = new ArrayList<>(events);
        withPoison.add(bad);
        try {
            syncService.applyComplete(cfp, "2026/27", withPoison);
            throw new AssertionError("预期事务违约失败");
        } catch (Exception expected) {
            // org.postgresql.util.PSQLException: value too long for type character varying
        }
        assertEquals(0, keyDateMapper.selectList(null).size(), "事件零残留（整事务回滚）");
        assertEquals(0, sourceMapper.selectList(null).size(), "源行零残留（与事件同事务）");
    }

    /**
     * verifier 零写径：篡改后的校验源候选 → discrepancy 记录返回 + 源诊断落库，
     * 事件表零写入、权威值保持（K11 存储面）
     */
    @Test
    void verifierZeroWritePathDiscrepancyOnly() throws IOException {
        KeyDateSourceDO cac = source("cal-academic-calendar", "writer");
        syncService.applyComplete(cac, "2026/27", gate("cal-academic-calendar").events());
        KeyDateDO authority = byUid(KeyDateUid.uidOf("2026/27", "S1", "exam-period", "all", "window"));
        assertNotNull(authority, "权威事件已发布");

        KeyDateSourceDO cet = source("cal-exam-timetable", "verifier");
        SourceGate.GateResult tampered = gate("cal-exam-timetable", main -> {
            main.get(1).set(1, "4 to 19 December 2026");
            return main;
        });
        List<String[]> disc = syncService.recordDiscrepancies(cet, tampered.candidates());
        assertTrue(disc.size() >= 1, "discrepancy 记录双方值");
        assertEquals(0, keyDateMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KeyDateDO>()
                        .eq(KeyDateDO::getSourceKey, "cal-exam-timetable")).size(),
                "校验源零事件写径");
        KeyDateDO kept = byUid(KeyDateUid.uidOf("2026/27", "S1", "exam-period", "all", "window"));
        assertEquals(LocalDate.of(2026, 12, 3), kept.getDateStart(), "权威值不被校验源覆盖");
        assertEquals(LocalDate.of(2026, 12, 18), kept.getDateEnd());
        KeyDateSourceDO cetRow = sourceMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<KeyDateSourceDO>()
                .eq(KeyDateSourceDO::getSourceKey, "cal-exam-timetable"));
        assertNotNull(cetRow.getLastDiag(), "discrepancy 写入源诊断");
        assertTrue(cetRow.getLastDiag().contains("discrepancy"));
    }

    /**
     * 一次性发布 74 事件的完整管线冒烟：五源中三写者发布 → 74-13-6=… 各源计数
     * 与账本一致（对账面的真库侧证）+ VERIFY 片段核对零 discrepancy
     */
    @Test
    void fullFiveSourcePublishSmoke() throws IOException {
        Map<String, Integer> expected = Map.of(
                "cal-academic-calendar", 49, "cal-fee-payment-annual", 13,
                "cal-timetable-exam-results", 6, "cal-assessment-results", 6);
        Map<String, SourceGate.GateResult> gates = new java.util.LinkedHashMap<>();
        gates.put("cal-academic-calendar", gate("cal-academic-calendar"));
        gates.put("cal-fee-payment-annual", gate("cal-fee-payment-annual"));
        gates.put("cal-timetable-exam-results", gate("cal-timetable-exam-results"));
        gates.put("cal-exam-timetable", gate("cal-exam-timetable"));
        gates.put("cal-assessment-results", gate("cal-assessment-results"));
        gates.forEach((key, g) -> assertTrue(!g.degraded(), key));
        assertEquals(74, gates.values().stream().mapToInt(g -> g.events().size()).sum());

        for (Map.Entry<String, SourceGate.GateResult> e : gates.entrySet()) {
            String role = "cal-exam-timetable".equals(e.getKey()) ? "verifier" : "writer";
            KeyDateSourceDO src = source(e.getKey(), role);
            if ("verifier".equals(role)) {
                syncService.recordDiscrepancies(src, e.getValue().candidates());
            } else {
                String coverage = com.nageoffer.ai.ragent.calendar.parse.CalendarPageParser.pageAy(e.getKey(),
                        CalendarHtmlTables.visibleText(snap(e.getKey())), CalendarHtmlTables.mainRows(snap(e.getKey())));
                syncService.applyComplete(src, coverage, e.getValue().events());
            }
        }
        List<KeyDateDO> all = keyDateMapper.selectList(null);
        assertEquals(74, all.size(), "四写者共发布 74 事件（校验源零写）");
        for (Map.Entry<String, Integer> e : expected.entrySet()) {
            assertEquals(e.getValue().longValue(), all.stream().filter(r -> e.getKey().equals(r.getSourceKey())).count(),
                    e.getKey() + " 事件数与账本一致");
        }
        assertTrue(all.stream().allMatch(r -> r.getTitleEn() != null && !r.getTitleEn().isEmpty()));
        assertTrue(all.stream().allMatch(r -> "published".equals(r.getStatus())));
        // 跨源校验面：五源门禁结果整体核对零 discrepancy
        assertTrue(SourceGate.crossVerify(gates).isEmpty());
    }

    /** URL 常量内联（主词表包私有可见性下的测试辅助） */
    private static final class CalendarLexiconUrls {

        static String url(String key) {
            return com.nageoffer.ai.ragent.calendar.parse.CalendarLexicon.SOURCE_URLS.get(key);
        }
    }
}
