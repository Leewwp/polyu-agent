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
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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
 * 不存在条目抛条目不存在。
 */
class NewsAdminServiceImplTests {

    private NewsItemMapper itemMapper;
    private NewsAdminServiceImpl service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        itemMapper = mock(NewsItemMapper.class);
        service = new NewsAdminServiceImpl(itemMapper);
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
}
