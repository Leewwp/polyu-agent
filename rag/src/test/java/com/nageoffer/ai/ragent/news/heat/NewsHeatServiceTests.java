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

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.heat.NewsStoryAssembler.NewsStoryWindow;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 热度模型测试：热度公式（Σ信源权重+覆盖信源数）、
 * 24h 半衰期精确数值、同簇写入、降级开关、变更跳写。Mapper mock 不触库；
 * 时间旅行=构造器注入固定时钟（FakeWindowCounter 先例同源）。
 */
class NewsHeatServiceTests {

    private static final long HOUR = 3600L * 1000;
    private static final Date NOW = new Date(1757548800000L);

    private NewsItemMapper itemMapper;
    private NewsStoryAssembler assembler;
    private NewsHeatProperties properties;
    private NewsHeatService service;

    @BeforeEach
    void setUp() {
        // 纯 Mockito 环境无 MyBatis-Plus 运行时，lambda wrapper 需手工初始化表元数据
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        itemMapper = mock(NewsItemMapper.class);
        assembler = mock(NewsStoryAssembler.class);
        properties = new NewsHeatProperties();
        Map<String, Integer> weights = new HashMap<>();
        weights.put("official-media-release", 3);
        weights.put("prn", 2);
        properties.setSourceWeights(weights);
        service = new NewsHeatService(itemMapper, assembler, new NewsStoryClusterer(), properties, () -> NOW);
    }

    private NewsStoryItem item(long id, long sourceId, long ageHours, Integer storedHeat) {
        return new NewsStoryItem(id, "PolyU team cracks perovskite solar cell stability problem", null,
                "research", sourceId, new Date(NOW.getTime() - ageHours * HOUR), Set.of(9L), storedHeat);
    }

    private NewsSourceDO source(long id, String sourceKey) {
        return NewsSourceDO.builder().id(id).sourceKey(sourceKey).platform("official").build();
    }

    private NewsStoryWindow windowOf(NewsStoryItem... items) {
        Map<Long, NewsSourceDO> sources = new HashMap<>();
        sources.put(11L, source(11L, "official-media-release"));
        sources.put(22L, source(22L, "prn"));
        return new NewsStoryWindow(List.of(items), sources);
    }

    @Test
    void clusterHeatSumsWeightsPlusCoverageAndDecaysByHalfLife() {
        NewsStoryCluster pair = new NewsStoryCluster(List.of(item(1, 11L, 0, 0), item(2, 22L, 0, 0)));
        Map<Long, NewsSourceDO> sources = windowOf().sourcesById();

        // 基数 = 权重 3+2 + 覆盖源数 2 = 7；24h 半衰：0h→7, 24h→4(round 3.5), 48h→2, 72h→1, 96h→0
        assertEquals(7, service.clusterHeat(pair, sources, NOW));
        assertEquals(4, service.clusterHeat(pair, sources, new Date(NOW.getTime() + 24 * HOUR)));
        assertEquals(2, service.clusterHeat(pair, sources, new Date(NOW.getTime() + 48 * HOUR)));
        assertEquals(1, service.clusterHeat(pair, sources, new Date(NOW.getTime() + 72 * HOUR)));
        assertEquals(0, service.clusterHeat(pair, sources, new Date(NOW.getTime() + 96 * HOUR)));
    }

    @Test
    void futurePublishCapsDecayAtOne() {
        // events 型未来开始时间：负龄不放大热度（衰减系数封顶 1）
        NewsStoryCluster future = new NewsStoryCluster(List.of(
                new NewsStoryItem(1L, null, "Upcoming career fair", "career", 11L,
                        new Date(NOW.getTime() + 3L * 24 * HOUR), Set.of(), 0)));
        assertEquals(4, service.clusterHeat(future, windowOf().sourcesById(), NOW));
    }

    @Test
    void recomputeWritesClusterHeatToAllMembers() {
        NewsStoryItem a = item(1, 11L, 1, 0);
        NewsStoryItem b = item(2, 22L, 2, 0);
        // 2h 龄：7 × 0.5^(2/24) = 6.61 → round 7
        when(assembler.loadPublished(any())).thenReturn(windowOf(a, b));

        int changed = service.recomputeHeat();

        assertEquals(2, changed);
        // eq 子句惰性求值不进 params——两个 wrapper 各携带 set 值 7 即证两成员同写簇热度
        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper, times(2)).update(any(), captor.capture());
        for (Wrapper<NewsItemDO> wrapper : captor.getAllValues()) {
            Map<String, Object> params = ((LambdaUpdateWrapper<NewsItemDO>) wrapper).getParamNameValuePairs();
            assertTrue(params.containsValue(7), "同簇成员写入同一簇热度，实际=" + params);
        }
    }

    @Test
    void recomputeSkipsMembersWithUnchangedHeat() {
        // 单条目簇：base=3(权重)+1(覆盖)=4，1h 龄 → round(3.89)=4；与存储一致即跳写
        when(assembler.loadPublished(any())).thenReturn(windowOf(item(1, 11L, 1, 4)));

        assertEquals(0, service.recomputeHeat());

        verify(itemMapper, never()).update(any(), any());
    }

    @Test
    void degradeSwitchComputesPerItemHeatWithoutMerge() {
        properties.setStoryMergeEnabled(false);
        when(assembler.loadPublished(any()))
                .thenReturn(windowOf(item(1, 11L, 1, 0), item(2, 22L, 2, 0)));

        assertEquals(2, service.recomputeHeat());

        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper, times(2)).update(any(), captor.capture());
        List<Integer> heats = new ArrayList<>();
        for (Wrapper<NewsItemDO> wrapper : captor.getAllValues()) {
            Map<String, Object> params = ((LambdaUpdateWrapper<NewsItemDO>) wrapper).getParamNameValuePairs();
            // 降级态各自成簇：base = 自身权重 + 1（官网源 4 / PRN 源 3）
            params.values().stream().filter(Integer.class::isInstance)
                    .map(Integer.class::cast)
                    .filter(value -> value >= 3)
                    .findFirst().ifPresent(heats::add);
        }
        assertTrue(heats.contains(4), "官网源单独热度 4，实际=" + heats);
        assertTrue(heats.contains(3), "PRN 源单独热度 3，实际=" + heats);
    }

    @Test
    void emptyWindowIsNoOp() {
        when(assembler.loadPublished(any())).thenReturn(new NewsStoryWindow(List.of(), Map.of()));

        assertEquals(0, service.recomputeHeat());

        verify(itemMapper, never()).update(any(), any());
    }

    @Test
    void unknownSourceKeyCountsCoverageOnly() {
        NewsStoryItem orphan = new NewsStoryItem(1L, null, "Orphan source story", "campus", 99L,
                new Date(NOW.getTime() - HOUR), Set.of(), 0);
        NewsStoryWindow window = new NewsStoryWindow(List.of(orphan), Map.of());

        assertEquals(1, service.clusterHeat(new NewsStoryCluster(List.of(orphan)), window.sourcesById(), NOW));
    }
}
