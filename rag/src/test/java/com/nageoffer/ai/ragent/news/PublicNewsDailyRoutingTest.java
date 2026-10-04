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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 日报读侧路由撞形钉住（#240 验收）：literal {@code GET /daily/rss} 与
 * {@code /daily/{date}} 同段数——Spring 映射 literal 优先于路径变量是既有行为，
 * 本测试用 MockMvc 钉住，防未来 mapping 调整（如改写 pattern/加通配）后
 * 新端点静默落进 {@code dailyDetail(date="rss")} 的「日报不存在」。
 *
 * <p>standalone MockMvc（无 Spring 上下文）：只验证 handler mapping 分派与
 * 控制器出口形状；孪生关闭态同形 404 一并钉住。
 */
class PublicNewsDailyRoutingTest {

    private final NewsDailyDigestQueryService digestQueryService = mock(NewsDailyDigestQueryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicNewsController(
                mock(NewsQueryService.class), digestQueryService, mock(NewsSeoService.class))).build();
    }

    @Test
    void dailyRssLiteralBeatsDatePathVariable() throws Exception {
        when(digestQueryService.renderIssuesRss()).thenReturn("<rss version=\"2.0\"/>");
        MockHttpServletResponse response = perform(get("/public/news/daily/rss"));
        assertEquals(200, response.getStatus(), "literal /daily/rss 命中期级 RSS 端点");
        assertEquals("application/rss+xml;charset=UTF-8", response.getContentType());
        String cacheControl = response.getHeader("Cache-Control");
        assertTrue(cacheControl != null && cacheControl.contains("max-age=3600") && cacheControl.contains("public"),
                "缓存沿既有 feed 惯例 1h public，实际=" + cacheControl);
        assertEquals("<rss version=\"2.0\"/>", response.getContentAsString());
        verify(digestQueryService).renderIssuesRss();
        verify(digestQueryService, never()).getDetail(any());
    }

    /** 对照：真日期路径仍走详情端点（literal 只截 rss 字面量，不侵占路径变量） */
    @Test
    void realDateStillRoutesToDetail() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 3);
        when(digestQueryService.getDetail(date)).thenReturn(NewsDailyDigestVO.builder().digestDate(date).build());
        MockHttpServletResponse response = perform(get("/public/news/daily/" + date));
        assertEquals(200, response.getStatus());
        verify(digestQueryService).getDetail(date);
        verify(digestQueryService, never()).renderIssuesRss();
    }

    /** 单刊 feed 对照：/daily/{date}/rss 不受新 literal 端点影响 */
    @Test
    void perIssueFeedStillResolves() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 3);
        when(digestQueryService.getDetail(date)).thenReturn(NewsDailyDigestVO.builder()
                .digestDate(date).storedIntroSource("llm").build());
        when(digestQueryService.renderRss(any())).thenReturn("<rss version=\"2.0\"/>");
        MockHttpServletResponse response = perform(get("/public/news/daily/" + date + "/rss"));
        assertEquals(200, response.getStatus());
        assertEquals("application/rss+xml;charset=UTF-8", response.getContentType());
    }

    /** 关闭态孪生同形：flag 关时 /daily/rss 走 404 兜底（不落 no-handler） */
    @Test
    void disabledTwinReturns404ForIssueFeed() throws Exception {
        MockMvc disabled = MockMvcBuilders.standaloneSetup(new PublicNewsDisabledController()).build();
        MockHttpServletResponse response = disabled
                .perform(get("/public/news/daily/rss"))
                .andReturn()
                .getResponse();
        assertEquals(404, response.getStatus());
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        return result.getResponse();
    }
}
