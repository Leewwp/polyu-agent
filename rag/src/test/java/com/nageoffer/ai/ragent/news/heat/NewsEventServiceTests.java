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

package com.nageoffer.ai.ragent.news.heat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventMigrationDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsEventSourceVoteDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件最小模型测试（#187 身份规则四态+独立来源投票+48h 证据窗+24h 半衰）：
 * 未合并/分裂同事件 ID 稳定；合并选存续（最早首报）记旧→存续；分裂原 ID 留
 * 确定原组（含最早成员）其余新 ID+迁移；改组/下架处理证据与热度。四表用
 * {@link FakeNewsEventStore}（内存求值），条目热度写经 captor 断言字段级。
 */
class NewsEventServiceTests {

    private static final long HOUR = 3600L * 1000;
    private static final Date T0 = new Date(1757548800000L);

    private FakeNewsEventStore store;
    private NewsItemMapper itemMapper;
    private NewsStoryAssembler assembler;
    private NewsHeatProperties properties;
    private final AtomicReference<Date> clock = new AtomicReference<>(T0);
    private final AtomicReference<NewsStoryAssembler.NewsStoryWindow> windowRef = new AtomicReference<>();
    private NewsEventService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        store = new FakeNewsEventStore();
        itemMapper = mock(NewsItemMapper.class);
        assembler = mock(NewsStoryAssembler.class);
        properties = new NewsHeatProperties();
        properties.setSourceWeights(new HashMap<>(Map.of("media-releases", 3, "sao-news", 3, "prn", 2)));
        when(assembler.loadPublished(any())).thenAnswer(invocation -> windowRef.get());
        service = new NewsEventService(itemMapper, store.eventMapper, store.eventItemMapper,
                store.voteMapper, store.migrationMapper, assembler, new NewsStoryClusterer(),
                new NewsHeatService(properties), properties, (Supplier<Date>) clock::get);
    }

    // ================== 形状助手 ==================

    /**
     * 可合并同事件形状：同分类(event)+共享主题+同锚定日（summary 内 10月10日）
     * +有效摘要+词面达标——anchor 参数控制锚定日（不同即类3 负例形状，天然分簇）
     */
    private NewsStoryItem storyItem(long id, long sourceId, Date publish, String anchor, int storedHeat) {
        return new NewsStoryItem(id, "理大活动报道", null, "event", sourceId, publish,
                Set.of(1L), storedHeat,
                "香港理工大学于" + anchor + "举行活动，欢迎师生校友参与，现场设有展览与分享环节。", null);
    }

    private NewsSourceDO source(long id, String key, String group) {
        return NewsSourceDO.builder().id(id).sourceKey(key).platform("official")
                .independenceGroup(group).build();
    }

    private void windowOf(Map<Long, NewsSourceDO> sources, NewsStoryItem... items) {
        windowRef.set(new NewsStoryAssembler.NewsStoryWindow(List.of(items), sources));
    }

    private Map<Long, NewsSourceDO> defaultSources() {
        return Map.of(
                11L, source(11L, "media-releases", "polyu-official"),
                12L, source(12L, "sao-news", "polyu-official"),
                13L, source(13L, "prn", "prn-wire"));
    }

    private Map<Long, NewsEventItemDO> participationsByItem() {
        Map<Long, NewsEventItemDO> byItem = new HashMap<>();
        for (NewsEventItemDO row : store.items()) {
            byItem.put(row.getItemId(), row);
        }
        return byItem;
    }

    // ================== 身份规则四态 ==================

    @Test
    void eventIdStableAcrossRoundsWithoutMergeOrSplit() {
        Map<Long, NewsSourceDO> sources = defaultSources();
        windowOf(sources, storyItem(1, 11L, T0, "10月10日", 0),
                storyItem(2, 12L, new Date(T0.getTime() - 2 * HOUR), "10月10日", 0));
        NewsEventService.RegroupResult first = service.regroupEvents();
        Long eventId = participationsByItem().get(1L).getEventId();

        NewsEventService.RegroupResult second = service.regroupEvents();

        assertEquals(1, first.created(), "首轮 1 簇新建 1 事件");
        assertEquals(0, second.created(), "次轮无新建");
        assertEquals(0, second.merges());
        assertEquals(0, second.splits());
        assertEquals(0, second.regroups());
        assertEquals(eventId, participationsByItem().get(1L).getEventId(), "同事件 ID 稳定");
        assertEquals(eventId, participationsByItem().get(2L).getEventId());
        assertEquals(1, store.events().stream().filter(e -> NewsEventDO.STATUS_ACTIVE.equals(e.getStatus())).count());
        assertTrue(store.migrations().isEmpty(), "无身份变动零迁移");
    }

    @Test
    void mergePicksEarliestFirstReportSurvivorAndRecordsMigration() {
        Map<Long, NewsSourceDO> sources = defaultSources();
        // 首轮：两簇两事件（E1 首报更早：a1 于 T0-30h）
        windowOf(sources,
                storyItem(1, 11L, new Date(T0.getTime() - 30 * HOUR), "10月10日", 0),
                storyItem(2, 12L, new Date(T0.getTime() - 28 * HOUR), "10月10日", 0),
                storyItem(3, 13L, new Date(T0.getTime() - 2 * HOUR), "9月1日", 0),
                storyItem(4, 13L, new Date(T0.getTime() - HOUR), "9月1日", 0));
        service.regroupEvents();
        Long earlyEvent = participationsByItem().get(1L).getEventId();
        Long lateEvent = participationsByItem().get(3L).getEventId();
        assertNotEquals(earlyEvent, lateEvent);

        // 次轮：词面更新后四条聚为一簇（b 组锚定日改为同日）→ 两事件合并
        windowOf(sources,
                storyItem(1, 11L, new Date(T0.getTime() - 30 * HOUR), "10月10日", 0),
                storyItem(2, 12L, new Date(T0.getTime() - 28 * HOUR), "10月10日", 0),
                storyItem(3, 13L, new Date(T0.getTime() - 2 * HOUR), "10月10日", 0),
                storyItem(4, 13L, new Date(T0.getTime() - HOUR), "10月10日", 0));
        NewsEventService.RegroupResult result = service.regroupEvents();

        assertEquals(1, result.merges(), "两事件并入一簇=1 次合并");
        assertEquals(earlyEvent, participationsByItem().get(3L).getEventId(), "存续=最早首报事件");
        NewsEventDO superseded = store.events().stream()
                .filter(e -> e.getId().equals(lateEvent)).findFirst().orElseThrow();
        assertEquals(NewsEventDO.STATUS_SUPERSEDED, superseded.getStatus(), "非存续方转 superseded");
        assertTrue(store.votes().stream().noneMatch(v -> v.getEventId().equals(lateEvent)),
                "被合并事件投票账清空");
        List<NewsEventMigrationDO> merges = store.migrations().stream()
                .filter(m -> NewsEventMigrationDO.KIND_MERGE.equals(m.getKind())).toList();
        assertEquals(1, merges.size());
        assertEquals(lateEvent, merges.get(0).getOldEventId());
        assertEquals(earlyEvent, merges.get(0).getNewEventId());
        // 四成员同挂存续事件
        assertEquals(4, store.items().stream()
                .filter(i -> i.getEventId().equals(earlyEvent)).count());
    }

    @Test
    void splitKeepsOriginalIdForClusterWithEarliestMember() {
        Map<Long, NewsSourceDO> sources = defaultSources();
        windowOf(sources,
                storyItem(1, 11L, new Date(T0.getTime() - 3 * HOUR), "10月10日", 0),
                storyItem(2, 12L, new Date(T0.getTime() - 2 * HOUR), "10月10日", 0));
        service.regroupEvents();
        Long original = participationsByItem().get(1L).getEventId();
        assertEquals(original, participationsByItem().get(2L).getEventId());

        // 次轮：条目 2 词面更新（锚定日变化）→ 原簇分裂为两簇
        windowOf(sources,
                storyItem(1, 11L, new Date(T0.getTime() - 3 * HOUR), "10月10日", 0),
                storyItem(2, 12L, new Date(T0.getTime() - 2 * HOUR), "9月1日", 0));
        NewsEventService.RegroupResult result = service.regroupEvents();

        assertEquals(1, result.splits(), "分裂迁出 1 条");
        assertEquals(original, participationsByItem().get(1L).getEventId(),
                "原 ID 留给含最早成员（条目 1，T0-3h 首报）的确定原组");
        Long splinter = participationsByItem().get(2L).getEventId();
        assertNotEquals(original, splinter);
        NewsEventMigrationDO migration = store.migrations().stream()
                .filter(m -> NewsEventMigrationDO.KIND_SPLIT.equals(m.getKind())).findFirst().orElseThrow();
        assertEquals(original, migration.getOldEventId());
        assertEquals(splinter, migration.getNewEventId());
        assertEquals(2L, migration.getItemId());
    }

    @Test
    void regroupMovesItemBetweenSurvivingEvents() {
        Map<Long, NewsSourceDO> sources = defaultSources();
        windowOf(sources,
                storyItem(1, 11L, new Date(T0.getTime() - 4 * HOUR), "10月10日", 0),
                storyItem(2, 12L, new Date(T0.getTime() - 3 * HOUR), "10月10日", 0),
                storyItem(3, 13L, new Date(T0.getTime() - 5 * HOUR), "9月1日", 0));
        service.regroupEvents();
        Long eventA = participationsByItem().get(1L).getEventId();
        Long eventB = participationsByItem().get(3L).getEventId();
        assertNotEquals(eventA, eventB);

        // 次轮：条目 2 词面改锚到 B 事件簇（B 首报更早 T0-5h → B 事件 keeper=该簇）
        windowOf(sources,
                storyItem(1, 11L, new Date(T0.getTime() - 4 * HOUR), "10月10日", 0),
                storyItem(2, 12L, new Date(T0.getTime() - 3 * HOUR), "9月1日", 0),
                storyItem(3, 13L, new Date(T0.getTime() - 5 * HOUR), "9月1日", 0));
        NewsEventService.RegroupResult result = service.regroupEvents();

        assertEquals(1, result.regroups(), "条目 2 在存活事件间改组");
        assertEquals(0, result.splits());
        assertEquals(eventB, participationsByItem().get(2L).getEventId());
        assertEquals(eventA, participationsByItem().get(1L).getEventId(), "A 事件存活保留条目 1");
        NewsEventMigrationDO migration = store.migrations().stream()
                .filter(m -> NewsEventMigrationDO.KIND_REGROUP.equals(m.getKind())).findFirst().orElseThrow();
        assertEquals(eventA, migration.getOldEventId());
        assertEquals(eventB, migration.getNewEventId());
        assertEquals(2L, migration.getItemId());
    }

    @Test
    void detachOnTerminalStatusRemovesEvidenceAndZeroesHeat() {
        Map<Long, NewsSourceDO> sources = defaultSources();
        windowOf(sources, storyItem(1, 11L, T0, "10月10日", 0));
        service.regroupEvents();
        Long eventId = participationsByItem().get(1L).getEventId();
        assertEquals(1, store.votes().size());

        // 次轮：条目转终态（下架）→ 不在发布窗，摘除证据
        windowOf(sources);
        NewsEventService.RegroupResult result = service.regroupEvents();

        assertEquals(1, result.detached());
        assertTrue(store.items().isEmpty(), "参与者证据行摘除");
        assertTrue(store.votes().isEmpty(), "投票账撤空");
        NewsEventMigrationDO migration = store.migrations().stream()
                .filter(m -> NewsEventMigrationDO.KIND_DETACH.equals(m.getKind())).findFirst().orElseThrow();
        assertEquals(eventId, migration.getOldEventId());
        assertEquals(1L, migration.getItemId());
        NewsEventDO event = store.events().stream().filter(e -> e.getId().equals(eventId)).findFirst().orElseThrow();
        assertEquals(NewsEventDO.STATUS_ACTIVE, event.getStatus(), "空置事件保留审计不删");
        assertEquals(0, event.getHeat());
    }

    // ================== 独立来源映射+48h 证据窗+半衰 ==================

    @Test
    void multiFeedSameIndependenceGroupCountsSingleVote() {
        Map<Long, NewsSourceDO> sources = defaultSources();
        // 三个成员两独立组：media-releases+sao-news 同组 polyu-official、prn 独立组
        windowOf(sources,
                storyItem(1, 11L, T0, "10月10日", 0),
                storyItem(2, 12L, T0, "10月10日", 0),
                storyItem(3, 13L, T0, "10月10日", 0));
        service.regroupEvents();

        List<NewsEventSourceVoteDO> votes = store.votes();
        assertEquals(2, votes.size(), "同机构多 feed 不重复加票：2 独立组=2 行");
        NewsEventSourceVoteDO official = votes.stream()
                .filter(v -> "polyu-official".equals(v.getIndependenceGroup())).findFirst().orElseThrow();
        assertEquals(2, official.getVoteCount(), "组内条目数计数（不放大票权）");
        NewsEventSourceVoteDO wire = votes.stream()
                .filter(v -> "prn-wire".equals(v.getIndependenceGroup())).findFirst().orElseThrow();
        assertEquals(1, wire.getVoteCount());
        NewsEventDO event = store.events().get(0);
        // 基数=组数 2 + 组内最大权重（polyu-official 3 + prn-wire 2）=7；龄 0 → 衰减 1
        assertEquals(7, event.getHeat());
    }

    @Test
    void voteOutsideFortyEightHourWindowDoesNotCountInHeatBase() {
        Map<Long, NewsSourceDO> sources = defaultSources();
        // 首报 T0-72h；prn 组报道 T0-20h（首报后 52h，超 48h 证据窗）
        windowOf(sources,
                storyItem(1, 11L, new Date(T0.getTime() - 72 * HOUR), "10月10日", 0),
                storyItem(3, 13L, new Date(T0.getTime() - 20 * HOUR), "10月10日", 0));
        service.regroupEvents();

        assertEquals(2, store.votes().size(), "证据账全量映射（窗外组也有证据行）");
        NewsEventDO event = store.events().get(0);
        // 热度票只计 48h 窗内组：基数=1 组+权重 3=4；72h 龄 → 4×0.5^3=0.5 → round=1
        assertEquals(1, event.getHeat(), "窗外报道不计热度票（CLU-038：热度各自记账口径）");
    }

    @Test
    void heatHalvesEveryTwentyFourHoursAcrossWindows() {
        Map<Long, NewsSourceDO> sources = defaultSources();
        windowOf(sources, storyItem(1, 11L, T0, "10月10日", 0));
        service.regroupEvents();
        int day0 = store.events().get(0).getHeat();
        assertEquals(4, day0, "单官方源事件：组 1+权重 3=4，龄 0");

        clock.set(new Date(T0.getTime() + 24 * HOUR));
        service.regroupEvents();
        assertEquals(2, store.events().get(0).getHeat(), "24h 半衰：4→2");

        clock.set(new Date(T0.getTime() + 48 * HOUR));
        service.regroupEvents();
        assertEquals(1, store.events().get(0).getHeat(), "48h：2→1");
    }

    @Test
    void degradeSwitchYieldsPerItemEventsAndOwnSourceHeat() {
        properties.setStoryMergeEnabled(false);
        Map<Long, NewsSourceDO> sources = defaultSources();
        windowOf(sources,
                storyItem(1, 11L, T0, "10月10日", 0),
                storyItem(2, 13L, T0, "10月10日", 0));
        service.regroupEvents();

        assertEquals(2, store.events().size(), "降级态逐条独立成事件");
        List<Integer> heats = store.events().stream().map(NewsEventDO::getHeat).sorted().toList();
        assertEquals(List.of(3, 4), heats, "各自计自身源：官方 3+1=4 / PRN 2+1=3");
    }

    @Test
    void eventHeatWrittenToAllMemberItemsAndSkippedWhenUnchanged() {
        Map<Long, NewsSourceDO> sources = defaultSources();
        windowOf(sources,
                storyItem(1, 11L, T0, "10月10日", 0),
                storyItem(2, 12L, T0, "10月10日", 0));
        service.regroupEvents();
        // 同组两源：基数=1 组+权重 3=4 → 两成员同写 4
        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper, times(2)).update(any(), captor.capture());
        for (Wrapper<NewsItemDO> wrapper : captor.getAllValues()) {
            Map<String, Object> params = ((LambdaUpdateWrapper<NewsItemDO>) wrapper).getParamNameValuePairs();
            assertTrue(params.containsValue(4), "同簇成员写入同一事件热度，实际=" + params);
        }

        // 次轮：窗口条目已持久化热度 4（跳写基线）→ 零 UPDATE
        windowOf(sources,
                storyItem(1, 11L, T0, "10月10日", 4),
                storyItem(2, 12L, T0, "10月10日", 4));
        service.regroupEvents();
        verify(itemMapper, times(2)).update(any(), any(Wrapper.class));
    }

    @Test
    void participationSnapshotRecordsIndependenceGroupAndEvidenceTime() {
        Map<Long, NewsSourceDO> sources = defaultSources();
        windowOf(sources, storyItem(1, 11L, new Date(T0.getTime() - HOUR), "10月10日", 0));
        service.regroupEvents();

        NewsEventItemDO participation = store.items().get(0);
        assertEquals("polyu-official", participation.getIndependenceGroup(), "入组时独立组快照");
        assertEquals(new Date(T0.getTime() - HOUR), participation.getPublishTime(), "证据时刻=publish_time");
        assertNotNull(participation.getEventId());
        assertEquals(1L, participation.getItemId());
        assertEquals(11L, participation.getSourceId());
    }
}
