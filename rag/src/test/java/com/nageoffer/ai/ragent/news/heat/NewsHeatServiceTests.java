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

import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 热度数学测试（#187：NewsHeatService 收敛为纯计算面——半衰公式+独立来源组语义；
 * 编排面身份/投票/48h 证据窗断言见 {@link NewsEventServiceTests}）。
 * 时间旅行=固定日期直接传参。
 */
class NewsHeatServiceTests {

    private static final long HOUR = 3600L * 1000;
    private static final Date NOW = new Date(1757548800000L);

    private NewsHeatProperties properties;
    private NewsHeatService service;

    @BeforeEach
    void setUp() {
        properties = new NewsHeatProperties();
        Map<String, Integer> weights = new HashMap<>();
        weights.put("official-media-release", 3);
        weights.put("media-releases", 3);
        weights.put("sao-news", 3);
        weights.put("prn", 2);
        properties.setSourceWeights(weights);
        service = new NewsHeatService(properties);
    }

    private NewsSourceDO source(long id, String sourceKey, String group) {
        return NewsSourceDO.builder().id(id).sourceKey(sourceKey).platform("official")
                .independenceGroup(group).build();
    }

    @Test
    void independenceKeyPrefersGroupThenSourceKey() {
        assertEquals("polyu-official", NewsHeatService.independenceKey(
                source(11L, "media-releases", "polyu-official")));
        assertEquals("sao-news", NewsHeatService.independenceKey(
                source(12L, "sao-news", null)), "NULL 组按 source_key 自成一组");
        assertEquals("unknown", NewsHeatService.independenceKey(null), "未注册源视同 unknown");
    }

    @Test
    void sourceWeightReadsOverrideTableWithZeroDefault() {
        assertEquals(3, service.sourceWeight(source(11L, "official-media-release", null)));
        assertEquals(2, service.sourceWeight(source(22L, "prn", "prn-wire")), "组键不影响权重查表（仍按 source_key）");
        assertEquals(0, service.sourceWeight(source(99L, "ai-techcrunch-ai", null)), "缺 key 按 0 计");
        assertEquals(0, service.sourceWeight(null));
    }

    @Test
    void decayedHeatHalvesEveryTwentyFourHours() {
        Date anchor = new Date(NOW.getTime() - HOUR);
        // 基数 7（2 独立组+权重 3+2）：1h 龄→round(6.80)=7；24h→4；48h→2；72h→1；96h→0
        assertEquals(7, NewsHeatService.decayedHeat(7, anchor, new Date(anchor.getTime() + HOUR)));
        assertEquals(4, NewsHeatService.decayedHeat(7, anchor, new Date(anchor.getTime() + 24 * HOUR)));
        assertEquals(2, NewsHeatService.decayedHeat(7, anchor, new Date(anchor.getTime() + 48 * HOUR)));
        assertEquals(1, NewsHeatService.decayedHeat(7, anchor, new Date(anchor.getTime() + 72 * HOUR)));
        assertEquals(0, NewsHeatService.decayedHeat(7, anchor, new Date(anchor.getTime() + 96 * HOUR)));
    }

    @Test
    void futureAnchorCapsDecayAtOne() {
        // events 型未来开始时间：负龄不放大热度（衰减系数封顶 1）
        Date future = new Date(NOW.getTime() + 3L * 24 * HOUR);
        assertEquals(4, NewsHeatService.decayedHeat(4, future, NOW));
    }

    @Test
    void zeroOrNegativeBaseIsZero() {
        assertEquals(0, NewsHeatService.decayedHeat(0, NOW, NOW));
        assertEquals(0, NewsHeatService.decayedHeat(-3, NOW, NOW));
    }

    @Test
    void nullAnchorKeepsBase() {
        assertEquals(5, NewsHeatService.decayedHeat(5, null, NOW));
    }
}
