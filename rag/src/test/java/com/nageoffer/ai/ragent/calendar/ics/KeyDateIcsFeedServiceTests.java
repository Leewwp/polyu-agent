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

package com.nageoffer.ai.ragent.calendar.ics;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #194 导出资格/生命周期状态机（Mockito 单测；真库写径实证在
 * {@code KeyDateIcsFeedPgIt}）。票面验收逐条：
 * <ul>
 *   <li>改期（同 UID、revision+1→SEQUENCE 递增、新 DTSTART）→ rescheduleKeepsUidAndBumpsSequence</li>
 *   <li>撤回（STATUS:CANCELLED 同 UID、SEQUENCE 严格大于、状态翻 cancelled）→
 *       withdrawalEmitsCancelledAndFlipsState</li>
 *   <li>取消留存 ≥撤回日起 90 天（含端）→ cancelRetentionWindowIsInclusiveNinetyDays</li>
 *   <li>恢复（cancelled→published、SEQUENCE 再+1、去取消态）→ recoveryRestoresLiveEventAboveCancelSequence</li>
 *   <li>精确变模糊（发取消、不留旧精确提醒、快照日期回放）→ exactToFuzzyCancelsOldPreciseReminder</li>
 *   <li>fuzzy/onwards 只入展示不入提醒 → fuzzyAndOnwardsNeverExportedNorAlarmed</li>
 *   <li>归档不发 CANCELLED → archivedRowStaysLiveWithoutCancellation</li>
 *   <li>VALARM 仅 deadline 白名单（fee-notification 反例）→ valarmOnlyOnDeadlineWhitelistEventCodes</li>
 *   <li>唯一写径=ics_export_state/ics_last_dates 两列 → writePathTouchesOnlyTheTwoPermittedColumns</li>
 *   <li>幂等：同库二次导出零写回、字节一致 → secondExportIsIdempotentAndStable</li>
 * </ul>
 * 固定时钟锚点 2026-09-30 00:00 HKT——留存窗断言不受真实日期影响。
 */
class KeyDateIcsFeedServiceTests {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");

    private final KeyDateMapper keyDateMapper = mock(KeyDateMapper.class);

    @BeforeAll
    static void initLambdaCache() {
        // LambdaUpdateWrapper 列解析需 TableInfo lambda cache（KeyDateQueryServiceTests 同判例）
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, KeyDateDO.class);
    }

    @BeforeEach
    void resetMock() {
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of());
    }

    private KeyDateIcsFeedService service() {
        IcsExportProperties properties = new IcsExportProperties();
        Clock fixed = Clock.fixed(Instant.parse("2026-09-29T16:00:00Z"), HKT); // 2026-09-30 00:00 HKT
        return new KeyDateIcsFeedService(keyDateMapper, properties, fixed);
    }

    private static KeyDateDO row(String uidHex64, String eventCode, String precision,
                                 LocalDate start, LocalDate end, String status, int revision) {
        return KeyDateDO.builder()
                .id((long) uidHex64.charAt(0))
                .uid(uidHex64)
                .academicYear("2026/27")
                .term("S1")
                .eventCode(eventCode)
                .audienceCode("current-students")
                .semanticSlot("initial")
                .sourceKey("cal-fee-payment-annual")
                .sourceUrl("https://www.polyu.edu.hk/ar/students-in-taught-programmes/annual-schedules/fee-payment/")
                .titleEn("Semester One tuition fee payment deadline (first round)")
                .titleZh("第一学期学费缴费截止（首轮）")
                .audienceText("Current Students")
                .rawText("r5:f1")
                .provenance("r5:f1")
                .precision(precision)
                .dateStart(start)
                .dateEnd(end)
                .fuzzyHint("fuzzy".equals(precision) ? "Late October 2026" : null)
                .status(status)
                .revision(revision)
                .firstSeenAt(LocalDateTime.of(2026, 9, 1, 7, 30))
                .lastSeenAt(LocalDateTime.of(2026, 9, 29, 7, 30))
                .build();
    }

    private static String hex64(char c) {
        return String.valueOf(c).repeat(64);
    }

    private static String uidLine(String hex) {
        return "UID:" + IcsCalendarWriter.UID_PREFIX + hex;
    }

    /**
     * RFC 5545 展开折行（CRLF+WSP → 拼接）：UID 行 87 八位组必然折行，逻辑行
     * 断言一律在展开面上做（物理面折行断言在 IcsCalendarWriterTests）
     */
    private static String unfolded(String ics) {
        return ics.replace("\r\n ", "");
    }

    private static List<String> updateColumnSets(KeyDateMapper mapper, int wantedUpdates) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<KeyDateDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(wantedUpdates)).update(isNull(), captor.capture());
        return captor.getAllValues().stream()
                .map(w -> ((LambdaUpdateWrapper<KeyDateDO>) w).getSqlSet()
                        .replaceAll("=#\\{[^}]*}", "") // 去参数占位，留列名
                        .replace(" ", ""))
                .toList();
    }

    // —— 资格与 UID 合同 ——

    @Test
    void publishedExactExportedWithContractUidAndStateWriteback() {
        KeyDateDO fee = row(hex64('a'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "published", 1);
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(fee));

        KeyDateIcsFeedService.FeedResult result = service().exportFeed();

        // UID 前缀合同 + 唯一写径回写 exported + 快照
        assertTrue(unfolded(result.feed()).contains(uidLine(hex64('a'))));
        assertTrue(result.feed().contains("DTSTART;VALUE=DATE:20261013"));
        assertTrue(result.feed().contains("DTEND;VALUE=DATE:20261014"));
        assertTrue(result.feed().contains("SEQUENCE:1"));
        assertTrue(result.feed().contains("STATUS:CONFIRMED"));
        assertEquals(1, result.liveCount());
        assertEquals(1, result.stateWrites());
        assertEquals(1, result.snapshotWrites());
        assertEquals(List.of("ics_export_state,ics_last_dates"), updateColumnSets(keyDateMapper, 1));
        // 生成 feed 过自写校验器（RFC 硬门）
        assertTrue(Rfc5545Validator.violations(result.feed()).isEmpty(),
                "导出 feed 须过 RFC 5545 校验器：" + Rfc5545Validator.violations(result.feed()));
    }

    // —— 改期：同 UID、SEQUENCE 递增 ——

    @Test
    void rescheduleKeepsUidAndBumpsSequence() {
        // 改期后（库内同一行）：revision 1→2、日期 10-13→10-20；上次导出快照仍是旧日期
        KeyDateDO rescheduled = row(hex64('b'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 20), null, "published", 2);
        rescheduled.setIcsExportState("exported");
        rescheduled.setIcsLastDates("exact-day|2026-10-13|");
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(rescheduled));

        KeyDateIcsFeedService.FeedResult result = service().exportFeed();

        // 硬门：UID 不换（防双提醒）+ SEQUENCE 递增 + 新 DTSTART（旧日期不残留）
        assertTrue(unfolded(result.feed()).contains(uidLine(hex64('b'))));
        assertTrue(result.feed().contains("SEQUENCE:2"));
        assertTrue(result.feed().contains("DTSTART;VALUE=DATE:20261020"));
        assertFalse(result.feed().contains("DTSTART;VALUE=DATE:20261013"),
                "改期后旧日期不得再出现在 feed");
        assertEquals(1, result.liveCount());
        assertEquals(0, result.cancelCount());
        // 快照跟进新日期（后续若再变模糊，取消组件回放本次日期）
        assertEquals(1, result.snapshotWrites());
        assertEquals(0, result.stateWrites(), "状态仍 exported——不重复写 state");
    }

    // —— 撤回：STATUS:CANCELLED 同 UID ——

    @Test
    void withdrawalEmitsCancelledAndFlipsState() {
        // #192 撤回合同：status=withdrawn、withdrawn_at=now、revision+1（3）
        KeyDateDO withdrawn = row(hex64('c'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "withdrawn", 3);
        withdrawn.setIcsExportState("exported");
        withdrawn.setIcsLastDates("exact-day|2026-10-13|");
        withdrawn.setWithdrawnAt(LocalDateTime.of(2026, 9, 29, 7, 30));
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(withdrawn));

        KeyDateIcsFeedService.FeedResult result = service().exportFeed();

        // 同 UID + STATUS:CANCELLED + SEQUENCE=撤回后 revision（>客户端最后持有的 2）
        assertTrue(unfolded(result.feed()).contains(uidLine(hex64('c'))));
        assertTrue(Pattern.compile("SEQUENCE:3\\r\\nSTATUS:CANCELLED").matcher(result.feed()).find(),
                "取消组件 SEQUENCE=撤回轮 revision（#192 撤回必 +1，严格大于导出轮）");
        assertTrue(result.feed().contains("DTSTART;VALUE=DATE:20261013"));
        assertFalse(result.feed().contains("STATUS:CONFIRMED"));
        // 状态翻 cancelled——只写 ics_export_state 一列（快照保留）
        assertEquals(1, result.stateWrites());
        assertEquals(0, result.snapshotWrites());
        assertEquals(List.of("ics_export_state"), updateColumnSets(keyDateMapper, 1));
        assertTrue(Rfc5545Validator.violations(result.feed()).isEmpty());
    }

    // —— 取消留存 ≥ 撤回日起 90 天（含端） ——

    @Test
    void cancelRetentionWindowIsInclusiveNinetyDays() {
        KeyDateDO fresh = row(hex64('d'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "withdrawn", 2);
        fresh.setIcsExportState("cancelled"); // 已发取消
        fresh.setWithdrawnAt(LocalDateTime.of(2026, 9, 30, 0, 0).minusDays(90)); // 恰第 90 天（含端）

        KeyDateDO expired = row(hex64('e'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "withdrawn", 2);
        expired.setIcsExportState("cancelled");
        expired.setWithdrawnAt(LocalDateTime.of(2026, 9, 30, 0, 0).minusDays(91)); // 第 91 天=窗外

        KeyDateDO noAnchor = row(hex64('f'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "withdrawn", 2);
        noAnchor.setIcsExportState("cancelled");
        noAnchor.setWithdrawnAt(null); // 精确变模糊路径：回退 last_seen_at 锚
        noAnchor.setLastSeenAt(LocalDateTime.of(2026, 9, 29, 7, 30));
        when(keyDateMapper.selectList(isNull()))
                .thenReturn(List.of(fresh, expired, noAnchor));

        KeyDateIcsFeedService.FeedResult result = service().exportFeed();

        assertTrue(unfolded(result.feed()).contains(uidLine(hex64('d'))), "恰第 90 天（含端）仍在留存窗内");
        assertFalse(unfolded(result.feed()).contains(uidLine(hex64('e'))), "第 91 天窗外不再发取消");
        assertTrue(unfolded(result.feed()).contains(uidLine(hex64('f'))), "无撤回锚（精确变模糊）按 last_seen_at 锚保留");
        assertEquals(2, result.cancelCount());
        assertEquals(0, result.stateWrites(), "已 cancelled 的留存回放零写回");
    }

    @Test
    void retentionConfigBelowNinetyIsClampedToContractFloor() {
        IcsExportProperties properties = new IcsExportProperties();
        properties.setCancelRetentionDays(30);
        KeyDateDO day60 = row(hex64('1'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "withdrawn", 2);
        day60.setIcsExportState("cancelled");
        day60.setWithdrawnAt(LocalDateTime.of(2026, 9, 30, 0, 0).minusDays(60));
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(day60));

        String feed = new KeyDateIcsFeedService(keyDateMapper, properties,
                Clock.fixed(Instant.parse("2026-09-29T16:00:00Z"), HKT)).exportFeed().feed();

        assertTrue(unfolded(feed).contains(uidLine(hex64('1'))),
                "配置 30 天低于合同下限——按 90 钳制执行（第 60 天仍保留）");
    }

    // —— 恢复：cancelled→published ——

    @Test
    void recoveryRestoresLiveEventAboveCancelSequence() {
        // 撤回轮 rev3（取消组件 SEQUENCE:3）→ 恢复合同 rev4、withdrawn_at 清空
        KeyDateDO recovered = row(hex64('g'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "published", 4);
        recovered.setIcsExportState("cancelled");
        recovered.setIcsLastDates("exact-day|2026-10-13|");
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(recovered));

        KeyDateIcsFeedService.FeedResult result = service().exportFeed();

        assertTrue(unfolded(result.feed()).contains(uidLine(hex64('g'))));
        assertTrue(Pattern.compile("SEQUENCE:4\\r\\nSTATUS:CONFIRMED").matcher(result.feed()).find(),
                "恢复组件 SEQUENCE=恢复轮 revision（#192 恢复必 +1——严格大于取消组件的 3）");
        assertFalse(result.feed().contains("STATUS:CANCELLED"), "恢复后不再发取消组件");
        assertEquals(1, result.stateWrites(), "cancelled→exported 回写");
        assertTrue(Rfc5545Validator.violations(result.feed()).isEmpty());
    }

    // —— 精确变模糊：取消旧精确提醒 ——

    @Test
    void exactToFuzzyCancelsOldPreciseReminder() {
        // 官方改口「Late October 2026」：同 UID precision→fuzzy、revision+1、日期清空
        KeyDateDO fuzzyNow = row(hex64('h'), "fee-deadline", "fuzzy",
                null, null, "published", 2);
        fuzzyNow.setIcsExportState("exported");
        fuzzyNow.setIcsLastDates("exact-day|2026-10-13|");
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(fuzzyNow));

        KeyDateIcsFeedService.FeedResult result = service().exportFeed();

        // 不留旧精确提醒：无活跃组件；发同 UID CANCELLED（快照回放旧精确日期作 DTSTART）
        assertEquals(0, result.liveCount(), "模糊行不得再活跃导出");
        assertTrue(unfolded(result.feed()).contains(uidLine(hex64('h'))));
        assertTrue(result.feed().contains("STATUS:CANCELLED"));
        assertTrue(result.feed().contains("DTSTART;VALUE=DATE:20261013"),
                "取消组件 DTSTART=上次导出快照日期（ics_last_dates），非空值伪造");
        assertFalse(result.feed().contains("Late October 2026") && result.feed().contains("STATUS:CONFIRMED"),
                "模糊窗不得生成精确提醒");
        assertEquals(1, result.stateWrites(), "exported→cancelled（不留旧精确提醒）");
        assertEquals(List.of("ics_export_state"), updateColumnSets(keyDateMapper, 1));
    }

    // —— fuzzy/onwards 只入展示不入提醒 ——

    @Test
    void fuzzyAndOnwardsNeverExportedNorAlarmed() {
        KeyDateDO fuzzy = row(hex64('i'), "exam-tt-release", "fuzzy", null, null, "published", 1);
        KeyDateDO onwards = row(hex64('j'), "sid-card-collection", "onwards",
                LocalDate.of(2026, 9, 10), null, "published", 1);
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(fuzzy, onwards));

        KeyDateIcsFeedService.FeedResult result = service().exportFeed();

        assertEquals(0, result.liveCount());
        assertEquals(0, result.cancelCount());
        assertFalse(unfolded(result.feed()).contains(uidLine(hex64('i'))));
        assertFalse(unfolded(result.feed()).contains(uidLine(hex64('j'))));
        assertEquals(List.of(), updateColumnSets(keyDateMapper, 0));
        assertTrue(Rfc5545Validator.violations(result.feed()).isEmpty(), "零事件 feed 仍须合法");
    }

    // —— 归档：不发 CANCELLED ——

    @Test
    void archivedRowStaysLiveWithoutCancellation() {
        KeyDateDO archived = row(hex64('k'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 6, 24), null, "archived", 1);
        archived.setIcsExportState("exported");
        archived.setIcsLastDates("exact-day|2026-06-24|");
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(archived));

        KeyDateIcsFeedService.FeedResult result = service().exportFeed();

        // archived=正常过期展示态≠官方取消：原样继续导出，绝不发 CANCELLED
        assertTrue(unfolded(result.feed()).contains(uidLine(hex64('k'))));
        assertTrue(result.feed().contains("STATUS:CONFIRMED"));
        assertFalse(result.feed().contains("STATUS:CANCELLED"), "归档不发 CANCELLED（票面）");
        assertEquals(0, result.stateWrites(), "已 exported 未变——零写回");
    }

    // —— 从未导出的不可见行：不发组件不写库 ——

    @Test
    void neverExportedWithdrawnRowEmitsNothing() {
        KeyDateDO never = row(hex64('l'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "withdrawn", 2);
        assertNull(never.getIcsExportState());
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(never));

        KeyDateIcsFeedService.FeedResult result = service().exportFeed();

        assertFalse(unfolded(result.feed()).contains(uidLine(hex64('l'))),
                "从未导出的撤回行无订阅端持有——无需取消组件");
        assertEquals(0, result.cancelCount());
        assertEquals(0, result.stateWrites());
        assertEquals(List.of(), updateColumnSets(keyDateMapper, 0));
    }

    // —— VALARM 仅 deadline 白名单（按事件码，非 fee 类别整类） ——

    @Test
    void valarmOnlyOnDeadlineWhitelistEventCodes() {
        KeyDateDO deadline = row(hex64('m'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "published", 1);
        // 票面反例：同 fee 类别的通知事件——不得挂 VALARM
        KeyDateDO notification = row(hex64('n'), "fee-notification", "exact-day",
                LocalDate.of(2026, 9, 25), null, "published", 1);
        KeyDateDO release = row(hex64('o'), "results-overall-release", "exact-day",
                LocalDate.of(2027, 1, 14), null, "published", 1);
        KeyDateDO holiday = row(hex64('p'), "general-holiday", "exact-day",
                LocalDate.of(2027, 1, 1), null, "published", 1);
        when(keyDateMapper.selectList(isNull()))
                .thenReturn(Arrays.asList(deadline, notification, release, holiday));

        String feed = service().exportFeed().feed();

        assertTrue(containsAlarmFor(feed, hex64('m')), "fee-deadline（缴费截止）挂 VALARM");
        assertFalse(containsAlarmFor(feed, hex64('n')), "fee-notification（同 fee 类别但非截止）不挂——票面反例");
        assertTrue(containsAlarmFor(feed, hex64('o')), "results-overall-release（成绩发布）挂 VALARM");
        assertFalse(containsAlarmFor(feed, hex64('p')), "general-holiday 不挂 VALARM");
        assertTrue(feed.contains("TRIGGER:-P7D"));
        assertTrue(Rfc5545Validator.violations(feed).isEmpty());
    }

    /**
     * 事件组件体内是否含 VALARM（按 UID 定位组件段）
     */
    private static boolean containsAlarmFor(String feed, String uidHex) {
        String[] blocks = unfolded(feed).split("BEGIN:VEVENT");
        for (String block : blocks) {
            if (block.startsWith("\r\n" + uidLine(uidHex))) {
                return block.contains("BEGIN:VALARM");
            }
        }
        return false;
    }

    // —— 写径铁律：只碰两列 ——

    @Test
    void writePathTouchesOnlyTheTwoPermittedColumns() {
        KeyDateDO live = row(hex64('q'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "published", 1);
        KeyDateDO cancel = row(hex64('r'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "withdrawn", 2);
        cancel.setIcsExportState("exported");
        cancel.setIcsLastDates("exact-day|2026-10-13|");
        cancel.setWithdrawnAt(LocalDateTime.of(2026, 9, 29, 7, 30));
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(live, cancel));

        service().exportFeed();

        // 两次 update（exported 双列 + cancelled 单列）——列名集合逐次断言：
        // 铁律=唯一写径 ics_export_state/ics_last_dates，其余列零触碰
        Set<String> permitted = Set.of("ics_export_state", "ics_last_dates");
        for (String sqlSet : updateColumnSets(keyDateMapper, 2)) {
            List<String> columns = Arrays.asList(sqlSet.split(","));
            assertTrue(permitted.containsAll(columns),
                    "写径越界：" + columns + " 只允许 " + permitted);
        }
    }

    // —— 幂等：同库二次导出零写回、字节一致 ——

    @Test
    void secondExportIsIdempotentAndStable() {
        KeyDateDO steady = row(hex64('s'), "fee-deadline", "exact-day",
                LocalDate.of(2026, 10, 13), null, "published", 1);
        steady.setIcsExportState("exported");
        steady.setIcsLastDates("exact-day|2026-10-13|");
        when(keyDateMapper.selectList(isNull())).thenReturn(List.of(steady));

        KeyDateIcsFeedService.FeedResult first = service().exportFeed();
        KeyDateIcsFeedService.FeedResult second = service().exportFeed();

        assertEquals(first.feed(), second.feed(), "数据未变两次导出字节一致（稳定序+DTSTAMP 取行时间）");
        assertEquals(0, second.stateWrites(), "二次导出零 state 写回");
        assertEquals(0, second.snapshotWrites(), "二次导出零快照写回");
        verify(keyDateMapper, times(0)).update(isNull(), any());
    }
}
