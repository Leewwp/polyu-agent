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
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicAliasDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicAliasMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsHttpFetchClient;
import com.nageoffer.ai.ragent.core.parser.HtmlDocumentParser;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 别名账消费侧拦截单测（#202 防再提，最小接线验证）：linkTopics 词表未命中后、
 * 建提案行前查别名账——merged 别名回链目标 curated 主题、rejected 别名跳过
 * （不建提案行不挂关联）、未命中照旧建提案（既有行为零回归）。
 * 真库口径互证（normalizeKey 两侧一致+不落行）归 NewsTopicGovernancePgIt。
 */
class NewsEnrichAliasInterceptTests {

    private NewsItemMapper itemMapper;
    private NewsSourceMapper sourceMapper;
    private NewsTopicMapper topicMapper;
    private NewsItemTopicMapper itemTopicMapper;
    private NewsTopicAliasMapper aliasMapper;
    private NewsLlmBudgetService llmBudgetService;
    private NewsEnrichService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsItemTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsTopicAliasDO.class);
        itemMapper = mock(NewsItemMapper.class);
        sourceMapper = mock(NewsSourceMapper.class);
        topicMapper = mock(NewsTopicMapper.class);
        itemTopicMapper = mock(NewsItemTopicMapper.class);
        aliasMapper = mock(NewsTopicAliasMapper.class);
        llmBudgetService = mock(NewsLlmBudgetService.class);
        PromptTemplateLoader loader = mock(PromptTemplateLoader.class);
        when(loader.load(anyString())).thenReturn("# 模板\n");
        service = new NewsEnrichService(itemMapper, sourceMapper, topicMapper, itemTopicMapper,
                aliasMapper, mock(NewsHttpFetchClient.class), new HtmlDocumentParser(), llmBudgetService,
                loader, new com.fasterxml.jackson.databind.ObjectMapper(), new NewsFetchProperties(),
                java.util.Date::new);
    }

    private static NewsTopicDO activeCurated(long id, String slug) {
        return NewsTopicDO.builder().id(id).slug(slug).nameZh(slug).nameEn(slug)
                .topicGroup("STUDENT_AFFAIRS").curated(true).status("active").build();
    }

    /**
     * merged 别名：词表未命中的「Culture」直接回链目标 campus——不落新提案行
     */
    @Test
    void mergedAliasLinksTargetWithoutNewProposal() {
        when(topicMapper.selectList(any())).thenReturn(List.of()); // 词表空（提案已退场）
        when(aliasMapper.selectByAliasKey(any())).thenReturn(NewsTopicAliasDO.builder()
                .id(1L).aliasKey("culture").action("merged")
                .sourceTopicId(28L).targetTopicId(15L).build());
        when(topicMapper.selectById(15L)).thenReturn(activeCurated(15L, "campus"));
        when(itemTopicMapper.selectCount(any())).thenReturn(0L);

        service.linkTopics(100L, List.of("Culture"));

        verify(topicMapper, never()).insert(any(NewsTopicDO.class));
        var linkCaptor = org.mockito.ArgumentCaptor.forClass(NewsItemTopicDO.class);
        verify(itemTopicMapper).insert(linkCaptor.capture());
        assertTrue(Long.valueOf(15L).equals(linkCaptor.getValue().getTopicId())
                        && Long.valueOf(100L).equals(linkCaptor.getValue().getItemId()),
                "merged 别名回链目标 curated 主题（item=100→topic=15），实际=" + linkCaptor.getValue());
        // 别名键查询用规范化形态（「Culture」→ culture）
        verify(aliasMapper).selectByAliasKey("culture");
    }

    /**
     * rejected 别名：命中即终局——不建提案行、不挂关联（幂等拦截核心断言）
     */
    @Test
    void rejectedAliasSkipsBothProposalAndLink() {
        when(topicMapper.selectList(any())).thenReturn(List.of());
        when(aliasMapper.selectByAliasKey(any())).thenReturn(NewsTopicAliasDO.builder()
                .id(2L).aliasKey("polyu").action("rejected")
                .sourceTopicId(34L).targetTopicId(null).build());

        service.linkTopics(100L, List.of("PolyU"));

        verify(topicMapper, never()).insert(any(NewsTopicDO.class));
        verify(itemTopicMapper, never()).insert(any(NewsItemTopicDO.class));
    }

    /**
     * 未命中别名：照旧走 createProposal（既有行为零回归，拦截只加消费侧不改生成）
     */
    @Test
    void unknownTokenStillCreatesProposalAsBefore() {
        when(topicMapper.selectList(any())).thenReturn(List.of());
        when(aliasMapper.selectByAliasKey(any())).thenReturn(null);
        when(topicMapper.selectOne(any())).thenReturn(null);
        when(topicMapper.insert(any(NewsTopicDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, NewsTopicDO.class).setId(777L);
            return 1;
        });
        when(itemTopicMapper.selectCount(any())).thenReturn(0L);

        service.linkTopics(100L, List.of("全新概念"));

        var captor = org.mockito.ArgumentCaptor.forClass(NewsTopicDO.class);
        verify(topicMapper).insert(captor.capture());
        assertTrue(captor.getValue().getSlug().startsWith("prop-"), "未命中别名照旧建 prop- 提案");
        verify(itemTopicMapper, times(1)).insert(any(NewsItemTopicDO.class));
    }
}
