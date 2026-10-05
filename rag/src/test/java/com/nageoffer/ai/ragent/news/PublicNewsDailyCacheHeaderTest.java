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
import com.nageoffer.ai.ragent.news.controller.PublicNewsController;
import com.nageoffer.ai.ragent.news.controller.PublicNewsDisabledController;
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestVO;
import com.nageoffer.ai.ragent.news.service.NewsDailyDigestQueryService;
import com.nageoffer.ai.ragent.news.service.NewsQueryService;
import com.nageoffer.ai.ragent.news.service.NewsSeoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 公开日报两 JSON 端点的 HTTP 缓存头合同（#274）：
 * 成功目录 public/max-age=60、成功详情 public/max-age=300；缺失详情
 * （HTTP 200+成功信封 data=null 既有契约）与业务错误/异常一律 no-store；
 * 关闭态 404 兜底同样 no-store。RSS 既有 1h 头与其余公开端点不受影响，
 * 响应不新增 Set-Cookie/身份字段。
 *
 * <p>standalone MockMvc（沿 PublicNewsDailyRoutingTest 范式）：只验控制器
 * 出口的头与信封形状，不拉 Spring 上下文。
 */
class PublicNewsDailyCacheHeaderTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 3);

    private final NewsDailyDigestQueryService digestQueryService = mock(NewsDailyDigestQueryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicNewsController(
                mock(NewsQueryService.class), digestQueryService, mock(NewsSeoService.class))).build();
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        return result.getResponse();
    }

    private static String cacheControl(MockHttpServletResponse response) {
        return response.getHeader("Cache-Control");
    }

    @Test
    void successfulCatalogGetsPublicMaxAge60() throws Exception {
        when(digestQueryService.listRecent(anyInt())).thenReturn(List.of());
        MockHttpServletResponse response = perform(get("/public/news/daily").param("limit", "400"));
        assertEquals(200, response.getStatus());
        String cc = cacheControl(response);
        assertTrue(cc != null && cc.contains("max-age=60") && cc.contains("public"),
                "目录成功=public/max-age=60，实际=" + cc);
        assertTrue(response.getContentAsString().contains("\"code\":\"0\""), "成功信封形状保持");
        assertTrue(response.getContentAsString().contains("\"data\":["), "空目录也是正常可缓存数据");
        assertNull(response.getHeader("Set-Cookie"), "公开 JSON 缓存不携带身份字段");
    }

    @Test
    void successfulDetailGetsPublicMaxAge300AndRecessIssueCachesToo() throws Exception {
        // 正常条目与合法休刊（items 空）同为正常数据，共享成功 TTL
        when(digestQueryService.getDetail(DATE)).thenReturn(NewsDailyDigestVO.builder()
                .digestDate(DATE).items(List.of()).build());
        MockHttpServletResponse response = perform(get("/public/news/daily/" + DATE));
        assertEquals(200, response.getStatus());
        String cc = cacheControl(response);
        assertTrue(cc != null && cc.contains("max-age=300") && cc.contains("public"),
                "详情成功=public/max-age=300，实际=" + cc);
        assertTrue(response.getContentAsString().contains("\"code\":\"0\""));
        assertNull(response.getHeader("Set-Cookie"));
    }

    @Test
    void missingDetailEnvelopeStays200ButIsNoStore() throws Exception {
        when(digestQueryService.getDetail(any())).thenReturn(null);
        MockHttpServletResponse response = perform(get("/public/news/daily/" + DATE));
        assertEquals(200, response.getStatus(), "缺失详情保持 HTTP 200 成功信封既有契约");
        assertEquals("no-store", cacheControl(response), "data=null 不给成功 TTL");
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":\"0\"") && body.contains("\"data\":null"),
                "成功信封 data=null 形状不变，实际=" + body);
    }

    @Test
    void malformedDateBusinessErrorIsNoStoreWithSameEnvelope() throws Exception {
        MockHttpServletResponse response = perform(get("/public/news/daily/not-a-date"));
        assertEquals(200, response.getStatus(), "业务错误沿全局处理器 HTTP 200 形状");
        assertEquals("no-store", cacheControl(response));
        String body = response.getContentAsString();
        assertFalse(body.contains("\"code\":\"0\""), "业务错误不落在成功码，实际=" + body);
        assertTrue(body.contains("日报不存在"), "错误消息与全局同形");
    }

    @Test
    void catalogRuntimeFailureIsNoStore() throws Exception {
        when(digestQueryService.listRecent(anyInt())).thenThrow(new IllegalStateException("boom"));
        MockHttpServletResponse response = perform(get("/public/news/daily"));
        assertEquals(200, response.getStatus(), "异常沿兜底处理器 HTTP 200 形状");
        assertEquals("no-store", cacheControl(response), "异常态不吃成功 TTL");
        assertFalse(response.getContentAsString().contains("\"code\":\"0\""));
    }

    @Test
    void detailRuntimeFailureIsNoStore() throws Exception {
        when(digestQueryService.getDetail(any())).thenThrow(new IllegalStateException("boom"));
        MockHttpServletResponse response = perform(get("/public/news/daily/" + DATE));
        assertEquals(200, response.getStatus());
        assertEquals("no-store", cacheControl(response));
        assertFalse(response.getContentAsString().contains("\"code\":\"0\""));
    }

    /** 不泛化：同控制器其余公开端点成功响应不带缓存头 */
    @Test
    void otherPublicEndpointsKeepNoCacheHeaders() throws Exception {
        NewsQueryService queryService = mock(NewsQueryService.class);
        when(queryService.listCuratedTopics()).thenReturn(List.of());
        MockMvc topics = MockMvcBuilders.standaloneSetup(new PublicNewsController(
                queryService, digestQueryService, mock(NewsSeoService.class))).build();
        MockHttpServletResponse response = topics.perform(get("/public/news/topics")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertNull(cacheControl(response), "其余公开端点不受本票影响");
    }

    /** 既有 RSS 一小时缓存合同不回退（单刊 feed 对照） */
    @Test
    void perIssueRssKeepsOneHourContract() throws Exception {
        when(digestQueryService.getDetail(DATE)).thenReturn(NewsDailyDigestVO.builder()
                .digestDate(DATE).storedIntroSource("llm").build());
        when(digestQueryService.renderRss(any())).thenReturn("<rss version=\"2.0\"/>");
        MockHttpServletResponse response = perform(get("/public/news/daily/" + DATE + "/rss"));
        assertEquals(200, response.getStatus());
        String cc = cacheControl(response);
        assertTrue(cc != null && cc.contains("max-age=3600") && cc.contains("public"),
                "RSS 1h 合同不变，实际=" + cc);
    }

    /** 关闭态孪生：日报两 JSON 端点 404 兜底附 no-store（其余兜底保持裸 404） */
    @Test
    void disabledTwinDailyEndpointsAre404NoStore() throws Exception {
        MockMvc disabled = MockMvcBuilders.standaloneSetup(new PublicNewsDisabledController()).build();
        MockHttpServletResponse list = disabled
                .perform(get("/public/news/daily").param("limit", "30")).andReturn().getResponse();
        assertEquals(404, list.getStatus());
        assertEquals("no-store", cacheControl(list));
        MockHttpServletResponse detail = disabled
                .perform(get("/public/news/daily/" + DATE)).andReturn().getResponse();
        assertEquals(404, detail.getStatus());
        assertEquals("no-store", cacheControl(detail));
        // 对照：其它端点关闭态兜底仍是裸 404（本票只处理日报两端点）
        MockHttpServletResponse hot = disabled.perform(get("/public/news/hot")).andReturn().getResponse();
        assertEquals(404, hot.getStatus());
        assertNull(cacheControl(hot));
    }

    /** ClientException 直抛路径：parseDigestDate 之外的显式业务异常同样 no-store */
    @Test
    void detailBusinessExceptionIsNoStore() throws Exception {
        when(digestQueryService.getDetail(any())).thenThrow(new ClientException("日报不存在"));
        MockHttpServletResponse response = perform(get("/public/news/daily/" + DATE));
        assertEquals(200, response.getStatus());
        assertEquals("no-store", cacheControl(response));
        assertTrue(response.getContentAsString().contains("日报不存在"));
    }
}
