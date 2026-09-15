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

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

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
 * 抓取编排测试：回灌窗口、url_hash 幂等去重、
 * 单源批上限、失败滞回（连续 3 败自动禁源、成功清零）；窗口/上限外置
 * NewsFetchProperties（默认 14/50，历史回灌临时调大）。
 * Mapper 全 mock，不触库；真实抓取入 dev PG 的端到端验证另记上线核验记录。
 */
class NewsFetchServiceTests {

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
        // 默认无既有条目
        when(itemMapper.selectList(any())).thenReturn(new ArrayList<>());
    }

    private NewsSourceDO source(int id, int consecutiveFailures) {
        return NewsSourceDO.builder()
                .id((long) id)
                .sourceKey("stub-source")
                .platform("official")
                .fetchEndpoint("https://example.com/list")
                .fetchStrategy("HTML_LIST")
                .enabled(true)
                .consecutiveFailures(consecutiveFailures)
                .build();
    }

    private RawNewsItem item(String slug, long ageMillis) {
        String url = "https://example.com/news/" + slug;
        return new RawNewsItem(url, NewsUrlNormalizer.urlHash(url),
                "title-" + slug, null, "en", new Date(now.getTime() - ageMillis), null, "stub-source");
    }

    @Test
    void insertsOnlyInWindowAndNewItems() {
        long day = 24L * 3600 * 1000;
        String edgeUrl = "https://example.com/news/edge-exactly-14d";
        fetcher.items = List.of(
                item("fresh", 1 * day),
                new RawNewsItem(edgeUrl, NewsUrlNormalizer.urlHash(edgeUrl), "edge", null, "en",
                        new Date(now.getTime() - 14L * day), null, "stub-source"),   // 恰 14 天（≥ 下界保留）
                item("too-old", 15 * day),
                new RawNewsItem("https://example.com/news/no-date", NewsUrlNormalizer.urlHash("https://example.com/news/no-date"),
                        "no-date", null, "en", null, null, "stub-source"));            // publishTime=null 跳过
        NewsItemDO existing = new NewsItemDO();
        existing.setUrlHash(NewsUrlNormalizer.urlHash(edgeUrl));
        when(itemMapper.selectList(any())).thenReturn(List.of(existing));

        int inserted = service.fetchAndPersist(source(1, 0));

        assertEquals(1, inserted);
        verify(itemMapper, times(1)).insert(any(NewsItemDO.class));
    }

    @Test
    void batchCapLimitsInsertsPerSource() {
        long fresh = 24L * 3600 * 1000;
        List<RawNewsItem> many = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            many.add(item("slug-" + i, fresh));
        }
        fetcher.items = many;

        int inserted = service.fetchAndPersist(source(1, 0));

        assertEquals(50, inserted);
        verify(itemMapper, times(50)).insert(any(NewsItemDO.class));
    }

    @Test
    void backfillWindowWideningAdmitsOlderItems() {
        // 历史回灌口径：窗口临时调大后，默认窗口外的 60 天旧条目可入库
        long day = 24L * 3600 * 1000;
        properties.setBackfillDays(95);
        fetcher.items = List.of(item("aged-60d", 60 * day), item("ancient-120d", 120 * day));

        int inserted = service.fetchAndPersist(source(1, 0));

        assertEquals(1, inserted);
        verify(itemMapper, times(1)).insert(any(NewsItemDO.class));
    }

    @Test
    void batchCapConfigurableForBackfill() {
        long fresh = 24L * 3600 * 1000;
        properties.setMaxItemsPerSource(10);
        List<RawNewsItem> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            many.add(item("backfill-" + i, fresh));
        }
        fetcher.items = many;

        int inserted = service.fetchAndPersist(source(1, 0));

        assertEquals(10, inserted);
        verify(itemMapper, times(10)).insert(any(NewsItemDO.class));
    }

    @Test
    void successResetsNonZeroFailureCounter() {
        fetcher.items = List.of(item("a", 0));

        service.fetchAndPersist(source(1, 2));

        verify(sourceMapper, times(1)).update(any(), any());
    }

    @Test
    void successWithZeroCounterSkipsResetWrite() {
        fetcher.items = List.of(item("a", 0));

        service.fetchAndPersist(source(1, 0));

        verify(sourceMapper, never()).update(any(), any());
    }

    @Test
    void failureIncrementsCounterAndDisablesAtThreshold() {
        fetcher.failure = new NewsFetchException("HTTP 500", true);

        NewsSourceDO twoFailures = source(1, 2);
        NewsFetchException thrown = assertThrows(NewsFetchException.class,
                () -> service.fetchAndPersist(twoFailures));
        assertTrue(thrown.isTransientError());
        // 第 3 败：计数 3 + 自动禁源（失败滞回）
        assertEquals(3, twoFailures.getConsecutiveFailures());
        assertFalse(twoFailures.getEnabled());
        verify(itemMapper, never()).insert(any(NewsItemDO.class));
    }

    @Test
    void failureBelowThresholdKeepsSourceEnabled() {
        fetcher.failure = new NewsFetchException("HTTP 404", false);

        NewsSourceDO source = source(1, 0);
        assertThrows(NewsFetchException.class, () -> service.fetchAndPersist(source));
        assertEquals(1, source.getConsecutiveFailures());
        assertTrue(source.getEnabled());
    }

    @Test
    void unknownStrategyFailsAndCounts() {
        NewsSourceDO source = source(1, 0);
        source.setFetchStrategy("TELEPATHY");

        assertThrows(NewsFetchException.class, () -> service.fetchAndPersist(source));
        assertEquals(1, source.getConsecutiveFailures());
    }

    @Test
    void persistedRecordCarriesPublishedDefaults() {
        fetcher.items = List.of(item("a", 0));
        List<NewsItemDO> inserted = new ArrayList<>();
        when(itemMapper.insert(any(NewsItemDO.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        });

        service.fetchAndPersist(source(7, 0));

        assertEquals(1, inserted.size());
        NewsItemDO record = inserted.get(0);
        assertEquals(7L, record.getSourceId());
        assertEquals("published", record.getStatus());
        assertEquals("other", record.getCategory());
        assertEquals(0, record.getHeat());
        assertEquals(now, record.getFetchTime());
        assertEquals(64, record.getUrlHash().length());
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
