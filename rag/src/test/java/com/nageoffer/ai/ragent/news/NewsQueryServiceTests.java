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

package com.nageoffer.ai.ragent.news;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.controller.vo.NewsHotRankEntryVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsItemVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPageVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsTopicVO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.service.NewsQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公开资讯查询服务测试（查询骨架面）
 *
 * <p>跑在 polyu 本地栈（PG 5434，测试隔离层固定 workflow 档）。断言只锚定
 * 不依赖库内容快照的形状语义——空库返回空列表、不存在 category 一定空、
 * 不存在 slug 一定 404 语义、分页参数钳制——真实抓取入位后的端到端验证归集成阶段。
 */
@SpringBootTest
class NewsQueryServiceTests {

    /**
     * 不可能与固定 8 类或未来扩展类撞名的哨兵 category
     */
    private static final String NON_EXISTENT_CATEGORY = "definitely-nonexistent-category-a2";

    @Autowired
    private NewsQueryService newsQueryService;

    @Autowired
    private NewsItemMapper newsItemMapper;

    @Autowired
    private NewsSourceMapper newsSourceMapper;

    @Autowired
    private NewsTopicMapper newsTopicMapper;

    @Autowired
    private NewsItemTopicMapper newsItemTopicMapper;

    /**
     * 检索/详情探针夹具：插一行专用信源+若干条目，finally 全清——
     * 标记含随机 UUID，与库内真实数据零碰撞（探针行 status/category 均普通形状）
     */
    private static final class SearchProbe implements AutoCloseable {
        private final NewsItemMapper itemMapper;
        private final NewsSourceMapper sourceMapper;
        private final NewsTopicMapper topicMapper;
        private final NewsItemTopicMapper itemTopicMapper;
        private final NewsSourceDO probeSource;
        private final List<Long> itemIds = new ArrayList<>();
        private final List<Long> topicIds = new ArrayList<>();
        final String marker;

        SearchProbe(NewsItemMapper itemMapper, NewsSourceMapper sourceMapper,
                NewsTopicMapper topicMapper, NewsItemTopicMapper itemTopicMapper) {
            this.itemMapper = itemMapper;
            this.sourceMapper = sourceMapper;
            this.topicMapper = topicMapper;
            this.itemTopicMapper = itemTopicMapper;
            this.marker = "probe-" + UUID.randomUUID();
            this.probeSource = new NewsSourceDO();
            probeSource.setSourceKey(marker);
            probeSource.setPlatform("official");
            probeSource.setDisplayName("T9T10 探针信源");
            probeSource.setFetchEndpoint("https://example.invalid/" + marker);
            probeSource.setFetchStrategy("SITEMAP");
            sourceMapper.insert(probeSource);
        }

        /** 追加一条探针条目：titleHit=true 时标题带标记，否则仅摘要带标记 */
        long item(boolean titleHit, boolean hidden, long publishTimeEpoch) {
            return itemInCategory("campus", titleHit, hidden, publishTimeEpoch);
        }

        /** T21：指定分类的探针行（关键词×分类互通用） */
        long itemInCategory(String category, boolean titleHit, boolean hidden, long publishTimeEpoch) {
            return itemFull(category, titleHit, hidden ? "hidden" : "published", null, publishTimeEpoch);
        }

        /** #214 MCP 检索面：published 但发布门未开（eligible_time 在未来）的探针行 */
        long itemPendingGate(boolean titleHit, long publishTimeEpoch) {
            return itemFull("campus", titleHit, "published", new Date(System.currentTimeMillis() + 3_600_000L),
                    publishTimeEpoch);
        }

        /** 全形态探针行（status/eligibleTime 可指定——统一公开资格四态覆盖） */
        long itemFull(String category, boolean titleHit, String status, Date eligibleTime, long publishTimeEpoch) {
            NewsItemDO item = NewsItemDO.builder()
                    .sourceId(probeSource.getId())
                    .url("https://example.invalid/" + marker + "/" + itemIds.size())
                    .urlHash(UUID.randomUUID().toString())
                    .titleZh(titleHit ? "标题命中 " + marker : "探针标题（仅摘要命中）")
                    .titleEn(titleHit ? "title hit " + marker : "probe title")
                    .summaryZh(titleHit ? "探针摘要" : "摘要命中 " + marker)
                    .summaryEn(titleHit ? "probe summary" : "summary hit " + marker)
                    .category(category)
                    .publishTime(new Date(publishTimeEpoch))
                    .status(status)
                    .eligibleTime(eligibleTime)
                    .build();
            itemMapper.insert(item);
            itemIds.add(item.getId());
            return item.getId();
        }

        /**
         * #214 MCP 检索面：完全不带标记的行（标题/摘要都不含 marker）——
         * 关键词过滤「真不命中」对照（itemFull 的非标题行会经摘要命中 marker）
         */
        long itemUnmarked(long publishTimeEpoch) {
            NewsItemDO item = NewsItemDO.builder()
                    .sourceId(probeSource.getId())
                    .url("https://example.invalid/plain/" + UUID.randomUUID())
                    .urlHash(UUID.randomUUID().toString())
                    .titleZh("不带标记的普通标题")
                    .titleEn("plain title without marker")
                    .summaryZh("不带标记的普通摘要")
                    .summaryEn("plain summary without marker")
                    .category("campus")
                    .publishTime(new Date(publishTimeEpoch))
                    .status("published")
                    .build();
            itemMapper.insert(item);
            itemIds.add(item.getId());
            return item.getId();
        }

        /** #214 MCP 检索面：建一个策展主题并返回其 slug（随机后缀防撞库内词表） */
        String curatedTopic() {
            NewsTopicDO topic = NewsTopicDO.builder()
                    .slug(marker + "-topic")
                    .nameZh("探针主题")
                    .nameEn("probe topic")
                    .topicGroup("STUDENT_AFFAIRS")
                    .descriptionZh("探针主题（测试夹具）")
                    .descriptionEn("probe topic (test fixture)")
                    .curated(true)
                    .status(NewsTopicDO.STATUS_ACTIVE)
                    .build();
            topicMapper.insert(topic);
            topicIds.add(topic.getId());
            return topic.getSlug();
        }

        /** 把探针条目挂到 slug 对应主题（curatedTopic() 之后调用） */
        void linkToTopic(long itemId, String slug) {
            NewsTopicDO topic = topicMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NewsTopicDO>()
                            .eq(NewsTopicDO::getSlug, slug).last("LIMIT 1"));
            itemTopicMapper.insert(NewsItemTopicDO.builder().itemId(itemId).topicId(topic.getId()).build());
        }

        /**
         * 某日零点的毫秒时刻（按 JVM 默认时区墙钟构造）：与实现侧
         * Timestamp.valueOf(date.atStartOfDay()) 同一语义——探针行的落库墙钟
         * 与时间窗边界严格对齐，测试不随运行时区漂移
         */
        static long wallClock(LocalDate day) {
            return Timestamp.valueOf(day.atStartOfDay()).getTime();
        }

        @Override
        public void close() {
            itemIds.forEach(itemMapper::deleteById);
            if (!topicIds.isEmpty()) {
                itemTopicMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NewsItemTopicDO>()
                        .in(NewsItemTopicDO::getTopicId, topicIds));
            }
            topicIds.forEach(topicMapper::deleteById);
            sourceMapper.deleteById(probeSource.getId());
        }
    }

    @Test
    void listWithNonExistentCategoryReturnsEmptyPage() {
        NewsPageVO page = newsQueryService.listPublished(NON_EXISTENT_CATEGORY, 1, 20);
        assertNotNull(page);
        assertTrue(page.getRecords().isEmpty());
        assertEquals(0L, page.getTotal());
        assertEquals(1L, page.getPage());
        assertEquals(20L, page.getSize());
        assertEquals(Boolean.FALSE, page.getHasMore());
    }

    @Test
    void listNormalizesOutOfRangePaging() {
        NewsPageVO zeroPage = newsQueryService.listPublished(null, 0, 0);
        assertTrue(zeroPage.getPage() >= 1);
        assertTrue(zeroPage.getSize() >= 1 && zeroPage.getSize() <= 50);
        NewsPageVO hugeSize = newsQueryService.listPublished(null, 1, Integer.MAX_VALUE);
        assertTrue(hugeSize.getSize() <= 50);
        // 越界页码：空记录且 hasMore 收敛为 false
        NewsPageVO farPage = newsQueryService.listPublished(NON_EXISTENT_CATEGORY, 9999, 20);
        assertTrue(farPage.getRecords().isEmpty());
        assertEquals(Boolean.FALSE, farPage.getHasMore());
    }

    @Test
    void hotReturnsBoundedStoryEntries() {
        List<NewsHotRankEntryVO> hot = newsQueryService.listHot(10);
        assertNotNull(hot);
        assertTrue(hot.size() <= 10);
        // 故事线粒度形状：每条目必有热度与信源名单；同簇去重后 itemId 唯一
        Set<Long> itemIds = new HashSet<>();
        for (NewsHotRankEntryVO entry : hot) {
            assertNotNull(entry.getHeat());
            assertNotNull(entry.getSources());
            assertTrue(entry.getSources().size() >= 1);
            if (entry.getItemId() != null) {
                assertTrue(itemIds.add(entry.getItemId()), "热点榜同簇重复条目：" + entry.getItemId());
            }
        }
    }

    @Test
    void topicsExposeOnlyCuratedGroups() {
        List<NewsTopicVO> topics = newsQueryService.listCuratedTopics();
        assertNotNull(topics);
        Set<String> slugs = new HashSet<>();
        for (NewsTopicVO topic : topics) {
            assertNotNull(topic.getSlug());
            slugs.add(topic.getSlug());
            assertNotNull(topic.getTopicGroup());
            assertTrue(topic.getTopicGroup().equals("FACULTY")
                    || topic.getTopicGroup().equals("RESEARCH")
                    || topic.getTopicGroup().equals("STUDENT_AFFAIRS"),
                    "非法三维分组：" + topic.getTopicGroup());
            assertNotNull(topic.getItemCount());
            assertTrue(topic.getItemCount() >= 0);
        }
        assertEquals(topics.size(), slugs.size(), "主题 slug 必须唯一");
    }

    @Test
    void topicDetailWithUnknownSlugThrows() {
        ClientException ex = assertThrows(ClientException.class,
                () -> newsQueryService.getTopicDetail("definitely-nonexistent-slug-a2", 1, 20));
        assertEquals("主题不存在", ex.getErrorMessage());
    }

    @Test
    void topicDetailWithBlankSlugThrows() {
        assertThrows(ClientException.class, () -> newsQueryService.getTopicDetail("  ", 1, 20));
    }

    // ============ 详情单条 ============

    @Test
    void detailReturnsAssembledVoForPublishedItem() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            long id = probe.item(true, false, System.currentTimeMillis());

            NewsItemVO vo = newsQueryService.getPublishedDetail(id);

            assertEquals(id, vo.getId());
            assertEquals("标题命中 " + probe.marker, vo.getTitleZh());
            assertEquals("campus", vo.getCategory());
            assertNotNull(vo.getPublishTime());
            assertNotNull(vo.getSource(), "详情应带信源元数据装配");
            assertEquals(probe.marker, vo.getSource().getSourceKey());
        }
    }

    @Test
    void detailWithUnknownOrNonPositiveIdThrows() {
        ClientException unknown = assertThrows(ClientException.class,
                () -> newsQueryService.getPublishedDetail(Long.MAX_VALUE - 1));
        assertEquals("资讯不存在", unknown.getErrorMessage());
        assertThrows(ClientException.class, () -> newsQueryService.getPublishedDetail(0L));
        assertThrows(ClientException.class, () -> newsQueryService.getPublishedDetail(-1L));
    }

    @Test
    void detailHidesHiddenItemWithSameNotFoundSemantics() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            long id = probe.item(true, true, System.currentTimeMillis());

            ClientException ex = assertThrows(ClientException.class, () -> newsQueryService.getPublishedDetail(id));
            assertEquals("资讯不存在", ex.getErrorMessage());
        }
    }

    // ============ 全局检索 ============

    @Test
    void searchWithBlankQueryReturnsContractEmptyPage() {
        NewsPageVO blank = newsQueryService.searchPublished("   ", "time", "desc", null, 1, 20);
        assertTrue(blank.getRecords().isEmpty());
        assertEquals(0L, blank.getTotal());
        assertEquals(Boolean.FALSE, blank.getHasMore());
    }

    @Test
    void searchMatchesTitleAndSummaryAcrossPublishedOnly() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            long titleHitId = probe.item(true, false, 1_000_000_000_000L);
            probe.item(false, false, 2_000_000_000_000L);
            probe.item(true, true, 3_000_000_000_000L);

            NewsPageVO page = newsQueryService.searchPublished(probe.marker, "time", "desc", null, 1, 20);

            assertEquals(2L, page.getTotal(), "标题命中+摘要命中两条可见，hidden 不入结果");
            Set<Long> ids = page.getRecords().stream().map(NewsItemVO::getId).collect(Collectors.toSet());
            assertTrue(ids.contains(titleHitId));
        }
    }

    @Test
    void searchRelevanceRanksTitleHitsAheadOfSummaryHits() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            // 时间倒序应为：summary(newest) > titleOld > titleNew；relevance 期望 titleNew > titleOld > summary
            long titleNew = probe.item(true, false, 1_000_000_000_000L);
            long titleOld = probe.item(true, false, 500_000_000_000L);
            long summaryNewest = probe.item(false, false, 9_000_000_000_000L);

            NewsPageVO relevance = newsQueryService.searchPublished(probe.marker, "relevance", "desc", null, 1, 20);
            List<Long> got = relevance.getRecords().stream().map(NewsItemVO::getId).toList();
            assertEquals(List.of(titleNew, titleOld, summaryNewest), got);

            // time 档同数据按发布时间倒序（默认档与未知取值同形）
            NewsPageVO time = newsQueryService.searchPublished(probe.marker, "time", "desc", null, 1, 20);
            assertEquals(List.of(summaryNewest, titleNew, titleOld),
                    time.getRecords().stream().map(NewsItemVO::getId).toList());
            NewsPageVO unknownSort = newsQueryService.searchPublished(probe.marker, "bogus-sort", "desc", null, 1, 20);
            assertEquals(time.getRecords().stream().map(NewsItemVO::getId).toList(),
                    unknownSort.getRecords().stream().map(NewsItemVO::getId).toList());
        }
    }

    @Test
    void searchPaginatesWithHasMore() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            probe.item(true, false, 1_000_000_000_000L);
            probe.item(true, false, 2_000_000_000_000L);
            probe.item(true, false, 3_000_000_000_000L);

            NewsPageVO first = newsQueryService.searchPublished(probe.marker, "time", "desc", null, 1, 1);
            assertEquals(1, first.getRecords().size());
            assertEquals(3L, first.getTotal());
            assertEquals(Boolean.TRUE, first.getHasMore());

            NewsPageVO third = newsQueryService.searchPublished(probe.marker, "time", "desc", null, 3, 1);
            assertEquals(1, third.getRecords().size());
            assertEquals(Boolean.FALSE, third.getHasMore());
        }
    }

    @Test
    void searchTreatsLikeWildcardsAsLiteral() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            probe.item(true, false, System.currentTimeMillis());

            // % 按字面：未转义时 marker+"%" 通配会撞出标题含 marker 的探针行
            NewsPageVO literal = newsQueryService.searchPublished(probe.marker + "%", "time", "desc", null, 1, 20);
            assertTrue(literal.getRecords().isEmpty());
            // _ 按字面：把首个连字符换成下划线构造 needle——未转义时单字符通配恰好匹配该连字符
            String underscoreNeedle = "probe_" + probe.marker.substring("probe-".length());
            NewsPageVO underscore = newsQueryService.searchPublished(underscoreNeedle, "time", "desc", null, 1, 20);
            assertTrue(underscore.getRecords().isEmpty(), "下划线应按字面匹配而非单字符通配");
        }
    }

    /**
     * T21：time 档方向翻转——asc=最旧在前（与默认 desc 互为镜像）
     */
    @Test
    void searchTimeOrderAscFlipsToOldestFirst() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            long old = probe.item(true, false, 1_000_000_000_000L);
            long mid = probe.item(true, false, 5_000_000_000_000L);
            long newest = probe.item(true, false, 9_000_000_000_000L);

            NewsPageVO desc = newsQueryService.searchPublished(probe.marker, "time", "desc", null, 1, 20);
            assertEquals(List.of(newest, mid, old), desc.getRecords().stream().map(NewsItemVO::getId).toList());

            NewsPageVO asc = newsQueryService.searchPublished(probe.marker, "time", "asc", null, 1, 20);
            assertEquals(List.of(old, mid, newest), asc.getRecords().stream().map(NewsItemVO::getId).toList());
        }
    }

    /**
     * T21：relevance 档 order 贯通——asc 时桶序翻转（非标题命中在前）+桶内时间正序
     */
    @Test
    void searchRelevanceOrderAscFlipsBucketAndTime() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            long titleNew = probe.item(true, false, 9_000_000_000_000L);
            long titleOld = probe.item(true, false, 1_000_000_000_000L);
            long summaryNewest = probe.item(false, false, 9_500_000_000_000L);
            long summaryOldest = probe.item(false, false, 500_000_000_000L);

            NewsPageVO asc = newsQueryService.searchPublished(probe.marker, "relevance", "asc", null, 1, 20);
            assertEquals(List.of(summaryOldest, summaryNewest, titleOld, titleNew),
                    asc.getRecords().stream().map(NewsItemVO::getId).toList(),
                    "asc=最不相关在前（非标题命中桶在前、桶内时间正序）");
        }
    }

    /**
     * T21：关键词×分类互通——category 限定检索范围（eq 谓词），全部/其他分类命中被滤掉
     */
    @Test
    void searchCategoryLimitsScopeAndKeepsKeyword() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            long campusHit = probe.item(true, false, 3_000_000_000_000L);
            probe.itemInCategory("admission", true, false, 5_000_000_000_000L);

            NewsPageVO scoped = newsQueryService.searchPublished(probe.marker, "time", "desc", "campus", 1, 20);
            assertEquals(List.of(campusHit), scoped.getRecords().stream().map(NewsItemVO::getId).toList());

            NewsPageVO all = newsQueryService.searchPublished(probe.marker, "time", "desc", null, 1, 20);
            assertEquals(2L, all.getTotal(), "不带 category 时两类行都在");
        }
    }

    // ============ MCP 出口受限检索（#214：关键词 × 主题 × 时间窗） ============

    /**
     * 功能矩阵主路径：三元过滤取交集，隐藏/未过门条目隔离（#180 R4 可见性合同）
     */
    @Test
    void mcpSearchCombinesKeywordTopicAndWindow() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            LocalDate from = LocalDate.of(2026, 9, 20);
            LocalDate to = LocalDate.of(2026, 9, 30);
            String slug = probe.curatedTopic();
            // 唯一应命中：关键词命中 + 挂主题 + 窗内
            long hit = probe.item(true, false, SearchProbe.wallClock(LocalDate.of(2026, 9, 25)));
            probe.linkToTopic(hit, slug);
            // 同关键词但未挂主题
            probe.item(true, false, SearchProbe.wallClock(LocalDate.of(2026, 9, 26)));
            // 挂主题在窗内但关键词不命中（用不带标记的行——普通探针行会经摘要命中 marker）
            long offKeyword = probe.itemUnmarked(SearchProbe.wallClock(LocalDate.of(2026, 9, 26)));
            probe.linkToTopic(offKeyword, slug);
            // 挂主题+关键词命中但窗外（早于 from）
            long offWindow = probe.item(true, false, SearchProbe.wallClock(LocalDate.of(2026, 9, 10)));
            probe.linkToTopic(offWindow, slug);
            // 挂主题+关键词命中+窗内但 hidden（下架隔离）
            long hidden = probe.item(true, true, SearchProbe.wallClock(LocalDate.of(2026, 9, 27)));
            probe.linkToTopic(hidden, slug);
            // 挂主题+关键词命中+窗内但发布门未开（未过门隔离）
            long gated = probe.itemPendingGate(true, SearchProbe.wallClock(LocalDate.of(2026, 9, 28)));
            probe.linkToTopic(gated, slug);

            NewsPageVO page = newsQueryService.searchPublishedForMcp(probe.marker, slug, from, to, 1, 20);

            assertEquals(1L, page.getTotal(), "三元交集只剩 1 条，hidden/未过门一律不可见");
            assertEquals(List.of(hit), page.getRecords().stream().map(NewsItemVO::getId).toList());
        }
    }

    /**
     * 全缺省=时间倒序首页（同 list 语义），分页钳制面共用（size ≤50）。
     * 探针行用未来时间戳顶到首页最前——库里真实数据不干扰断言
     */
    @Test
    void mcpSearchWithoutFiltersListsPublishedByTime() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            long dayMillis = 86_400_000L;
            long now = System.currentTimeMillis();
            long older = probe.item(true, false, now + 5 * dayMillis);
            long newer = probe.item(true, false, now + 6 * dayMillis);

            NewsPageVO page = newsQueryService.searchPublishedForMcp(null, null, null, null, 1, 20);
            List<Long> ids = page.getRecords().stream().map(NewsItemVO::getId).toList();
            assertTrue(ids.contains(newer) && ids.contains(older), "无过滤时探针行都在");
            assertTrue(ids.indexOf(newer) < ids.indexOf(older), "发布时间倒序");

            NewsPageVO bounded = newsQueryService.searchPublishedForMcp(null, null, null, null, 1, Integer.MAX_VALUE);
            assertTrue(bounded.getSize() <= 50, "size 钳制与 list 同口径");
        }
    }

    /**
     * 时间窗端点含当日：from 当天零点与 to 当天尾条都在窗内
     */
    @Test
    void mcpSearchWindowBoundsAreInclusive() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            LocalDate from = LocalDate.of(2026, 9, 20);
            LocalDate to = LocalDate.of(2026, 9, 30);
            long dayStart = probe.item(true, false, SearchProbe.wallClock(from));
            long dayEnd = probe.item(true, false, SearchProbe.wallClock(to) + 86_399_000L);
            probe.item(true, false, SearchProbe.wallClock(from.minusDays(1)));
            probe.item(true, false, SearchProbe.wallClock(to.plusDays(1)));

            NewsPageVO page = newsQueryService.searchPublishedForMcp(probe.marker, null, from, to, 1, 20);

            Set<Long> ids = page.getRecords().stream().map(NewsItemVO::getId).collect(Collectors.toSet());
            assertEquals(Set.of(dayStart, dayEnd), ids, "两端当日含入，窗外排除");
        }
    }

    @Test
    void mcpSearchWithInvertedWindowThrows() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            ClientException ex = assertThrows(ClientException.class, () -> newsQueryService.searchPublishedForMcp(
                    probe.marker, null, LocalDate.of(2026, 9, 30), LocalDate.of(2026, 9, 20), 1, 20));
            assertEquals("时间窗不合法：起始日期晚于结束日期", ex.getErrorMessage());
        }
    }

    /**
     * 未知主题同形「主题不存在」（与 getTopicDetail 一致，不泄漏存在性）——
     * 含已 merged/rejected/AI 提案（curated=false）主题
     */
    @Test
    void mcpSearchWithUnknownTopicThrows() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            String slug = probe.curatedTopic();
            // 同 slug 改成 AI 提案（curated=false）：对 MCP 出口应视为不可用主题
            NewsTopicDO loaded = newsTopicMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NewsTopicDO>()
                            .eq(NewsTopicDO::getSlug, slug).last("LIMIT 1"));
            loaded.setCurated(false);
            newsTopicMapper.updateById(loaded);

            ClientException unknown = assertThrows(ClientException.class,
                    () -> newsQueryService.searchPublishedForMcp(null, "definitely-nonexistent-slug-a2", null, null, 1, 20));
            assertEquals("主题不存在", unknown.getErrorMessage());
            ClientException proposed = assertThrows(ClientException.class,
                    () -> newsQueryService.searchPublishedForMcp(null, slug, null, null, 1, 20));
            assertEquals("主题不存在", proposed.getErrorMessage());
        }
    }

    /**
     * 主题单用（无关键词）：挂主题条目按时间倒序，未挂条目不出现
     */
    @Test
    void mcpSearchTopicAloneFiltersByLinkage() {
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper, newsTopicMapper, newsItemTopicMapper)) {
            String slug = probe.curatedTopic();
            long linkedNewer = probe.item(false, false, 9_000_000_000_000L);
            long linkedOlder = probe.item(false, false, 1_000_000_000_000L);
            probe.linkToTopic(linkedNewer, slug);
            probe.linkToTopic(linkedOlder, slug);
            probe.item(false, false, 5_000_000_000_000L);

            NewsPageVO page = newsQueryService.searchPublishedForMcp(null, slug, null, null, 1, 20);

            assertEquals(List.of(linkedNewer, linkedOlder),
                    page.getRecords().stream().map(NewsItemVO::getId).toList(),
                    "只挂主题的条目入选且时间倒序，未挂条目不出现");
        }
    }
}
