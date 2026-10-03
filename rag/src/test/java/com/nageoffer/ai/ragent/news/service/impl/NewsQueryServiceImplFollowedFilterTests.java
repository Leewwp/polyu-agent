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
import com.nageoffer.ai.ragent.news.controller.vo.NewsPageVO;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「只看关注」多主题过滤单测（#215 调整，2026-10-04 维护者设计）：
 * null/空 topics=原语义零注入（不触主题解析）；命中 slug 注入 EXISTS+topic_id IN
 * 判据；全部未解析=契约空页不落条目查询。Mapper 全 mock 断言 wrapper 判据
 * （沿 {@link NewsQueryServiceImplGateTests} 范式）；真库 SQL 判据归 PgIt。
 */
class NewsQueryServiceImplFollowedFilterTests {

    private static final Date NOW = new Date(1757548800000L);

    private NewsItemMapper itemMapper;
    private NewsTopicMapper topicMapper;
    private NewsQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsItemTopicDO.class);
        itemMapper = mock(NewsItemMapper.class);
        topicMapper = mock(NewsTopicMapper.class);
        service = new NewsQueryServiceImpl(itemMapper, mock(NewsSourceMapper.class), topicMapper,
                mock(NewsItemTopicMapper.class), mock(NewsStoryAssembler.class), new NewsStoryClusterer(),
                new NewsHeatProperties(), new NewsFetchProperties(), () -> NOW);
        when(itemMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void nullTopicsKeepsOriginalListSemantics() {
        Page<NewsItemDO> pager = new Page<>(1, 20);
        pager.setRecords(List.of());
        when(itemMapper.selectPage(any(), any())).thenReturn(pager);

        service.listPublished(null, 1, 20);

        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertFalse(sql.contains("EXISTS"), "无 topics 不得注入主题判据，实际=" + sql);
        verify(topicMapper, never()).selectList(any());
    }

    @Test
    void resolvedTopicsInjectExistsWithIdIn() {
        when(topicMapper.selectList(any())).thenReturn(List.of(
                NewsTopicDO.builder().id(13L).slug("research").curated(true).status("active").build(),
                NewsTopicDO.builder().id(26L).slug("alumni").curated(true).status("active").build()));
        Page<NewsItemDO> pager = new Page<>(1, 20);
        pager.setRecords(List.of());
        when(itemMapper.selectPage(any(), any())).thenReturn(pager);

        service.listPublished(null, List.of("RESEARCH", " research ", "alumni"), 1, 20);

        // 主题解析面：规范化（小写/trim/去重）后按 slug IN 查 curated+active 目录
        ArgumentCaptor<Wrapper<NewsTopicDO>> topicCaptor = ArgumentCaptor.forClass(Wrapper.class);
        verify(topicMapper).selectList(topicCaptor.capture());
        String topicSql = topicCaptor.getValue().getSqlSegment();
        assertTrue(topicSql.contains("slug IN"), "按 slug 集解析，实际=" + topicSql);
        assertTrue(topicSql.contains("curated"), "仅策展目录可过滤，实际=" + topicSql);

        // 条目查询面：EXISTS + 外键锚定 + id 列表
        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("EXISTS"), "注入主题成员判据，实际=" + sql);
        assertTrue(sql.contains("topic_id IN (13,26)"), "解析 id 拼接，实际=" + sql);
        assertTrue(sql.contains("nit.item_id = t_news_item.id"), "外键锚定条目主键，实际=" + sql);
        // 可见性判据与无过滤档一致（统一公开资格不因过滤档放宽）
        assertTrue(sql.contains("status =") && sql.contains("eligible_time"), "公开资格判据在位，实际=" + sql);
    }

    @Test
    void unresolvedTopicsReturnContractEmptyPageWithoutItemQuery() {
        when(topicMapper.selectList(any())).thenReturn(List.of());

        NewsPageVO page = service.listPublished(null, List.of("ghost-topic"), 1, 20);

        assertEquals(0L, page.getTotal());
        assertTrue(page.getRecords().isEmpty());
        assertFalse(Boolean.TRUE.equals(page.getHasMore()));
        verify(itemMapper, never()).selectPage(any(), any());
    }
}
