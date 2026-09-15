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

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.news.controller.vo.NewsHotRankEntryVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPageVO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.heat.NewsHeatProperties;
import com.nageoffer.ai.ragent.news.heat.NewsStoryAssembler;
import com.nageoffer.ai.ragent.news.heat.NewsStoryItem;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 查询侧热度消费测试：/hot 故事线条目（标签/来源名单/同簇去重）、
 * 冷却期兜底、降级态、列表卡「另有 N 个来源」徽章。Mapper/装配器全 mock，
 * 时钟注入固定值（时间旅行先例）。
 */
class NewsQueryServiceImplHeatTests {

    private static final long HOUR = 3600L * 1000;
    private static final Date NOW = new Date(1757548800000L);

    private NewsItemMapper itemMapper;
    private NewsSourceMapper sourceMapper;
    private NewsStoryAssembler assembler;
    private NewsHeatProperties properties;
    private NewsQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsSourceDO.class);
        itemMapper = mock(NewsItemMapper.class);
        sourceMapper = mock(NewsSourceMapper.class);
        assembler = mock(NewsStoryAssembler.class);
        properties = new NewsHeatProperties();
        Map<String, Integer> weights = new HashMap<>();
        weights.put("official-media-release", 3);
        weights.put("prn", 2);
        weights.put("events", 3);
        properties.setSourceWeights(weights);
        service = new NewsQueryServiceImpl(itemMapper, sourceMapper, mock(NewsTopicMapper.class),
                mock(NewsItemTopicMapper.class), assembler, new com.nageoffer.ai.ragent.news.heat.NewsStoryClusterer(),
                properties, () -> NOW);
        when(itemMapper.selectList(any())).thenReturn(List.of());
    }

    private NewsStoryItem item(long id, long sourceId, long ageHours, int heat) {
        return new NewsStoryItem(id, "理大团队破解钙钛矿太阳能电池稳定性难题",
                "PolyU team cracks perovskite solar cell stability problem", "research", sourceId,
                new Date(NOW.getTime() - ageHours * HOUR), Set.of(9L), heat);
    }

    private NewsSourceDO source(long id, String key, String nameZh) {
        return NewsSourceDO.builder().id(id).sourceKey(key).platform("official")
                .displayName(nameZh).displayNameEn(key).build();
    }

    @Test
    void hotBuildsStoryEntriesWithSourcesAndTags() {
        NewsStoryItem rep = item(1, 11L, 1, 7);
        NewsStoryItem twin = item(2, 22L, 2, 7);
        NewsStoryItem solo = new NewsStoryItem(3L, "理大秋季招聘会开放报名", "Autumn career fair opens",
                "career", 33L, new Date(NOW.getTime() - 2 * HOUR), Set.of(12L), 5);
        Map<Long, NewsSourceDO> sources = Map.of(
                11L, source(11L, "official-media-release", "官网 · 媒体发布"),
                22L, source(22L, "prn", "PR Newswire"),
                33L, source(33L, "events", "官网 · 活动日历"));
        when(assembler.loadPublished(any()))
                .thenReturn(new NewsStoryAssembler.NewsStoryWindow(List.of(rep, twin, solo), sources));

        List<NewsHotRankEntryVO> hot = service.listHot(10);

        assertEquals(2, hot.size(), "同簇两条合并为一条榜单位");
        NewsHotRankEntryVO top = hot.get(0);
        assertEquals(7, top.getHeat());
        assertEquals(Long.valueOf(2L), top.getItemId(), "代表=最早发布成员（twin 2h 前首报）");
        assertEquals(List.of("官网 · 媒体发布", "PR Newswire"), top.getSources(), "权重高者先");
        assertTrue(top.getTags().contains("rise"), "双源且最新报道在 6h 内=发酵中");
        NewsHotRankEntryVO second = hot.get(1);
        assertEquals(5, second.getHeat());
        assertEquals(List.of("fresh"), second.getTags());
        assertEquals(List.of("官网 · 活动日历"), second.getSources());
    }

    @Test
    void hotFallsBackToLatestWhenWindowCold() {
        when(assembler.loadPublished(any()))
                .thenReturn(new NewsStoryAssembler.NewsStoryWindow(List.of(), Map.of()));
        NewsItemDO stale = NewsItemDO.builder().id(9L).titleZh("旧闻一条").titleEn("Old story")
                .sourceId(11L).publishTime(new Date(NOW.getTime() - 10L * 24 * HOUR))
                .status("published").heat(0).build();
        when(itemMapper.selectList(any())).thenReturn(List.of(stale));
        when(sourceMapper.selectBatchIds(any())).thenReturn(
                List.of(source(11L, "official-media-release", "官网 · 媒体发布")));

        List<NewsHotRankEntryVO> hot = service.listHot(10);

        assertEquals(1, hot.size());
        assertEquals(0, hot.get(0).getHeat());
        assertTrue(hot.get(0).getTags().isEmpty());
        assertEquals(List.of("官网 · 媒体发布"), hot.get(0).getSources());
    }

    @Test
    void hotDegradeKeepsPerItemEntries() {
        properties.setStoryMergeEnabled(false);
        NewsStoryItem rep = item(1, 11L, 1, 4);
        NewsStoryItem twin = item(2, 22L, 2, 3);
        when(assembler.loadPublished(any())).thenReturn(new NewsStoryAssembler.NewsStoryWindow(
                List.of(rep, twin),
                Map.of(11L, source(11L, "official-media-release", "官网 · 媒体发布"),
                        22L, source(22L, "prn", "PR Newswire"))));

        List<NewsHotRankEntryVO> hot = service.listHot(10);

        assertEquals(2, hot.size());
        assertEquals(List.of("官网 · 媒体发布"), hot.get(0).getSources());
        assertEquals(List.of("PR Newswire"), hot.get(1).getSources());
    }

    @Test
    void listPageFillsClusterSourceCountBadge() {
        NewsItemDO clustered = NewsItemDO.builder().id(1L).url("https://example.com/a")
                .sourceId(11L).publishTime(new Date(NOW.getTime() - HOUR))
                .status("published").heat(7).build();
        NewsItemDO solo = NewsItemDO.builder().id(4L).url("https://example.com/b")
                .sourceId(33L).publishTime(new Date(NOW.getTime() - 2 * HOUR))
                .status("published").heat(5).build();
        Page<NewsItemDO> pager = new Page<>(1, 20);
        pager.setRecords(List.of(clustered, solo));
        pager.setTotal(2);
        when(itemMapper.selectPage(any(), any())).thenReturn(pager);
        NewsStoryItem twinA = item(1, 11L, 1, 7);
        NewsStoryItem twinB = item(2, 22L, 2, 7);
        when(assembler.loadPublished(any())).thenReturn(new NewsStoryAssembler.NewsStoryWindow(
                List.of(twinA, twinB,
                        new NewsStoryItem(3L, "solo title", null, "career", 33L,
                                new Date(NOW.getTime() - 2 * HOUR), Set.of(), 5)),
                Map.of()));

        NewsPageVO page = service.listPublished(null, 1, 20);

        assertEquals(1, page.getRecords().get(0).getClusterSourceCount(), "簇覆盖 2 源=「另有 1 个来源」");
        assertNull(page.getRecords().get(1).getClusterSourceCount(), "单源条目不显徽章");
    }
}
