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

package com.nageoffer.ai.ragent.news.service.impl;

import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchException;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsSourceFetcher;
import com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer;
import com.nageoffer.ai.ragent.news.fetch.RawNewsItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 抓取编排两阶段测试（#185）：旧文 48h 归档、确定性首灌限量、全站日准入 60
 * （局部 200 候选只放行 60）、按源公平轮转（大源不饿死校园源）、跨日计数、
 * 重启不重排不重复（计数源自库行+确定性排序）、待富化 TTL 收尾、失败滞回。
 * Mapper 全 mock，真库 SQL 口径（日计数聚合/TTL 扫描）归 NewsPipelinePgIt。
 */
class NewsFetchServiceTests {

    private static final long HOUR = 3600L * 1000;
    private static final long DAY = 24L * HOUR;

    private NewsSourceMapper sourceMapper;
    private NewsItemMapper itemMapper;
    private StubFetcher fetcher;
    private NewsFetchService service;
    private NewsFetchProperties properties;
    private Date now;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 环境无 MyBatis-Plus 运行时，lambda wrapper 需手工初始化表元数据
        // （沿 ConversationMessageServiceImplOrderTest 先例）
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(), "");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, NewsSourceDO.class);
        sourceMapper = mock(NewsSourceMapper.class);
        itemMapper = mock(NewsItemMapper.class);
        fetcher = new StubFetcher();
        now = new Date();
        properties = new NewsFetchProperties();
        service = new NewsFetchService(List.of(fetcher), sourceMapper, itemMapper, properties, () -> now);
        // 默认无既有条目、当日无已准入
        when(itemMapper.selectList(any())).thenReturn(new ArrayList<>());
        when(itemMapper.selectMaps(any())).thenReturn(new ArrayList<>());
    }

    private NewsSourceDO source(long id, String key) {
        return NewsSourceDO.builder()
                .id(id)
                .sourceKey(key)
                .platform("official")
                .fetchEndpoint("https://example.com/list-" + key)
                .fetchStrategy("HTML_LIST")
                .enabled(true)
                .consecutiveFailures(0)
                .build();
    }

    private RawNewsItem item(String key, String slug, long ageMillis) {
        String url = "https://example.com/news/" + slug;
        return new RawNewsItem(url, NewsUrlNormalizer.urlHash(url),
                "title-" + slug, null, "en", new Date(now.getTime() - ageMillis), null, key);
    }

    private List<NewsItemDO> admit(List<NewsFetchService.SourceCandidates> batches) {
        service.admitAll(batches);
        return insertedRecords();
    }

    private List<NewsItemDO> insertedPending() {
        return insertedRecords().stream()
                .filter(r -> NewsItemStatus.PENDING.equals(r.getStatus())).toList();
    }

    private List<NewsItemDO> insertedRecords() {
        ArgumentCaptor<NewsItemDO> captor = ArgumentCaptor.forClass(NewsItemDO.class);
        org.mockito.Mockito.verify(itemMapper, org.mockito.Mockito.atLeast(0)).insert(captor.capture());
        return captor.getAllValues().stream().filter(r -> r != null).collect(Collectors.toList());
    }

    private void stubAdmittedToday(Map<Long, Long> countsBySource) {
        List<Map<String, Object>> rows = new ArrayList<>();
        countsBySource.forEach((sourceId, cnt) -> {
            Map<String, Object> row = new HashMap<>();
            row.put("source_id", sourceId);
            row.put("cnt", cnt);
            rows.add(row);
        });
        when(itemMapper.selectMaps(any())).thenReturn(rows);
    }

    // ================== 阶段一：候选与滞回（沿既有范式） ==================

    @Test
    void fetchCandidatesInMemoryDedupAndSuccessResetsFailures() {
        fetcher.items = List.of(item("stub-source", "a", HOUR), item("stub-source", "a", HOUR),
                item("stub-source", "b", HOUR));

        NewsFetchService.SourceCandidates candidates = service.fetchCandidates(source(1, "stub-source"));

        assertEquals(2, candidates.items().size(), "同源同轮 url_hash 去重");
        verify(sourceMapper, never()).update(any(), any()); // consecutiveFailures=0 时跳过清零写库
    }

    @Test
    void failureIncrementsCounterAndDisablesAtThreshold() {
        fetcher.failure = new NewsFetchException("HTTP 500", true);
        NewsSourceDO twoFailures = source(1, "stub-source");
        twoFailures.setConsecutiveFailures(2);

        NewsFetchException thrown = assertThrows(NewsFetchException.class,
                () -> service.fetchCandidates(twoFailures));
        assertTrue(thrown.isTransientError());
        assertEquals(3, twoFailures.getConsecutiveFailures());
        assertFalse(twoFailures.getEnabled());
        verify(itemMapper, never()).insert(any(NewsItemDO.class));
    }

    @Test
    void unknownStrategyFailsAndCounts() {
        NewsSourceDO source = source(1, "stub-source");
        source.setFetchStrategy("TELEPATHY");

        assertThrows(NewsFetchException.class, () -> service.fetchCandidates(source));
        assertEquals(1, source.getConsecutiveFailures());
    }

    // ================== 旧文 48h 归档与 null 发布时间不入库 ==================

    @Test
    void staleArticleBeyond48hArchivedNotAdmitted() {
        NewsFetchService.SourceCandidates batch =
                new NewsFetchService.SourceCandidates(source(1, "s"), List.of(
                        item("s", "fresh", 2 * HOUR),
                        item("s", "edge-48h", 48 * HOUR),
                        item("s", "stale-3d", 3 * DAY),
                        new RawNewsItem("https://example.com/news/no-date",
                                NewsUrlNormalizer.urlHash("https://example.com/news/no-date"),
                                "no-date", null, "en", null, null, "s")));

        List<NewsItemDO> records = admit(List.of(batch));

        assertEquals(3, records.size(), "窗口内三条入库（null 发布时间不入库）");
        assertEquals(NewsItemStatus.PENDING, statusOf(records, "fresh"), "48h 内进待富化");
        assertEquals(NewsItemStatus.PENDING, statusOf(records, "edge-48h"), "恰 48h 未「超过」——仍属新鲜（归档阈值为严格早于）");
        assertEquals(NewsItemStatus.ARCHIVED, statusOf(records, "stale-3d"), "3 天旧文归档");
    }

    @Test
    void staleBeyondBackfillWindowNotInsertedAtAll() {
        NewsFetchService.SourceCandidates batch =
                new NewsFetchService.SourceCandidates(source(1, "s"), List.of(item("s", "ancient-20d", 20 * DAY)));

        List<NewsItemDO> records = admit(List.of(batch));

        assertTrue(records.isEmpty(), "回灌窗口（默认 14 天）外不入库（lastmod/null 发布时间均不冒充首发）");
    }

    @Test
    void archivedRowsDoNotConsumeSiteAdmissionBudget() {
        stubAdmittedToday(Map.of(1L, 60L)); // 全站当日已满
        NewsFetchService.SourceCandidates batch =
                new NewsFetchService.SourceCandidates(source(1, "s"), List.of(
                        item("s", "fresh-1", HOUR), item("s", "fresh-2", 2 * HOUR),
                        item("s", "stale", 3 * DAY)));

        NewsFetchService.AdmissionResult result = service.admitAll(List.of(batch));

        assertEquals(0, result.admitted(), "全站满额不再准入 pending");
        assertEquals(1, result.archivedStale(), "归档行不受全站准入约束");
        assertEquals(NewsItemStatus.ARCHIVED, statusOf(insertedRecords(), "stale"));
    }

    // ================== 确定性首灌限量（修 HashMap 无序截断） ==================

    @Test
    void firstFillKeepsNewestDeterministicallyUnderPerSourceCap() {
        properties.getAdmissionSourceDailyCaps().put("ai-openai-news", 10);
        List<RawNewsItem> fullHistory = new ArrayList<>();
        for (int i = 0; i < 1234; i++) {
            fullHistory.add(item("ai-openai-news", "openai-" + i, HOUR + i * HOUR)); // 越后越旧
        }
        NewsFetchService.SourceCandidates batch =
                new NewsFetchService.SourceCandidates(source(7, "ai-openai-news"), fullHistory);

        List<NewsItemDO> records = admit(List.of(batch));

        List<NewsItemDO> pending = insertedPending();
        assertEquals(10, pending.size(), "首灌按每源日上限 10 截断（其余候选被截留，不入库）");
        assertEquals("title-openai-0", pending.get(0).getTitleEn(), "确定性顺序=发布时间倒序，最新一条最先准入");
        assertEquals("title-openai-9", pending.get(9).getTitleEn(), "截断保最新 10 条（1234 条全史不刷屏）");
    }

    @Test
    void shuffledInputYieldsSameAdmittedSet() {
        properties.getAdmissionSourceDailyCaps().put("ai-openai-news", 10);
        List<RawNewsItem> fullHistory = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fullHistory.add(item("ai-openai-news", "openai-" + String.format("%03d", i), HOUR + i * HOUR));
        }
        java.util.Collections.shuffle(fullHistory, new java.util.Random(42));

        List<NewsItemDO> records = admit(List.of(
                new NewsFetchService.SourceCandidates(source(7, "ai-openai-news"), fullHistory)));

        List<String> urls = insertedPending().stream().map(NewsItemDO::getUrl).sorted().toList();
        assertEquals(10, urls.size());
        assertEquals("https://example.com/news/openai-000", urls.get(0), "输入顺序不影响准入集合（确定性排序）");
        assertEquals("https://example.com/news/openai-009", urls.get(9));
    }

    // ================== 容量硬合同：全站 60 vs 局部 200 ==================

    @Test
    void siteWideCapBoundsLocalTwoHundredCandidates() {
        // 20 源 × 每源 10 条新鲜候选 = 局部 200（#180 §1 清单量级）→ 全站只准入 60
        List<NewsFetchService.SourceCandidates> batches = new ArrayList<>();
        for (int s = 0; s < 20; s++) {
            List<RawNewsItem> items = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                items.add(item("src-" + s, "src" + s + "-" + i, HOUR + i * HOUR));
            }
            batches.add(new NewsFetchService.SourceCandidates(source(100 + s, "src-" + s), items));
        }

        NewsFetchService.AdmissionResult result = service.admitAll(batches);

        assertEquals(60, result.admitted(), "局部 200 候选，全站仅准入 60");
        assertEquals(0, result.siteRemaining());
        assertEquals(20, result.deferredSources(), "轮转下 20 源各得 3 条（60/20），全部源仍留候选=截留 20 源");
        assertTrue(insertedRecords().stream().allMatch(r -> NewsItemStatus.PENDING.equals(r.getStatus())));
    }

    @Test
    void perSourceDailyCapEnforcedAcrossRounds() {
        properties.getAdmissionSourceDailyCaps().put("ai-arxiv-rss", 20);
        stubAdmittedToday(Map.of(9L, 15L)); // 当日已准入 15
        List<RawNewsItem> items = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            items.add(item("ai-arxiv-rss", "arxiv-" + i, HOUR + i * HOUR));
        }

        NewsFetchService.AdmissionResult result = service.admitAll(List.of(
                new NewsFetchService.SourceCandidates(source(9, "ai-arxiv-rss"), items)));

        assertEquals(5, result.admitted(), "单源日上限 20：已准入 15 → 本轮只补 5");
    }

    // ================== 按源公平：大源不饿死校园源 ==================

    @Test
    void roundRobinPreventsBigSourceStarvingCampusSource() {
        // 大源 30 条新鲜候选 vs 校园源 2 条；全站 60 够全部准入——公平性体现在
        // 交错入库（校园源不被挤到批上限外）；再把全站上限压到 12 验证轮转份额
        List<RawNewsItem> big = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            big.add(item("ai-arxiv-rss", "arxiv-" + i, HOUR + i * HOUR));
        }
        List<RawNewsItem> campus = List.of(item("official-sitemap", "campus-1", HOUR),
                item("official-sitemap", "campus-2", 2 * HOUR));
        properties.setAdmissionDailySiteCap(12);
        properties.getAdmissionSourceDailyCaps().put("ai-arxiv-rss", 20);

        NewsFetchService.AdmissionResult result = service.admitAll(List.of(
                new NewsFetchService.SourceCandidates(source(9, "ai-arxiv-rss"), big),
                new NewsFetchService.SourceCandidates(source(11, "official-sitemap"), campus)));

        assertEquals(12, result.admitted());
        List<NewsItemDO> records = insertedRecords();
        long campusAdmitted = records.stream().filter(r -> r.getSourceId() == 11L).count();
        assertEquals(2, campusAdmitted, "校园源 2 条全部准入——大源不能饿死校园源");
        long bigAdmitted = records.stream().filter(r -> r.getSourceId() == 9L).count();
        assertEquals(10, bigAdmitted, "大源按全站余量取得剩余份额（轮转不偏袒先来源）");
        // 轮转交错性：前 4 条入库应交替覆盖两源（每轮每源一条）
        List<Long> firstFourSources = records.subList(0, 4).stream().map(NewsItemDO::getSourceId).toList();
        assertTrue(firstFourSources.contains(9L) && firstFourSources.contains(11L),
                "前 4 条按源交错（每源每轮一票），实际=" + firstFourSources);
    }

    // ================== 跨日计数与重启不重排不重复 ==================

    @Test
    void crossDayCountingResetsSiteBudget() {
        // 第 1 日：全站准入 60（20 源×3）——首轮 60 条
        List<NewsFetchService.SourceCandidates> day1 = new ArrayList<>();
        for (int s = 0; s < 20; s++) {
            List<RawNewsItem> items = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                items.add(item("src-" + s, "d1-src" + s + "-" + i, HOUR + i * HOUR));
            }
            day1.add(new NewsFetchService.SourceCandidates(source(100 + s, "src-" + s), items));
        }
        NewsFetchService.AdmissionResult day1Result = service.admitAll(day1);
        assertEquals(60, day1Result.admitted());

        // 第 2 日（时钟跨 HKT 午夜）：计数重读库——前一日的 60 条仍在库（计入当日）？
        // 否：跨日后 fetch_time 已在昨日窗口，当日计数清零 → 新候选可再准入 60
        org.mockito.Mockito.clearInvocations(itemMapper);
        when(itemMapper.selectMaps(any())).thenReturn(new ArrayList<>()); // 第 2 日计数聚合=0
        List<NewsFetchService.SourceCandidates> day2 = List.of(
                new NewsFetchService.SourceCandidates(source(100, "src-0"),
                        List.of(item("src-0", "d2-new-1", HOUR), item("src-0", "d2-new-2", 2 * HOUR))));
        NewsFetchService.AdmissionResult day2Result = service.admitAll(day2);
        assertEquals(2, day2Result.admitted(), "跨日（HKT 日切）后全站额度重新可用");
    }

    @Test
    void restartSameDayDoesNotReAdmitOrExceedCap() {
        // 同日重跑：库内已准入 60（计数行）+ 同一批候选再出现 → 全被 url_hash 幂等跳过
        stubAdmittedToday(Map.of(1L, 60L));
        NewsFetchService.SourceCandidates batch =
                new NewsFetchService.SourceCandidates(source(1, "s"),
                        List.of(item("s", "dup-1", HOUR), item("s", "dup-2", 2 * HOUR)));

        NewsFetchService.AdmissionResult result = service.admitAll(List.of(batch));

        assertEquals(0, result.admitted(), "同日重跑零新增（额度与 url_hash 双约束）");
        assertTrue(insertedRecords().isEmpty(), "无重复插入——重启不重排不重复");
    }

    @Test
    void existingUrlReappearanceDoesNotRebuildAnyPendingRow() {
        NewsItemDO existing = new NewsItemDO();
        existing.setUrlHash(NewsUrlNormalizer.urlHash("https://example.com/news/seen-before"));
        when(itemMapper.selectList(any())).thenReturn(List.of(existing));

        List<NewsItemDO> records = admit(List.of(new NewsFetchService.SourceCandidates(source(1, "s"),
                List.of(item("s", "seen-before", HOUR)))));

        assertTrue(records.isEmpty(), "同 URL 重现（含已 expired/archived 行）不重建付费待办");
    }

    // ================== 待富化 TTL 收尾 ==================

    @Test
    void expireOverduePendingTransitionsToExpiredTerminal() {
        service.expireOverduePending();

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<NewsItemDO>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(itemMapper).update(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("status ="), "限定 pending 态，实际=" + sql);
        assertTrue(sql.contains("fetch_time <"), "TTL 从首次发现（fetch_time）起算，实际=" + sql);
    }

    @Test
    void sameSourceMultipleBatchesAggregatedWithoutDuplicateInserts() {
        // 同源拆两个批次（真库 IT 撞出的回归）：fresh 轮转与 stale 归档都按去重源迭代，
        // 归档段不得重访同一队列重复入库
        NewsSourceDO source = source(5, "s");
        List<NewsItemDO> records = admit(List.of(
                new NewsFetchService.SourceCandidates(source,
                        List.of(item("s", "fresh-a", HOUR))),
                new NewsFetchService.SourceCandidates(source,
                        List.of(item("s", "fresh-b", 2 * HOUR), item("s", "stale-c", 3 * 24 * HOUR)))));

        assertEquals(3, records.size(), "两批次候选全部入库且各一次");
        assertEquals(2, records.stream().filter(r -> NewsItemStatus.PENDING.equals(r.getStatus())).count());
        assertEquals(1, records.stream().filter(r -> NewsItemStatus.ARCHIVED.equals(r.getStatus())).count());
    }

    // ================== 落库字段 ==================

    @Test
    void pendingRecordCarriesPipelineDefaults() {
        admit(List.of(new NewsFetchService.SourceCandidates(source(7, "s"),
                List.of(item("s", "a", HOUR)))));

        NewsItemDO record = insertedRecords().get(0);
        assertEquals(7L, record.getSourceId());
        assertEquals(NewsItemStatus.PENDING, record.getStatus(), "采集直写 pending（富化前不可公开）");
        assertEquals("other", record.getCategory());
        assertEquals(0, record.getHeat());
        assertEquals(now, record.getFetchTime());
        assertEquals(64, record.getUrlHash().length());
    }

    private static String statusOf(Collection<NewsItemDO> records, String slug) {
        String url = "https://example.com/news/" + slug;
        return records.stream().filter(r -> url.equals(r.getUrl())).findFirst()
                .map(NewsItemDO::getStatus).orElse(null);
    }

    /**
     * 可编程假抓取器（绕开 HTTP 层——传输纪律由 NewsHttpFetchClientTests 覆盖）
     */
    private static final class StubFetcher implements NewsSourceFetcher {

        private List<RawNewsItem> items = List.of();
        private RuntimeException failure;

        @Override
        public String supportedStrategy() {
            return "HTML_LIST";
        }

        @Override
        public List<RawNewsItem> fetch(NewsSourceDO source) {
            if (failure != null) {
                throw failure;
            }
            return items;
        }
    }
}
