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

import com.nageoffer.ai.ragent.core.parser.HtmlDocumentParser;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsHttpFetchClient;
import com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer;
import com.nageoffer.ai.ragent.news.fetch.RawNewsItem;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 资源测试（#185 验收）：实际全站上限对应的 4 日窗回放——每日 20 源×10 新鲜候选
 * （局部 200）+50 旧文候选 + 前日候选重灌（幂等 IN 判重），全站日准入 60、
 * 富化 20×3 轮（假 LLM 网关零延迟——测量的是管线逻辑+真实模板渲染+守卫+内存库
 * 求值成本，非模型延迟；LLM 延迟与预算归 #184 域）。
 *
 * <p><b>声明的并发数=1（串行）</b>：准入/富化在本项目为单实例进程内串行
 * （#184 单机串行准入锁先例；多实例须换 PG 原子预留，不在本票）。记录指标=
 * 各阶段墙钟延迟与堆内存用量（System.gc 后采样），数字进测试输出供证据包；
 * 断言上限宽松（防 CI 抖动）：4 日全窗 ≤30s、堆增量 ≤256MB——不以
 * 「4C8G 配置未改」代替测量。
 */
class NewsPipelineCapacityReplayTest {

    private static final long HOUR = 3600L * 1000;
    private static final int DAYS = 4;
    private static final int SOURCES = 20;

    private FakeNewsItemStore store;
    private NewsFetchService fetchService;
    private NewsEnrichService enrichService;
    private long now;

    @BeforeEach
    void setUp() {
        store = new FakeNewsItemStore();
        now = 1757548800000L;
        NewsFetchProperties properties = new NewsFetchProperties();
        for (int s = 0; s < SOURCES; s++) {
            properties.getAdmissionSourceDailyCaps().put("src-" + s, 10);
        }
        fetchService = new NewsFetchService(List.of(), mock(NewsSourceMapper.class), store.mapper,
                properties, () -> new Date(now));
        NewsSourceMapper sourceMapper = mock(NewsSourceMapper.class);
        when(sourceMapper.selectById(any(Long.class))).thenReturn(NewsSourceDO.builder()
                .id(1L).sourceKey("src-0").platform("official").build());
        NewsHttpFetchClient httpFetchClient = mock(NewsHttpFetchClient.class);
        when(httpFetchClient.get(anyString())).thenReturn(
                ("<html><body><main><p>" + "理大研究要闻正文段落，包含具体事实。".repeat(40)
                        + "</p></main></body></html>").getBytes(StandardCharsets.UTF_8));
        NewsLlmBudgetService budget = mock(NewsLlmBudgetService.class);
        when(budget.call(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0, ChatRequest.class);
            assertTrue(request.getMessages().get(0).getContent().contains("# 受控主题词表"),
                    "真实模板渲染进请求（测量含模板成本）");
            return new NewsLlmBudgetService.LlmCall("{\"title_zh\":\"标题\",\"title_en\":\"Title\","
                    + "\"summary_zh\":\"摘要内容。\\n\\n补充段落。\",\"summary_en\":\"Summary.\\n\\nParagraph.\","
                    + "\"category\":\"campus\",\"topics\":[]}", false, () -> { });
        });
        enrichService = new NewsEnrichService(store.mapper, sourceMapper,
                mock(NewsTopicMapper.class), mock(NewsItemTopicMapper.class),
                httpFetchClient, new HtmlDocumentParser(), budget,
                new PromptTemplateLoader(new DefaultResourceLoader()),
                new com.fasterxml.jackson.databind.ObjectMapper(), properties, () -> new Date(now));
    }

    private long usedHeapMb() {
        System.gc();
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    @Test
    void fourDayWindowAtFullSiteCapWithinTimeAndMemoryBudget() {
        long heapBefore = usedHeapMb();
        long start = System.nanoTime();
        long admitNanos = 0;
        long enrichNanos = 0;
        long sweepNanos = 0;
        int totalAdmitted = 0;
        int totalArchived = 0;
        List<NewsFetchService.SourceCandidates> previousDays = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            now += 24 * HOUR;
            List<NewsFetchService.SourceCandidates> today = new ArrayList<>();
            for (int s = 0; s < SOURCES; s++) {
                List<RawNewsItem> items = new ArrayList<>();
                for (int i = 0; i < 10; i++) {
                    items.add(candidate("src-" + s, "d" + day + "-src" + s + "-" + i, (i + 1) * HOUR));
                }
                for (int i = 0; i < 2; i++) {
                    items.add(candidate("src-" + s, "d" + day + "-stale" + s + "-" + i, 3 * 24 * HOUR + i * HOUR));
                }
                today.add(batch(s, items));
            }
            // 前日候选重灌（同 URL 幂等 IN 判重在满量候选集上反复执行）
            List<NewsFetchService.SourceCandidates> all = new ArrayList<>(today);
            all.addAll(previousDays);
            long t0 = System.nanoTime();
            NewsFetchService.AdmissionResult result = fetchService.admitAll(all);
            admitNanos += System.nanoTime() - t0;
            totalAdmitted += result.admitted();
            totalArchived += result.archivedStale();
            previousDays.addAll(today);
            assertEquals(60, result.admitted(), "每日全站准入恰为上限 60（局部 200 候选）");
            // 富化 3 轮×20（假网关零延迟），TTL 收尾，与生产轮次形状一致
            t0 = System.nanoTime();
            for (int round = 0; round < 3; round++) {
                enrichService.enrichPendingItems();
            }
            enrichNanos += System.nanoTime() - t0;
            t0 = System.nanoTime();
            fetchService.expireOverduePending();
            sweepNanos += System.nanoTime() - t0;
        }
        long totalNanos = System.nanoTime() - start;
        long heapAfter = usedHeapMb();
        long heapDelta = Math.max(0, heapAfter - heapBefore);

        // 稳态核验：每日准入 60 全部当日富化完成（批 20×3），积压不跨日累积
        assertEquals(DAYS * 60, totalAdmitted, "4 日累计准入=240（60/日）");
        assertEquals(0, store.rows().stream()
                        .filter(r -> NewsItemStatus.PENDING.equals(r.getStatus())).count(),
                "满配下无跨日积压（60 准入=60 富化能力）");
        assertEquals(DAYS * 60, store.rows().stream()
                        .filter(r -> NewsItemStatus.SUMMARY_SOURCE_LLM.equals(r.getSummarySource())).count(),
                "240 条全部 LLM 富化成功（假网关）");
        // 资源记录（进测试输出=证据包数字；并发=1 串行声明见类 javadoc）
        System.out.printf("[news][resource] 4 日窗满上限回放：总 %d ms（准入 %d ms，富化 %d ms，TTL 扫描 %d ms），"
                        + "行数 %d（准入 %d/归档 %d），堆 %d→%d MB（增量 %d MB）%n",
                totalNanos / 1_000_000, admitNanos / 1_000_000, enrichNanos / 1_000_000,
                sweepNanos / 1_000_000, store.rows().size(), totalAdmitted, totalArchived,
                heapBefore, heapAfter, heapDelta);
        assertTrue(totalNanos < 30_000_000_000L, "4 日窗串行回放 ≤30s（实际 "
                + totalNanos / 1_000_000 + " ms）——实测不以配置口径代替");
        assertTrue(heapDelta < 256, "堆增量 ≤256MB（实际 " + heapDelta + " MB）");
    }

    /** 标题不含数字（守卫的数字保真面由 NewsWritingGuardTests 用真实样例句覆盖；本测量不掺守卫拒绝） */
    private RawNewsItem candidate(String key, String slug, long ageMillis) {
        String url = "https://example.com/news/" + slug;
        return new RawNewsItem(url, NewsUrlNormalizer.urlHash(url),
                "research news item", null, "en", new Date(now - ageMillis), null, key);
    }

    private NewsFetchService.SourceCandidates batch(int index, List<RawNewsItem> items) {
        return new NewsFetchService.SourceCandidates(NewsSourceDO.builder()
                .id(100L + index).sourceKey("src-" + index).platform("official")
                .fetchEndpoint("https://example.com/x").fetchStrategy("RSS")
                .enabled(true).consecutiveFailures(0).build(), items);
    }
}
