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
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateAuditVO;
import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateBoardVO;
import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateVO;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateSourceDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateMapper;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateSourceMapper;
import com.nageoffer.ai.ragent.calendar.service.KeyDateDisplayProperties;
import com.nageoffer.ai.ragent.calendar.service.impl.KeyDateQueryServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校历查询服务测试（#193 验收逐条对应；纯 Mockito 直测服务层=CI 可跑，
 * KeyDateSyncJobTests 同判例——不建 Spring 上下文）。
 *
 * <p>票面验收映射：
 * <ul>
 *   <li>排序/归档边界（今日/临近/过期 N 天）→ proximitySortAndArchiveBoundary /
 *       exactRangeOngoingPhase；</li>
 *   <li>三态口径不冒充最新（正常/退化保留最后完整版本/源隔离）→
 *       sourceThreeStates_degradedKeepsLastCompleteNotLatest；</li>
 *   <li>倒计时门（exact-day/exact-range 进倒计时，onwards/fuzzy 不伪造具体日）→
 *       countdownGateOnwardsFuzzyNoCountdown；</li>
 *   <li>字段级断言（双语标题/受众原文/日期一律 LocalDate）→ boardFieldMapping；</li>
 *   <li>archived 封顶+全量计数 → archivedCapAndTotal；withdrawn 不可见 →
 *       withdrawnRowInvisible；空库态 → emptyDatabaseState。</li>
 * </ul>
 * 固定时钟锚点 2026-09-30 HKT——归档边界含端断言不受真实日期影响。
 */
class KeyDateQueryServiceTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");

    private final KeyDateMapper keyDateMapper = mock(KeyDateMapper.class);
    private final KeyDateSourceMapper sourceMapper = mock(KeyDateSourceMapper.class);

    @BeforeAll
    static void initLambdaCache() {
        // LambdaQueryWrapper 列解析需 TableInfo lambda cache（KeyDateSyncPgIt 同判例；
        // 纯 Mockito 单测无 MyBatis 启动，须手动初始化实体元数据）
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, KeyDateDO.class);
        TableInfoHelper.initTableInfo(assistant, KeyDateSourceDO.class);
    }

    private KeyDateQueryServiceImpl service(int archiveAfterDays, int archivedCap) {
        KeyDateDisplayProperties properties = new KeyDateDisplayProperties();
        properties.setArchiveAfterDays(archiveAfterDays);
        properties.setArchivedCap(archivedCap);
        Clock fixed = Clock.fixed(Instant.parse("2026-09-29T16:00:00Z"), HKT); // 2026-09-30 00:00 HKT
        return new KeyDateQueryServiceImpl(keyDateMapper, sourceMapper, properties, fixed);
    }

    private static KeyDateDO event(String uid, String precision, LocalDate start, LocalDate end) {
        return KeyDateDO.builder()
                .uid(uid)
                .academicYear("2026/27")
                .term("S1")
                .eventCode(uid)
                .audienceCode("all")
                .semanticSlot("")
                .sourceKey("cal-academic-calendar")
                .sourceUrl("https://www.polyu.edu.hk/ar/students-in-taught-programmes/academic-calendar/")
                .titleEn("Title " + uid)
                .titleZh("标题 " + uid)
                .rawText("raw")
                .provenance("r1")
                .precision(precision)
                .dateStart(start)
                .dateEnd(end)
                .status("published")
                .revision(1)
                .build();
    }

    private static KeyDateSourceDO source(String key, String role, String enabled, String autoState,
                                          int degradedStreak, LocalDateTime lastSuccessAt, String coverage) {
        return KeyDateSourceDO.builder()
                .sourceKey(key)
                .sourceUrl("https://example.polyu.edu.hk/" + key)
                .role(role)
                .enabled(enabled)
                .autoState(autoState)
                .degradedStreak(degradedStreak)
                .probeOkStreak(0)
                .coverageAcademicYear(coverage)
                .lastSuccessAt(lastSuccessAt)
                .build();
    }

    // ------------------------------------------------ 排序/归档边界（今日/临近/过期 N 天）

    @Test
    void proximitySortAndArchiveBoundary() {
        int n = 14;
        // 今日/临近/过期边界五档：昨日过期、恰 N 天前（含端=recent）、N+1 天前（archived）、今日、明日、远期
        when(keyDateMapper.selectList(any())).thenReturn(List.of(
                event("far", "exact-day", TODAY.plusDays(30), null),
                event("range", "exact-range", TODAY.plusDays(2), TODAY.plusDays(9)),
                event("today", "exact-day", TODAY, null),
                event("tomorrow", "exact-day", TODAY.plusDays(1), null),
                event("yesterday", "exact-day", TODAY.minusDays(1), null),
                event("edgeN", "exact-day", TODAY.minusDays(n), null),
                event("edgeN1", "exact-day", TODAY.minusDays(n + 1), null)));
        when(sourceMapper.selectList(any())).thenReturn(List.of());

        KeyDateBoardVO board = service(n, 120).board();

        // currentAndUpcoming=临近度序（date_start 升序），今日相=today、daysUntil=0
        assertEquals(List.of("today", "tomorrow", "range", "far"),
                board.getCurrentAndUpcoming().stream().map(KeyDateVO::getUid).toList());
        assertEquals("today", board.getCurrentAndUpcoming().get(0).getPhase());
        assertEquals(0, board.getCurrentAndUpcoming().get(0).getDaysUntil());
        assertEquals("upcoming", board.getCurrentAndUpcoming().get(1).getPhase());
        assertEquals(1, board.getCurrentAndUpcoming().get(1).getDaysUntil());
        assertEquals(2, board.getCurrentAndUpcoming().get(2).getDaysUntil());

        // recentPast=过期 ≤N 天（含端：恰 N 天前仍 recent），结束日倒序（最新过期在前）
        assertEquals(List.of("yesterday", "edgeN"),
                board.getRecentPast().stream().map(KeyDateVO::getUid).toList());
        assertEquals("recent", board.getRecentPast().get(0).getPhase());
        assertEquals(-1, board.getRecentPast().get(0).getDaysUntil());

        // archived=过期 >N 天（N+1 天前起进归档），结束日倒序
        assertEquals(List.of("edgeN1"), board.getArchived().stream().map(KeyDateVO::getUid).toList());
        assertEquals("archived", board.getArchived().get(0).getPhase());
        assertEquals(1, board.getArchivedTotal());

        // 服务端锚点透出（前端徽章/分组消费，避免客户端时区漂移）
        assertEquals(TODAY, board.getToday());
    }

    @Test
    void exactRangeOngoingPhase() {
        // exact-range 进行中：start<today≤end → ongoing（留在 currentAndUpcoming，不判过期）
        when(keyDateMapper.selectList(any())).thenReturn(List.of(
                event("ongoing", "exact-range", TODAY.minusDays(3), TODAY.plusDays(4)),
                event("endedRecent", "exact-range", TODAY.minusDays(5), TODAY.minusDays(1)),
                event("endedArchived", "exact-range", TODAY.minusDays(40), TODAY.minusDays(20))));
        when(sourceMapper.selectList(any())).thenReturn(List.of());

        KeyDateBoardVO board = service(14, 120).board();

        assertEquals(List.of("ongoing"), board.getCurrentAndUpcoming().stream().map(KeyDateVO::getUid).toList());
        assertEquals("ongoing", board.getCurrentAndUpcoming().get(0).getPhase());
        // 区间进行中：起算日已过，daysUntil=已过天数（负值，前端不用于倒计时）
        assertEquals(-3, board.getCurrentAndUpcoming().get(0).getDaysUntil());
        // 区间过期按结束日判：endedRecent（昨日结束）recent；endedArchived（20 天前结束>N）archived
        assertEquals(List.of("endedRecent"), board.getRecentPast().stream().map(KeyDateVO::getUid).toList());
        assertEquals(List.of("endedArchived"), board.getArchived().stream().map(KeyDateVO::getUid).toList());
    }

    // ------------------------------------------------ 倒计时门（onwards/fuzzy 不伪造具体日）

    @Test
    void countdownGateOnwardsFuzzyNoCountdown() {
        KeyDateDO onwards = event("onwards", "onwards", TODAY.plusDays(10), null);
        KeyDateDO fuzzy = event("fuzzy", "fuzzy", null, null);
        fuzzy.setFuzzyHint("Late October 2026");
        fuzzy.setTitleZh(null); // 顺带断言缺词直通：titleZh=null 消费侧回退英文
        when(keyDateMapper.selectList(any())).thenReturn(List.of(onwards, fuzzy));
        when(sourceMapper.selectList(any())).thenReturn(List.of());

        KeyDateBoardVO board = service(14, 120).board();

        // onwards：真实开放起点日可展示（date_start 非伪造），但不进倒计时（daysUntil=null）
        assertEquals(List.of("onwards"), board.getCurrentAndUpcoming().stream().map(KeyDateVO::getUid).toList());
        assertEquals("upcoming", board.getCurrentAndUpcoming().get(0).getPhase());
        assertEquals(TODAY.plusDays(10), board.getCurrentAndUpcoming().get(0).getDateStart());
        assertNull(board.getCurrentAndUpcoming().get(0).getDaysUntil());

        // fuzzy：不伪造具体日（date_* null、只透原文窗桶），进 undated 段、无倒计时
        assertEquals(List.of("fuzzy"), board.getUndated().stream().map(KeyDateVO::getUid).toList());
        KeyDateVO fuzzyVO = board.getUndated().get(0);
        assertEquals("undated", fuzzyVO.getPhase());
        assertNull(fuzzyVO.getDateStart());
        assertNull(fuzzyVO.getDateEnd());
        assertEquals("Late October 2026", fuzzyVO.getFuzzyHint());
        assertNull(fuzzyVO.getDaysUntil());
        assertNull(fuzzyVO.getTitleZh());
    }

    // ------------------------------------------------ 三态口径（不冒充最新）

    @Test
    void sourceThreeStates_degradedKeepsLastCompleteNotLatest() {
        LocalDateTime degradedLastSuccess = LocalDateTime.of(2026, 9, 20, 7, 31);
        LocalDateTime normalLastSuccess = LocalDateTime.of(2026, 9, 29, 7, 31);
        when(sourceMapper.selectList(any())).thenReturn(List.of(
                source("cal-academic-calendar", "writer", "1", "active", 0, normalLastSuccess, "2026/27"),
                // 退化：active 但连续 2 轮失败——last_success_at 停在 9/20（摄取面退化轮不刷新）
                source("cal-fee-payment-annual", "writer", "1", "active", 2, degradedLastSuccess, "2026/27"),
                // 隔离：auto_isolated——日级只读探测中，库内=隔离前最后完整版本（历史发布非最新）
                source("cal-timetable-exam-results", "writer", "1", "auto_isolated", 3, degradedLastSuccess, "2026/27"),
                // 人工停用：enabled=0（manual_disabled 落库面；不参与同步不自动复活）
                source("cal-exam-timetable", "verifier", "0", "active", 0, null, null),
                source("cal-assessment-results", "writer", "1", "active", 0, normalLastSuccess, "2025/26")));
        when(keyDateMapper.selectList(any())).thenReturn(List.of());

        KeyDateBoardVO board = service(14, 120).board();

        var byKey = board.getSources().stream()
                .collect(java.util.stream.Collectors.toMap(s -> s.getSourceKey(), s -> s));
        assertEquals("normal", byKey.get("cal-academic-calendar").getState());
        assertEquals("degraded", byKey.get("cal-fee-payment-annual").getState());
        assertEquals(2, byKey.get("cal-fee-payment-annual").getDegradedStreak());
        assertEquals("isolated", byKey.get("cal-timetable-exam-results").getState());
        assertEquals("manual_disabled", byKey.get("cal-exam-timetable").getState());
        assertFalse(byKey.get("cal-exam-timetable").isEnabled());

        // 退化/隔离源的时间原样透出（数据截至证据，不冒充最新）：9/20 不被任何展示逻辑改写
        assertEquals(degradedLastSuccess, byKey.get("cal-fee-payment-annual").getLastSuccessAt());
        // 三态聚合：存在退化/隔离源 → 页面按「最后完整版本（截至 lastFullSyncAt）」呈现
        assertTrue(board.isAnySourceAbnormal());
        // lastFullSyncAt=各源 last_success_at 最大值（最近完整同步时间口径）
        assertEquals(normalLastSuccess, board.getLastFullSyncAt());
        // 覆盖学年=写者源聚合（verifier 的 coverage 不入；同值取最大）
        assertEquals("2026/27", board.getCoverageAcademicYear());
    }

    @Test
    void allNormalSources_noAbnormalFlag() {
        when(sourceMapper.selectList(any())).thenReturn(List.of(
                source("cal-academic-calendar", "writer", "1", "active", 0,
                        LocalDateTime.of(2026, 9, 29, 7, 31), "2026/27")));
        when(keyDateMapper.selectList(any())).thenReturn(List.of());

        KeyDateBoardVO board = service(14, 120).board();

        assertFalse(board.isAnySourceAbnormal());
        assertEquals("2026/27", board.getCoverageAcademicYear());
    }

    // ------------------------------------------------ 字段级映射（双语/受众原文/LocalDate）

    @Test
    void boardFieldMapping() {
        KeyDateDO row = event("field-check", "exact-day", TODAY.plusDays(3), null);
        row.setTerm("S2");
        row.setTitleEn("Add/Drop Period");
        row.setTitleZh("增退期");
        row.setAudienceText("Taught postgraduate students only");
        when(keyDateMapper.selectList(any())).thenReturn(List.of(row));
        when(sourceMapper.selectList(any())).thenReturn(List.of());

        KeyDateVO vo = service(14, 120).board().getCurrentAndUpcoming().get(0);

        assertEquals("field-check", vo.getUid());
        assertEquals("2026/27", vo.getAcademicYear());
        assertEquals("S2", vo.getTerm());
        assertEquals("Add/Drop Period", vo.getTitleEn());
        assertEquals("增退期", vo.getTitleZh());
        assertEquals("exact-day", vo.getPrecision());
        assertEquals(LocalDate.class, vo.getDateStart().getClass()); // 日期一律 LocalDate（判例）
        assertEquals(TODAY.plusDays(3), vo.getDateStart());
        assertNull(vo.getDateEnd());
        assertEquals("Taught postgraduate students only", vo.getAudienceText()); // 人群限定原文不得省略
        assertEquals("https://www.polyu.edu.hk/ar/students-in-taught-programmes/academic-calendar/", vo.getSourceUrl());
        assertEquals("upcoming", vo.getPhase());
        assertEquals(3, vo.getDaysUntil());
    }

    // ------------------------------------------------ withdrawn 不可见 / archived 封顶 / 空库

    @Test
    void withdrawnRowInvisible() {
        KeyDateDO withdrawn = event("withdrawn", "exact-day", TODAY.plusDays(5), null);
        withdrawn.setStatus("withdrawn");
        // DB 生命周期归档行（跨学年旧事件）：不冒充最新，一律展示 archived 段
        KeyDateDO dbArchived = event("db-archived", "exact-day", TODAY.plusDays(5), null);
        dbArchived.setStatus("archived");
        dbArchived.setAcademicYear("2024/25");
        when(keyDateMapper.selectList(any())).thenReturn(List.of(withdrawn, dbArchived,
                event("live", "exact-day", TODAY.plusDays(5), null)));
        when(sourceMapper.selectList(any())).thenReturn(List.of());

        KeyDateBoardVO board = service(14, 120).board();

        // 撤回行（限定覆盖域撤回，保留 UID 与历史）任何段不可见
        assertTrue(board.getCurrentAndUpcoming().stream().noneMatch(v -> "withdrawn".equals(v.getUid())));
        assertTrue(board.getRecentPast().isEmpty());
        assertTrue(board.getUndated().isEmpty());
        // published 行正常展示；DB archived 行归展示 archived 段（不计入 current）
        assertEquals(List.of("live"), board.getCurrentAndUpcoming().stream().map(KeyDateVO::getUid).toList());
        assertEquals(List.of("db-archived"), board.getArchived().stream().map(KeyDateVO::getUid).toList());
        assertEquals(1, board.getArchivedTotal());
    }

    @Test
    void archivedCapAndTotal() {
        when(keyDateMapper.selectList(any())).thenReturn(List.of(
                event("old1", "exact-day", TODAY.minusDays(100), null),
                event("old2", "exact-day", TODAY.minusDays(110), null),
                event("old3", "exact-day", TODAY.minusDays(120), null)));
        when(sourceMapper.selectList(any())).thenReturn(List.of());

        KeyDateBoardVO board = service(14, 2).board();

        assertEquals(2, board.getArchived().size()); // 封顶 archived-cap
        assertEquals(3, board.getArchivedTotal());   // 全量计数不受封顶影响
        // 封顶后保留结束日最新的前 cap 条（倒序序）
        assertEquals(List.of("old1", "old2"), board.getArchived().stream().map(KeyDateVO::getUid).toList());
    }

    @Test
    void emptyDatabaseState() {
        when(sourceMapper.selectList(any())).thenReturn(List.of());
        when(keyDateMapper.selectList(any())).thenReturn(List.of());

        KeyDateBoardVO board = service(14, 120).board();

        assertTrue(board.getSources().isEmpty());
        assertTrue(board.getCurrentAndUpcoming().isEmpty());
        assertTrue(board.getRecentPast().isEmpty());
        assertTrue(board.getArchived().isEmpty());
        assertTrue(board.getUndated().isEmpty());
        assertEquals(0, board.getArchivedTotal());
        assertNull(board.getCoverageAcademicYear());
        assertNull(board.getLastFullSyncAt());
        assertFalse(board.isAnySourceAbnormal());
        assertEquals(TODAY, board.getToday());
    }

    // ------------------------------------------------ 纯读核对视图（admin）

    @Test
    void auditSourcesReadsDiagAndCounts() {
        KeyDateSourceDO degradedSource = source("cal-fee-payment-annual", "writer", "1", "active", 2,
                LocalDateTime.of(2026, 9, 20, 7, 31), "2026/27");
        degradedSource.setLastDiag("退化（零事件写，保留最后完整版本）：抓取失败：HTTP 503");
        degradedSource.setUpdatedAt(LocalDateTime.of(2026, 9, 30, 7, 35));
        when(sourceMapper.selectList(any())).thenReturn(List.of(
                source("cal-academic-calendar", "writer", "1", "active", 0,
                        LocalDateTime.of(2026, 9, 29, 7, 31), "2026/27"),
                degradedSource));
        KeyDateDO live = event("live", "exact-day", TODAY.plusDays(5), null);
        live.setSourceKey("cal-fee-payment-annual");
        KeyDateDO withdrawn = event("wd", "exact-day", TODAY.plusDays(5), null);
        withdrawn.setStatus("withdrawn");
        withdrawn.setSourceKey("cal-fee-payment-annual");
        when(keyDateMapper.selectList(any())).thenReturn(List.of(live, withdrawn));

        List<KeyDateAuditVO> audit = service(14, 120).auditSources();

        assertEquals(2, audit.size());
        assertEquals(List.of("cal-academic-calendar", "cal-fee-payment-annual"),
                audit.stream().map(KeyDateAuditVO::getSourceKey).toList()); // sourceKey 排序稳定
        KeyDateAuditVO degradedVO = audit.get(1);
        assertEquals("writer", degradedVO.getRole());
        assertTrue(degradedVO.isEnabled());
        assertEquals("active", degradedVO.getAutoState());
        assertEquals(2, degradedVO.getDegradedStreak());
        assertEquals("2026/27", degradedVO.getCoverageAcademicYear());
        assertEquals("退化（零事件写，保留最后完整版本）：抓取失败：HTTP 503", degradedVO.getLastDiag());
        assertEquals(1, degradedVO.getPublishedCount());
        assertEquals(1, degradedVO.getWithdrawnCount());
        assertEquals(0, degradedVO.getArchivedCount());
    }
}
