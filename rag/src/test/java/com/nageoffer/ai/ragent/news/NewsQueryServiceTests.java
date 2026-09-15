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
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.service.NewsQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

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

    /**
     * 检索/详情探针夹具：插一行专用信源+若干条目，finally 全清——
     * 标记含随机 UUID，与库内真实数据零碰撞（探针行 status/category 均普通形状）
     */
    private static final class SearchProbe implements AutoCloseable {
        private final NewsItemMapper itemMapper;
        private final NewsSourceMapper sourceMapper;
        private final NewsSourceDO probeSource;
        private final List<Long> itemIds = new ArrayList<>();
        final String marker;

        SearchProbe(NewsItemMapper itemMapper, NewsSourceMapper sourceMapper) {
            this.itemMapper = itemMapper;
            this.sourceMapper = sourceMapper;
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
                    .status(hidden ? "hidden" : "published")
                    .build();
            itemMapper.insert(item);
            itemIds.add(item.getId());
            return item.getId();
        }

        @Override
        public void close() {
            itemIds.forEach(itemMapper::deleteById);
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
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper)) {
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
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper)) {
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
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper)) {
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
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper)) {
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
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper)) {
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
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper)) {
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
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper)) {
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
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper)) {
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
        try (SearchProbe probe = new SearchProbe(newsItemMapper, newsSourceMapper)) {
            long campusHit = probe.item(true, false, 3_000_000_000_000L);
            probe.itemInCategory("admission", true, false, 5_000_000_000_000L);

            NewsPageVO scoped = newsQueryService.searchPublished(probe.marker, "time", "desc", "campus", 1, 20);
            assertEquals(List.of(campusHit), scoped.getRecords().stream().map(NewsItemVO::getId).toList());

            NewsPageVO all = newsQueryService.searchPublished(probe.marker, "time", "desc", null, 1, 20);
            assertEquals(2L, all.getTotal(), "不带 category 时两类行都在");
        }
    }
}
