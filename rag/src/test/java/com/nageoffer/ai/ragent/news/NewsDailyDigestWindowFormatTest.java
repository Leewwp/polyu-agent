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
import com.nageoffer.ai.ragent.news.controller.vo.NewsDailyDigestVO;
import com.nageoffer.ai.ragent.news.service.NewsDailyDigestQueryService;
import com.nageoffer.ai.ragent.news.service.NewsQueryService;
import com.nageoffer.ai.ragent.news.service.NewsSeoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 日报窗口字段序列化格式钉住：windowStart/windowEnd 以 HKT 墙钟
 * （yyyy-MM-dd HH:mm）出 JSON——前端 DailyDigestPage 直取字符串拼
 * 「覆盖窗口（… → … HKT）」标签，默认 UTC 序列化曾把 08:00 窗口显示成
 * 00:00（+08 环境部署下 8 小时观感漂移）。standalone MockMvc 与
 * PublicNewsDailyRoutingTest 同款（无 Spring 上下文，只钉出口形状）。
 */
class NewsDailyDigestWindowFormatTest {

    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");

    private final NewsDailyDigestQueryService digestQueryService = mock(NewsDailyDigestQueryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicNewsController(
                mock(NewsQueryService.class), digestQueryService, mock(NewsSeoService.class))).build();
    }

    @Test
    void windowFieldsSerializeAsHktWallClock() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 5);
        when(digestQueryService.getDetail(date)).thenReturn(NewsDailyDigestVO.builder()
                .digestDate(date)
                .windowStart(Date.from(date.minusDays(1).atTime(8, 0).atZone(HKT).toInstant()))
                .windowEnd(Date.from(date.atTime(8, 0).atZone(HKT).toInstant()))
                .build());
        String body = mockMvc.perform(get("/public/news/daily/" + date))
                .andReturn().getResponse().getContentAsString();
        assertTrue(body.contains("\"windowStart\":\"2026-10-04 08:00\""),
                "窗口起点应为 HKT 墙钟，实际=" + body);
        assertTrue(body.contains("\"windowEnd\":\"2026-10-05 08:00\""),
                "窗口闭端应为 HKT 墙钟，实际=" + body);
    }
}
