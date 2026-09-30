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
import com.nageoffer.ai.ragent.calendar.service.KeyDateDisplayProperties;
import com.nageoffer.ai.ragent.calendar.service.impl.KeyDateQueryServiceImpl;
import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateBoardVO;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #193 查询面真库实证（门控同 KeyDateSyncPgIt：CI 无 PG 默认跳过；本地
 * {@code ./mvnw -pl rag test -Dtest=KeyDateQueryPgIt -Dpolyu.pg.it=1}）。
 * Mockito 单测覆盖不到的部分：status IN 过滤的真实 SQL、PG DATE↔LocalDate
 * 映射（String↔DATE 必崩判例的反证）、三态聚合真表读路径。每用例前后全清
 * 自净（260930 种子行也被清——只认本用例自插行）。
 */
@EnabledIfSystemProperty(named = "polyu.pg.it", matches = "1")
class KeyDateQueryPgIt {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);

    private static KeyDateMapper keyDateMapper;
    private static KeyDateSourceMapper sourceMapper;
    private static KeyDateQueryServiceImpl service;

    @BeforeAll
    static void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(System.getProperty("polyu.pg.it.url", "jdbc:postgresql://127.0.0.1:5434/ragent"));
        dataSource.setUser(System.getProperty("polyu.pg.it.user", "postgres"));
        dataSource.setPassword(System.getProperty("polyu.pg.it.pass", "postgres"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("calendar-query-it", new SpringManagedTransactionFactory(), dataSource));
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
        KeyDateDisplayProperties properties = new KeyDateDisplayProperties();
        properties.setArchiveAfterDays(14);
        properties.setArchivedCap(120);
        // 固定锚点 2026-09-30 00:00 HKT——归档边界断言不受真实时间影响
        Clock fixed = Clock.fixed(Instant.parse("2026-09-29T16:00:00Z"), ZoneId.of("Asia/Hong_Kong"));
        service = new KeyDateQueryServiceImpl(keyDateMapper, sourceMapper, properties, fixed);
    }

    @AfterAll
    static void tearDown() {
        // SqlSessionTemplate 无需手动关闭
    }

    @AfterEach
    @BeforeEach
    void cleanTables() {
        keyDateMapper.delete(null);
        sourceMapper.delete(null);
    }

    private static void insertEvent(String uid, String precision, LocalDate start, LocalDate end, String status) {
        keyDateMapper.insert(KeyDateDO.builder()
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
                .rawText("raw " + uid)
                .provenance("r1")
                .precision(precision)
                .dateStart(start)
                .dateEnd(end)
                .fuzzyHint("fuzzy".equals(precision) ? "Late October 2026" : null)
                .status(status)
                .revision(1)
                .firstSeenAt(LocalDateTime.of(2026, 9, 1, 8, 0))
                .lastSeenAt(LocalDateTime.of(2026, 9, 29, 8, 0))
                .build());
    }

    private static void insertSource(String key, String autoState, int degradedStreak,
                                     LocalDateTime lastSuccessAt, String coverage) {
        sourceMapper.insert(KeyDateSourceDO.builder()
                .sourceKey(key)
                .sourceUrl("https://www.polyu.edu.hk/ar/" + key)
                .role("writer")
                .enabled("1")
                .autoState(autoState)
                .degradedStreak(degradedStreak)
                .probeOkStreak(0)
                .coverageAcademicYear(coverage)
                .lastSuccessAt(lastSuccessAt)
                .updatedAt(LocalDateTime.of(2026, 9, 30, 7, 35))
                .build());
    }

    @Test
    void boardRealSqlPathSectionsAndThreeStates() {
        insertSource("cal-academic-calendar", "active", 0,
                LocalDateTime.of(2026, 9, 29, 7, 31), "2026/27");
        insertSource("cal-fee-payment-annual", "active", 2,
                LocalDateTime.of(2026, 9, 20, 7, 31), "2026/27");
        insertEvent("today", "exact-day", TODAY, null, "published");
        insertEvent("upcoming", "exact-day", TODAY.plusDays(3), null, "published");
        insertEvent("recentEdge", "exact-day", TODAY.minusDays(14), null, "published");
        insertEvent("archivedEdge", "exact-day", TODAY.minusDays(15), null, "published");
        insertEvent("withdrawnRow", "exact-day", TODAY.plusDays(5), null, "withdrawn");
        insertEvent("fuzzyRow", "fuzzy", null, null, "published");

        KeyDateBoardVO board = service.board();

        // PG DATE↔LocalDate 直读（判例反证）+ 临近度序
        assertEquals(List.of("today", "upcoming"),
                board.getCurrentAndUpcoming().stream().map(v -> v.getUid()).toList());
        assertEquals(0, board.getCurrentAndUpcoming().get(0).getDaysUntil());
        assertEquals(3, board.getCurrentAndUpcoming().get(1).getDaysUntil());
        // 归档边界含端：恰 14 天前=recent，15 天前=archived（真表序）
        assertEquals(List.of("recentEdge"),
                board.getRecentPast().stream().map(v -> v.getUid()).toList());
        assertEquals(List.of("archivedEdge"),
                board.getArchived().stream().map(v -> v.getUid()).toList());
        // withdrawn 真表过滤不可见；fuzzy 进 undated（date_* null 直读）
        assertEquals(List.of("fuzzyRow"),
                board.getUndated().stream().map(v -> v.getUid()).toList());
        assertTrue(board.getUndated().get(0).getDateStart() == null);
        // 三态：一退化源 → anySourceAbnormal；lastFullSyncAt=真表 max
        assertTrue(board.isAnySourceAbnormal());
        assertEquals(LocalDateTime.of(2026, 9, 29, 7, 31), board.getLastFullSyncAt());
        assertEquals("2026/27", board.getCoverageAcademicYear());
        assertEquals(TODAY, board.getToday());
    }

    @Test
    void auditReadsRealRows() {
        insertSource("cal-academic-calendar", "active", 0,
                LocalDateTime.of(2026, 9, 29, 7, 31), "2026/27");
        insertEvent("live", "exact-day", TODAY.plusDays(5), null, "published");
        insertEvent("wd", "exact-day", TODAY.plusDays(5), null, "withdrawn");

        var audit = service.auditSources();

        assertEquals(1, audit.size());
        assertEquals("cal-academic-calendar", audit.get(0).getSourceKey());
        assertEquals(1, audit.get(0).getPublishedCount());
        assertEquals(1, audit.get(0).getWithdrawnCount());
        assertEquals(0, audit.get(0).getArchivedCount());
        // 纯读核对视图直读真表行（未写诊断的源 lastDiag 为 null）
        assertNull(audit.get(0).getLastDiag());
    }
}
