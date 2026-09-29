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
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.heat.NewsHeatProperties;
import com.nageoffer.ai.ragent.news.heat.NewsStoryAssembler;
import com.nageoffer.ai.ragent.news.heat.NewsStoryClusterer;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 统一公开资格测试（#185）：列表/详情/主题/检索四公开面共用同一条可见性判据
 * ——status='published' AND (eligible_time IS NULL OR eligible_time ≤ now-180s)；
 * 发布门从资格就绪起算，预算延期/无效摘要/待富化条目不落 published、不能靠
 * 180s 超时放行（状态面在富化测试证明）。Mapper 全 mock 断言 wrapper 判据
 * 与 gateFloor 值；真库 SQL 判据归 NewsPipelinePgIt。
 */
class NewsQueryServiceImplGateTests {

    private static final long NOW_MILLIS = 1757548800000L;
    private static final Date NOW = new Date(NOW_MILLIS);

    private NewsItemMapper itemMapper;
    private NewsItemTopicMapper itemTopicMapper;
    private NewsTopicMapper topicMapper;
    private NewsQueryServiceImpl service;
    private NewsFetchProperties fetchProperties;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsItemTopicDO.class);
        itemMapper = mock(NewsItemMapper.class);
        itemTopicMapper = mock(NewsItemTopicMapper.class);
        topicMapper = mock(NewsTopicMapper.class);
        fetchProperties = new NewsFetchProperties();
        service = new NewsQueryServiceImpl(itemMapper, mock(NewsSourceMapper.class), topicMapper,
                itemTopicMapper, mock(NewsStoryAssembler.class), new NewsStoryClusterer(),
                new NewsHeatProperties(), fetchProperties, () -> NOW);
        when(itemMapper.selectList(any())).thenReturn(List.of());
    }

    private static void assertGatePredicate(Wrapper<NewsItemDO> wrapper, String surface) {
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("status ="), surface + "：限定 published，实际=" + sql);
        assertTrue(sql.contains("eligible_time IS NULL"), surface + "：历史行（NULL）视同过门，实际=" + sql);
        assertTrue(sql.contains("eligible_time <="), surface + "：资格就绪 180s 后过门，实际=" + sql);
    }

    @Test
    void listAppliesUnifiedVisibilityGate() {
        Page<NewsItemDO> pager = new Page<>(1, 20);
        pager.setRecords(List.of());
        when(itemMapper.selectPage(any(), any())).thenReturn(pager);

        service.listPublished(null, 1, 20);

        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).selectPage(any(), captor.capture());
        assertGatePredicate(captor.getValue(), "列表面");
    }

    @Test
    void detailAppliesUnifiedVisibilityGate() {
        NewsItemDO visible = NewsItemDO.builder().id(1L).status("published").build();
        when(itemMapper.selectOne(any())).thenReturn(visible);

        service.getPublishedDetail(1L);

        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).selectOne(captor.capture());
        assertGatePredicate(captor.getValue(), "详情面");
    }

    @Test
    void detailOfGatedItemNotFound() {
        when(itemMapper.selectOne(any())).thenReturn(null);

        assertThrows(ClientException.class, () -> service.getPublishedDetail(1L),
                "未过门/下架/不存在同形 404——不向访问者泄漏存在性");
    }

    @Test
    void searchAppliesUnifiedVisibilityGate() {
        Page<NewsItemDO> pager = new Page<>(1, 20);
        pager.setRecords(List.of());
        when(itemMapper.selectPage(any(), any())).thenReturn(pager);

        service.searchPublished("polyu", null, null, null, 1, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<NewsItemDO>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(itemMapper).selectPage(any(), captor.capture());
        assertGatePredicate(captor.getValue(), "检索面");
    }

    @Test
    void topicSurfacesShareTheSameGateFloor() {
        NewsTopicDO topic = NewsTopicDO.builder().id(9L).slug("ai").nameZh("人工智能")
                .topicGroup("RESEARCH").curated(true).status("active").build();
        when(topicMapper.selectOne(any())).thenReturn(topic);
        when(itemTopicMapper.countVisibleByTopic(any())).thenReturn(List.of());
        Page<NewsItemDO> pager = new Page<>(1, 20);
        pager.setRecords(List.of());
        when(itemTopicMapper.selectVisiblePageByTopic(any(), anyLong(), any())).thenReturn(pager);

        service.getTopicDetail("ai", 1, 20);

        Date expectedGateFloor = new Date(NOW_MILLIS - 180_000L);
        verify(itemTopicMapper).countVisibleByTopic(eq(expectedGateFloor));
        verify(itemTopicMapper).selectVisiblePageByTopic(any(), eq(9L), eq(expectedGateFloor));
        verify(itemTopicMapper).selectLastVisiblePublishTime(eq(9L), eq(expectedGateFloor));
    }

    @Test
    void gateFloorFollowsConfiguredSeconds() {
        fetchProperties.setPublishGateSeconds(60);

        NewsTopicDO topic = NewsTopicDO.builder().id(9L).slug("ai").nameZh("人工智能")
                .topicGroup("RESEARCH").curated(true).status("active").build();
        when(topicMapper.selectOne(any())).thenReturn(topic);
        when(itemTopicMapper.countVisibleByTopic(any())).thenReturn(List.of());
        Page<NewsItemDO> pager = new Page<>(1, 20);
        pager.setRecords(List.of());
        when(itemTopicMapper.selectVisiblePageByTopic(any(), anyLong(), any())).thenReturn(pager);

        service.getTopicDetail("ai", 1, 20);

        verify(itemTopicMapper).countVisibleByTopic(eq(new Date(NOW_MILLIS - 60_000L)));
    }

    @Test
    void listWrapperCarriesGateFloorParam() {
        Page<NewsItemDO> pager = new Page<>(1, 20);
        pager.setRecords(List.of());
        when(itemMapper.selectPage(any(), any())).thenReturn(pager);

        service.listPublished(null, 1, 20);

        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).selectPage(any(), captor.capture());
        // MP 3.5.17：paramNameValuePairs 在 sqlSegment 首次物化时才填充（lazy segment）——先物化再读
        assertGatePredicate(captor.getValue(), "列表面");
        Map<String, Object> params = ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<NewsItemDO, ?, ?>)
                captor.getValue()).getParamNameValuePairs();
        assertTrue(params.containsValue(new Date(NOW_MILLIS - 180_000L)),
                "gateFloor=now-180s 作为参数进查询，实际=" + params);
        assertEquals(180, fetchProperties.effectivePublishGateSeconds());
    }
}
