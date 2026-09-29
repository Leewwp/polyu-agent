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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.controller.vo.NewsLlmBudgetStatusVO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsLlmReceiptMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 下架服务测试：published → hidden、幂等重复下架、
 * 不存在条目抛条目不存在；预算状态聚合（#184：attempts 与成本双口径+降级事件）。
 */
class NewsAdminServiceImplTests {

    private NewsItemMapper itemMapper;
    private NewsLlmReceiptMapper receiptMapper;
    private NewsAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        itemMapper = mock(NewsItemMapper.class);
        receiptMapper = mock(NewsLlmReceiptMapper.class);
        service = new NewsAdminServiceImpl(itemMapper, receiptMapper, new NewsFetchProperties());
    }

    @Test
    void hideUpdatesPublishedItemToHidden() {
        when(itemMapper.update(any(), any())).thenReturn(1);

        service.hide(7L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<NewsItemDO>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(itemMapper).update(any(), captor.capture());
        Map<String, Object> params = ((LambdaUpdateWrapper<NewsItemDO>) captor.getValue())
                .getParamNameValuePairs();
        assertTrue(params.containsValue("hidden"), "set status=hidden，实际=" + params);
    }

    @Test
    void hideIsIdempotentForAlreadyHiddenItems() {
        when(itemMapper.update(any(), any())).thenReturn(0);
        when(itemMapper.selectCount(any())).thenReturn(1L);

        service.hide(7L);

        verify(itemMapper).selectCount(any());
    }

    @Test
    void hideUnknownItemThrows() {
        when(itemMapper.update(any(), any())).thenReturn(0);
        when(itemMapper.selectCount(any())).thenReturn(0L);

        ClientException ex = assertThrows(ClientException.class, () -> service.hide(404L));
        assertEquals("条目不存在", ex.getErrorMessage());
    }

    // ================== #184：预算状态双口径查询 ==================

    // ================== #185：管线状态与六口径日报 ==================

    @Test
    void pipelineStatusAggregatesSixMetricsFromItemTable() {
        // 发现日窗聚合行：发现 8、准入 6、唯一内容 5、归档 2
        Map<String, Object> fetchDay = new java.util.HashMap<>();
        fetchDay.put("discovered", 8L);
        fetchDay.put("admitted", 6L);
        fetchDay.put("unique_content", 5L);
        fetchDay.put("archived_today", 2L);
        when(itemMapper.selectMaps(any())).thenReturn(List.of(fetchDay));
        // 资格日窗行：llm×3 + fallback×1 + 过门 published×3（其中 1 条 fallback 未过门）
        java.util.Date now = new java.util.Date();
        long gateMs = 180_000L;
        List<NewsItemDO> eligibleRows = List.of(
                NewsItemDO.builder().status("published").summarySource("llm")
                        .eligibleTime(new java.util.Date(now.getTime() - 10 * gateMs)).build(),
                NewsItemDO.builder().status("published").summarySource("llm")
                        .eligibleTime(new java.util.Date(now.getTime() - 10 * gateMs)).build(),
                NewsItemDO.builder().status("published").summarySource("llm")
                        .eligibleTime(new java.util.Date(now.getTime() - 10 * gateMs)).build(),
                NewsItemDO.builder().status("published").summarySource("fallback")
                        .eligibleTime(new java.util.Date(now.getTime())).build());
        when(itemMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(eligibleRows);

        com.nageoffer.ai.ragent.news.controller.vo.NewsPipelineStatusVO status =
                service.pipelineStatus(java.time.LocalDate.now());

        assertEquals(8L, status.getDiscovered(), "六口径·发现");
        assertEquals(6L, status.getAdmitted(), "六口径·准入（归档不计）");
        assertEquals(5L, status.getUniqueContent(), "六口径·唯一内容（标题面代理）");
        assertEquals(3L, status.getEnrichedLlm(), "六口径·富化成功（summary_source=llm）");
        assertEquals(3L, status.getPublicVisible(), "六口径·公开展示（过门可见：3 LLM；未过门 fallback 不计）");
        assertEquals(0L, status.getEventCount(), "六口径·事件数=0 占位（#187）");
        assertEquals(2L, status.getArchivedToday(), "旧文归档单列");
        assertEquals(1L, status.getFallbackToday(), "零调用回退单列");
        assertEquals(54L, status.getSiteRemainingToday(), "剩余额度=上限 60-准入 6");
        assertEquals(180, status.getGateSeconds());
    }

    @Test
    void pipelineStatusHandlesEmptyDayWithoutNpe() {
        when(itemMapper.selectMaps(any())).thenReturn(null);
        when(itemMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class))).thenReturn(List.of());

        com.nageoffer.ai.ragent.news.controller.vo.NewsPipelineStatusVO status =
                service.pipelineStatus(java.time.LocalDate.now());

        assertEquals(0L, status.getDiscovered());
        assertEquals(0L, status.getAdmitted());
        assertEquals(0L, status.getUniqueContent());
        assertEquals(0L, status.getEnrichedLlm());
        assertEquals(0L, status.getPublicVisible());
        assertEquals(60L, status.getSiteRemainingToday(), "空日全额剩余");
    }

    @Test
    void llmBudgetStatusAggregatesAttemptsCostAndDegradedEvents() {
        // 日聚合（第一次 selectMaps）与月聚合（第二次）
        when(receiptMapper.selectMaps(any(Wrapper.class))).thenReturn(
                List.of(Map.of("cost_sum", new BigDecimal("0.015"), "attempt_sum", 3L)),
                List.of(Map.of("cost_sum", new BigDecimal("2.0"), "attempt_sum", 412L)));
        when(receiptMapper.selectCount(any())).thenReturn(2L);

        NewsLlmBudgetStatusVO status = service.llmBudgetStatus();

        assertEquals(0, status.getDailyCostYuan().compareTo(new BigDecimal("0.015")));
        assertEquals(3L, status.getDailyAttempts());
        assertEquals(2L, status.getDailyDegraded(), "当日降级事件数可查（#184 硬门）");
        assertEquals(0, status.getDailyRemainingYuan().compareTo(new BigDecimal("0.985")));
        assertEquals(0, status.getMonthlyCostYuan().compareTo(new BigDecimal("2.0")));
        assertEquals(412L, status.getMonthlyAttempts());
        assertEquals(0, status.getMonthlyRemainingYuan().compareTo(new BigDecimal("8.0")),
                "月剩余=¥10 保守默认（修正点1）−2.0");
    }

    @Test
    void llmBudgetStatusHandlesEmptyReceipts() {
        when(receiptMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(Map.of()));
        when(receiptMapper.selectCount(any())).thenReturn(0L);

        NewsLlmBudgetStatusVO status = service.llmBudgetStatus();

        assertEquals(0L, status.getDailyAttempts());
        assertEquals(0L, status.getDailyDegraded());
        assertEquals(0, status.getDailyCostYuan().compareTo(BigDecimal.ZERO));
        assertEquals(0, status.getDailyRemainingYuan().compareTo(BigDecimal.ONE));
    }
}
