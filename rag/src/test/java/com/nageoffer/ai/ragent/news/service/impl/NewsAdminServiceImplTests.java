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
        assertEquals(0, status.getMonthlyRemainingYuan().compareTo(new BigDecimal("13.0")));
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
