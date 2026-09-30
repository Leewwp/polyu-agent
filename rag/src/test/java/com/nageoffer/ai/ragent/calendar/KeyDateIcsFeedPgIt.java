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
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateSourceDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateMapper;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateSourceMapper;
import com.nageoffer.ai.ragent.calendar.ics.IcsCalendarWriter;
import com.nageoffer.ai.ragent.calendar.ics.IcsExportProperties;
import com.nageoffer.ai.ragent.calendar.ics.KeyDateIcsFeedService;
import com.nageoffer.ai.ragent.calendar.ics.Rfc5545Validator;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;

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

/**
 * #194 导出面真库实证（门控同 KeyDateSyncPgIt/KeyDateQueryPgIt：CI 无 PG 默认
 * 跳过；本地 {@code ./mvnw -pl rag test -Dtest=KeyDateIcsFeedPgIt -Dpolyu.pg.it=1}）。
 * Mockito 单测覆盖不到的部分：ics_export_state/ics_last_dates 真写径（唯一写径
 * 铁律的库面反证——导出后非 ICS 列逐字段不变）、改期/撤回/恢复全生命周期真表
 * 往返、PG VARCHAR/DATE 直读。每用例前后全清自净（判例：同库连跑两次绿；只认
 * 本用例自插行）。
 */
@EnabledIfSystemProperty(named = "polyu.pg.it", matches = "1")
class KeyDateIcsFeedPgIt {

    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");
    private static final LocalDate FEE_DAY = LocalDate.of(2026, 10, 13);

    private static KeyDateMapper keyDateMapper;
    private static KeyDateSourceMapper sourceMapper;
    private static KeyDateIcsFeedService service;

    @BeforeAll
    static void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getProperty("polyu.pg.it.url", "jdbc:postgresql://127.0.0.1:5434/ragent"));
        dataSource.setUser(System.getProperty("polyu.pg.it.user", "postgres"));
        dataSource.setPassword(System.getProperty("polyu.pg.it.pass", "postgres"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("calendar-ics-it", new SpringManagedTransactionFactory(), dataSource));
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace(KeyDateMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, KeyDateDO.class);
        TableInfoHelper.initTableInfo(assistant, KeyDateSourceDO.class);
        configuration.addMapper(KeyDateMapper.class);
        configuration.addMapper(KeyDateSourceMapper.class);
        SqlSessionFactory factory = new MybatisSqlSessionFactoryBuilder().build(configuration);
        SqlSessionTemplate template = new SqlSessionTemplate(factory);
        keyDateMapper = template.getMapper(KeyDateMapper.class);
        sourceMapper = template.getMapper(KeyDateSourceMapper.class);
        // 固定锚点 2026-09-30 00:00 HKT——留存窗断言不受真实时间影响
        Clock fixed = Clock.fixed(Instant.parse("2026-09-29T16:00:00Z"), HKT);
        service = new KeyDateIcsFeedService(keyDateMapper, new IcsExportProperties(), fixed);
    }

    @AfterAll
    static void tearDown() {
        // SqlSessionTemplate 无需手动关闭
    }

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // 清种自净（判例）：260930 种子行也清——全表回到已知空态，只认本用例自插行
        keyDateMapper.delete(null);
        sourceMapper.delete(null);
    }

    private static KeyDateDO insertFeeEvent(String uid, int revision, String status,
                                            LocalDate start, LocalDateTime withdrawnAt) {
        KeyDateDO row = KeyDateDO.builder()
                .uid(uid)
                .academicYear("2026/27")
                .term("S1")
                .eventCode("fee-deadline")
                .audienceCode("current-students")
                .semanticSlot("initial")
                .sourceKey("cal-fee-payment-annual")
                .sourceUrl("https://www.polyu.edu.hk/ar/students-in-taught-programmes/annual-schedules/fee-payment/")
                .titleEn("Semester One tuition fee payment deadline (first round)")
                .titleZh("第一学期学费缴费截止（首轮）")
                .audienceText("Current Students")
                .rawText("r5:f1")
                .provenance("r5:f1")
                .precision("exact-day")
                .dateStart(start)
                .dateEnd(null)
                .status(status)
                .revision(revision)
                .firstSeenAt(LocalDateTime.of(2026, 9, 1, 7, 30))
                .lastSeenAt(LocalDateTime.of(2026, 9, 29, 7, 30))
                .withdrawnAt(withdrawnAt)
                .build();
        keyDateMapper.insert(row);
        return row;
    }

    private static KeyDateDO reload(String uid) {
        return keyDateMapper.selectList(new LambdaQueryWrapper<KeyDateDO>()
                .eq(KeyDateDO::getUid, uid)).get(0);
    }

    private static String uidLine(String hex) {
        return "UID:" + IcsCalendarWriter.UID_PREFIX + hex;
    }

    /** RFC 5545 展开折行：UID 行（23+64=87 八位组）必然折行，断言在展开面 */
    private static String unfolded(String ics) {
        return ics.replace("\r\n ", "");
    }

    @Test
    void lifecycleRescheduleWithdrawRecoverOnRealDb() {
        String uid = "a".repeat(64);

        // 1) 首次导出：published exact → exported 回写（唯一写径双列）
        insertFeeEvent(uid, 1, "published", FEE_DAY, null);
        KeyDateIcsFeedService.FeedResult first = service.exportFeed();
        assertTrue(unfolded(first.feed()).contains(uidLine(uid)));
        assertTrue(first.feed().contains("SEQUENCE:1"));
        assertTrue(first.feed().contains("STATUS:CONFIRMED"));
        assertTrue(Rfc5545Validator.violations(first.feed()).isEmpty(),
                "真库导出 feed 过 RFC 校验器：" + Rfc5545Validator.violations(first.feed()));
        KeyDateDO afterFirst = reload(uid);
        assertEquals("exported", afterFirst.getIcsExportState());
        assertEquals("exact-day|2026-10-13|", afterFirst.getIcsLastDates());

        // 2) 改期（#192 语义：同 UID 行内更新 revision 1→2、日期迁移）
        keyDateMapper.update(null, new LambdaUpdateWrapper<KeyDateDO>()
                .eq(KeyDateDO::getUid, uid)
                .set(KeyDateDO::getRevision, 2)
                .set(KeyDateDO::getDateStart, LocalDate.of(2026, 10, 20)));
        KeyDateIcsFeedService.FeedResult rescheduled = service.exportFeed();
        assertTrue(unfolded(rescheduled.feed()).contains(uidLine(uid)), "改期不换 UID（防双提醒）");
        assertTrue(rescheduled.feed().contains("SEQUENCE:2"), "revision→SEQUENCE 递增");
        assertTrue(rescheduled.feed().contains("DTSTART;VALUE=DATE:20261020"));
        assertFalse(rescheduled.feed().contains("DTSTART;VALUE=DATE:20261013"), "旧日期不残留");
        assertEquals("exact-day|2026-10-20|", reload(uid).getIcsLastDates(), "快照跟进改期后日期");

        // 3) 撤回（#192 语义：status=withdrawn、revision+1、withdrawn_at 记锚）
        keyDateMapper.update(null, new LambdaUpdateWrapper<KeyDateDO>()
                .eq(KeyDateDO::getUid, uid)
                .set(KeyDateDO::getStatus, "withdrawn")
                .set(KeyDateDO::getRevision, 3)
                .set(KeyDateDO::getWithdrawnAt, LocalDateTime.of(2026, 9, 29, 7, 30)));
        KeyDateIcsFeedService.FeedResult withdrawn = service.exportFeed();
        assertTrue(withdrawn.feed().contains("STATUS:CANCELLED"), "撤回发取消组件");
        assertTrue(unfolded(withdrawn.feed()).contains(uidLine(uid)), "取消组件同 UID");
        assertTrue(withdrawn.feed().contains("SEQUENCE:3"), "取消 SEQUENCE=撤回轮 revision（>导出轮 2）");
        KeyDateDO afterWithdraw = reload(uid);
        assertEquals("cancelled", afterWithdraw.getIcsExportState());
        assertEquals("exact-day|2026-10-20|", afterWithdraw.getIcsLastDates(), "快照保留（审计+留存期日期源）");

        // 4) 撤回留存期内（90 天窗内）重复导出：取消组件持续回放、零状态迁移
        KeyDateIcsFeedService.FeedResult retained = service.exportFeed();
        assertTrue(retained.feed().contains("STATUS:CANCELLED"));
        assertEquals("cancelled", reload(uid).getIcsExportState());
        assertEquals(withdrawn.feed().replaceFirst("DTSTAMP:\\d+T\\d+Z", ""),
                retained.feed().replaceFirst("DTSTAMP:\\d+T\\d+Z", ""),
                "留存期回放字节稳定（DTSTAMP 同源 last_seen_at 不变）");

        // 5) 恢复（#192 语义：published 回归、revision 再+1、withdrawn_at 清空）
        keyDateMapper.update(null, new LambdaUpdateWrapper<KeyDateDO>()
                .eq(KeyDateDO::getUid, uid)
                .set(KeyDateDO::getStatus, "published")
                .set(KeyDateDO::getRevision, 4)
                .set(KeyDateDO::getWithdrawnAt, null));
        KeyDateIcsFeedService.FeedResult recovered = service.exportFeed();
        assertTrue(recovered.feed().contains("STATUS:CONFIRMED"), "恢复回活跃导出");
        assertTrue(recovered.feed().contains("SEQUENCE:4"), "恢复 SEQUENCE 严格大于取消组件 3");
        assertFalse(recovered.feed().contains("STATUS:CANCELLED"), "恢复后取消组件退场");
        assertEquals("exported", reload(uid).getIcsExportState());
        assertTrue(Rfc5545Validator.violations(recovered.feed()).isEmpty());

        // 6) 唯一写径库面反证：导出四轮后非 ICS 列逐字段未动
        KeyDateDO finalRow = reload(uid);
        assertEquals(4, finalRow.getRevision());
        assertEquals("published", finalRow.getStatus());
        assertEquals(LocalDate.of(2026, 10, 20), finalRow.getDateStart());
        assertNull(finalRow.getDateEnd());
        assertEquals("exact-day", finalRow.getPrecision());
        assertEquals("第一学期学费缴费截止（首轮）", finalRow.getTitleZh());
        assertEquals(LocalDateTime.of(2026, 9, 1, 7, 30), finalRow.getFirstSeenAt());
        assertEquals(LocalDateTime.of(2026, 9, 29, 7, 30), finalRow.getLastSeenAt());
    }

    @Test
    void exactToFuzzyCancellationOnRealDb() {
        String uid = "b".repeat(64);
        // 先精确导出（建立 exported+快照），再官方改模糊（precision→fuzzy、日期清空、revision+1）
        insertFeeEvent(uid, 1, "published", FEE_DAY, null);
        service.exportFeed();
        keyDateMapper.update(null, new LambdaUpdateWrapper<KeyDateDO>()
                .eq(KeyDateDO::getUid, uid)
                .set(KeyDateDO::getRevision, 2)
                .set(KeyDateDO::getPrecision, "fuzzy")
                .set(KeyDateDO::getDateStart, null)
                .set(KeyDateDO::getDateEnd, null)
                .set(KeyDateDO::getFuzzyHint, "Middle of October 2026"));

        KeyDateIcsFeedService.FeedResult result = service.exportFeed();

        // 不留旧精确提醒：取消组件（快照日期回放）而非活跃精确事件
        assertFalse(result.feed().contains("STATUS:CONFIRMED"), "模糊行不再活跃导出");
        assertTrue(unfolded(result.feed()).contains(uidLine(uid)));
        assertTrue(result.feed().contains("STATUS:CANCELLED"));
        assertTrue(result.feed().contains("DTSTART;VALUE=DATE:20261013"),
                "取消组件 DTSTART=ics_last_dates 快照（行内日期已清空）");
        KeyDateDO after = reload(uid);
        assertEquals("cancelled", after.getIcsExportState());
        assertEquals("exact-day|2026-10-13|", after.getIcsLastDates());
        assertTrue(Rfc5545Validator.violations(result.feed()).isEmpty());

        // 留存窗（锚=last_seen_at 2026-09-29，窗内）回放仍发——不留旧精确提醒的持续面
        assertTrue(service.exportFeed().feed().contains("STATUS:CANCELLED"));
    }

    @Test
    void archivedAndNeverExportedRowsOnRealDb() {
        // archived（此前已导出）：继续活跃导出、绝不发 CANCELLED、零状态写
        String archivedUid = "c".repeat(64);
        KeyDateDO archived = insertFeeEvent(archivedUid, 1, "archived",
                LocalDate.of(2026, 6, 24), null);
        keyDateMapper.update(null, new LambdaUpdateWrapper<KeyDateDO>()
                .eq(KeyDateDO::getUid, archivedUid)
                .set(KeyDateDO::getIcsExportState, "exported")
                .set(KeyDateDO::getIcsLastDates, "exact-day|2026-06-24|"));
        // 从未导出的 withdrawn：无组件无写径
        String neverUid = "d".repeat(64);
        insertFeeEvent(neverUid, 2, "withdrawn", FEE_DAY, LocalDateTime.of(2026, 9, 29, 7, 30));

        KeyDateIcsFeedService.FeedResult result = service.exportFeed();

        assertTrue(unfolded(result.feed()).contains(uidLine(archivedUid)));
        assertTrue(result.feed().contains("STATUS:CONFIRMED"));
        assertFalse(result.feed().contains("STATUS:CANCELLED"), "归档不发 CANCELLED");
        assertFalse(unfolded(result.feed()).contains(uidLine(neverUid)), "从未导出的撤回行零组件");
        assertEquals("exported", reload(archivedUid).getIcsExportState(), "归档行状态不变（零写）");
        assertNull(reload(neverUid).getIcsExportState(), "从未导出行不被导出触碰");
        assertEquals(archived.getRevision(), reload(archivedUid).getRevision());
        List<String> violations = Rfc5545Validator.violations(result.feed());
        assertTrue(violations.isEmpty(), "混合态 feed 过校验器：" + violations);
    }
}
