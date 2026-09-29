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
import com.nageoffer.ai.ragent.infra.model.LlmBudgetExhaustedException;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 状态机与六口径回放（#185 验收主证据之一）：真实 NewsFetchService +
 * NewsEnrichService（真模板加载器）×内存条目库×可旅行时钟——一次完整回放
 * 发现→准入→富化（成功/守卫拒绝→零调用回退/预算延期）→发布门→TTL 终态→
 * 同 URL 重现，按发现/准入/唯一内容/富化成功/公开展示/事件数六口径断言
 * （事件数=0 占位，#187 落地持久事件身份）。预算延期与无效摘要在 180s
 * 之后仍不可见（不绕发布门）。
 */
class NewsPipelineReplayTest {

    private static final long HOUR = 3600L * 1000;
    private static final long GATE = 180_000L;

    /** 可旅行时钟（固定锚点起） */
    private static final class Clock implements Supplier<Date> {
        long millis = 1757548800000L;

        void advance(long ms) {
            millis += ms;
        }

        @Override
        public Date get() {
            return new Date(millis);
        }
    }

    private FakeNewsItemStore store;
    private NewsFetchService fetchService;
    private NewsEnrichService enrichService;
    private Clock clock;
    private final Map<String, AtomicInteger> budgetCalls = new ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        store = new FakeNewsItemStore();
        clock = new Clock();
        NewsFetchProperties properties = new NewsFetchProperties();
        fetchService = new NewsFetchService(List.of(), mock(NewsSourceMapper.class), store.mapper,
                properties, clock);
        NewsSourceMapper sourceMapper = mock(NewsSourceMapper.class);
        when(sourceMapper.selectById(any(Long.class))).thenReturn(NewsSourceDO.builder()
                .id(1L).sourceKey("campus-x").platform("official").build());
        NewsHttpFetchClient httpFetchClient = mock(NewsHttpFetchClient.class);
        when(httpFetchClient.get(anyString())).thenReturn(
                "<html><body><main><p>Campus news content.</p></main></body></html>"
                        .getBytes(StandardCharsets.UTF_8));
        enrichService = new NewsEnrichService(store.mapper, sourceMapper,
                mock(NewsTopicMapper.class), mock(NewsItemTopicMapper.class),
                httpFetchClient, new HtmlDocumentParser(), mockBudget(),
                new PromptTemplateLoader(new DefaultResourceLoader()),
                new com.fasterxml.jackson.databind.ObjectMapper(), properties, clock);
    }

    /**
     * 按条目标题驱动的假预算网关（真实指纹/回执语义不在本回放面，归 #184 测试）：
     * 默认成功载荷；守卫样本=半语种载荷（首调 reused=false、复用轮 reused=true）；
     * 预算样本=预算耗尽
     */
    private NewsLlmBudgetService mockBudget() {
        NewsLlmBudgetService budget = mock(NewsLlmBudgetService.class);
        when(budget.call(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            ChatRequest request = invocation.getArgument(0, ChatRequest.class);
            String prompt = request.getMessages().get(0).getContent();
            String valid = "{\"title_zh\":\"标题\",\"title_en\":\"Title\","
                    + "\"summary_zh\":\"摘要内容。\\n\\n补充段落。\",\"summary_en\":\"Summary content.\\n\\nSecond paragraph.\","
                    + "\"category\":\"campus\",\"topics\":[]}";
            String halfBilingual = "{\"title_zh\":\"标题\",\"title_en\":\"Title\","
                    + "\"summary_zh\":\"只有中文。\\n\\n段落。\",\"summary_en\":\"\",\"category\":\"campus\",\"topics\":[]}";
            if (prompt.contains("budget deferred sample")) {
                throw new LlmBudgetExhaustedException("资讯 LLM 预算耗尽（回放注入）");
            }
            if (prompt.contains("guard reject sample")) {
                boolean reused = budgetCalls.computeIfAbsent("guard", k -> new AtomicInteger())
                        .incrementAndGet() > 1;
                return new NewsLlmBudgetService.LlmCall(halfBilingual, reused, () -> { });
            }
            return new NewsLlmBudgetService.LlmCall(valid, false, () -> { });
        });
        return budget;
    }

    private RawNewsItem candidate(String slug, long ageMillis) {
        String url = "https://www.polyu.edu.hk/en/media/" + slug;
        return new RawNewsItem(url, NewsUrlNormalizer.urlHash(url),
                slug.replace('-', ' '), null, "en",
                new Date(clock.millis - ageMillis), null, "campus-x");
    }

    private NewsFetchService.SourceCandidates campusBatch(List<RawNewsItem> items) {
        return new NewsFetchService.SourceCandidates(NewsSourceDO.builder()
                .id(1L).sourceKey("campus-x").platform("official")
                .fetchEndpoint("https://example.com/x").fetchStrategy("SITEMAP")
                .enabled(true).consecutiveFailures(0).build(), items);
    }

    private long countByStatus(String status) {
        return store.rows().stream().filter(r -> status.equals(r.getStatus())).count();
    }

    /**
     * 统一公开资格（查询侧同一判据在此按行求值，SQL 面归 Gate 测试/PG IT）：
     * status=published 且 (eligible_time IS NULL 或 ≤ now-180s)
     */
    private long visibleCount() {
        Date gateFloor = new Date(clock.millis - GATE);
        return store.rows().stream().filter(r -> NewsItemStatus.PUBLISHED.equals(r.getStatus())
                && (r.getEligibleTime() == null || !r.getEligibleTime().after(gateFloor))).count();
    }

    private NewsItemDO rowBy(String slug) {
        String url = "https://www.polyu.edu.hk/en/media/" + slug;
        return store.rows().stream().filter(r -> url.equals(r.getUrl())).findFirst().orElseThrow();
    }

    @Test
    void fullPipelineReplayAcrossGateTtlAndReappearance() {
        // ── 发现：5 新鲜（新→旧：A/B/E 成功样本、C 守卫样本、D 预算样本）+1 旧文（3 天）──
        List<RawNewsItem> candidates = new ArrayList<>(List.of(
                candidate("llm-success-a", 1 * HOUR),
                candidate("llm-success-b", 2 * HOUR),
                candidate("guard-reject-sample", 3 * HOUR),
                candidate("llm-success-e", 4 * HOUR),
                candidate("budget-deferred-sample", 5 * HOUR),
                candidate("stale-old-story", 3 * 24 * HOUR)));

        // ── 准入（T0）──
        fetchService.admitAll(List.of(campusBatch(candidates)));
        assertEquals(6, store.rows().size(), "发现=6（含归档行）");
        assertEquals(5, countByStatus(NewsItemStatus.PENDING), "准入=5（pending，FIFO id 序=新→旧）");
        assertEquals(1, countByStatus(NewsItemStatus.ARCHIVED), "旧文 48h 归档=1（不计准入、跳过付费富化）");

        // ── 富化第 1 轮（T0）：A/B/E 成功；C 守卫拒绝（新鲜响应）隔离；D 预算耗尽批降级 ──
        assertEquals(3, enrichService.enrichPendingItems(), "A/B/E 三条 LLM 富化成功");
        assertEquals(3, countByStatus(NewsItemStatus.PUBLISHED));
        NewsItemDO a = rowBy("llm-success-a");
        assertEquals(NewsItemStatus.SUMMARY_SOURCE_LLM, a.getSummarySource());
        assertEquals(clock.get(), a.getEligibleTime(), "发布门起点=资格就绪时刻（非抓取时间）");
        assertNotNull(a.getPromptVersion(), "prompt_version 随行落库（可追溯）");
        assertEquals(2, countByStatus(NewsItemStatus.PENDING), "C 守卫拒绝留待复用、D 预算延期");
        assertEquals(0, visibleCount(), "门未开：资格已就绪的 A/B/E 此刻也不可见（180s 从资格就绪起算）");

        // ── 门界：差 1 秒不过门，满 180s 可见 ──
        clock.advance(179_000);
        assertEquals(0, visibleCount(), "差 1 秒不过门");
        clock.advance(1_000);
        assertEquals(3, visibleCount(), "资格就绪 180s 后可见");

        // ── 富化第 2 轮（T0+1h，同日）：C 复用响应仍拒→明示零调用回退；D 预算仍耗尽 ──
        clock.advance(HOUR);
        assertEquals(0, enrichService.enrichPendingItems(), "C 走回退不算 LLM 成功");
        NewsItemDO c = rowBy("guard-reject-sample");
        assertEquals(NewsItemStatus.PUBLISHED, c.getStatus(), "守卫拒绝（复用响应）→零调用回退收尾");
        assertEquals(NewsItemStatus.SUMMARY_SOURCE_FALLBACK, c.getSummarySource());
        assertNull(c.getPromptVersion(), "回退无提示词参与");
        assertTrue(c.getSummaryZh().startsWith("原文标题（AI 摘要暂缺）："), "回退摘要=标题派生（可解释）");
        assertTrue(c.getSummaryEn().startsWith("Source headline (AI summary unavailable): "), "双语回退");
        NewsItemDO d = rowBy("budget-deferred-sample");
        assertEquals(NewsItemStatus.PENDING, d.getStatus(), "预算延期不回退：留待配额或 TTL");
        assertNull(d.getEligibleTime(), "预算延期未获资格——不能靠 180s 超时放行");

        // ── 门开之后的可见面（A/B/E=llm，C=fallback）──
        clock.advance(GATE + 1);
        assertEquals(4, visibleCount(), "公开展示=4（3 LLM+1 回退）");
        assertEquals(1, countByStatus(NewsItemStatus.PENDING), "仅预算延期样本仍待富化");

        // ── 同 URL 重现（+2h）：不重建任何待办 ──
        clock.advance(2 * HOUR);
        int rowsBefore = store.rows().size();
        fetchService.admitAll(List.of(campusBatch(candidates)));
        assertEquals(rowsBefore, store.rows().size(), "同 URL 重现零新行（expired/archived 不复活）");

        // ── TTL 48h 届满（自首次发现累计 49h+）：D 转 expired 终态 ──
        clock.advance(46 * HOUR);
        assertEquals(1, fetchService.expireOverduePending());
        // fake 返回克隆：终态断言须重取行（旧引用是 expire 前快照）
        assertEquals(NewsItemStatus.EXPIRED, rowBy("budget-deferred-sample").getStatus(),
                "待富化超 48h 未获资格→expired 终态退出待办");
        assertEquals(4, visibleCount(), "expired 不公开（终态）");

        // ── 六口径（事件数=0 占位，#187 落地持久事件身份）──
        List<NewsItemDO> rows = store.rows();
        assertEquals(6, rows.size(), "六口径·发现=6");
        assertEquals(5, rows.stream().filter(r -> !NewsItemStatus.ARCHIVED.equals(r.getStatus())).count(),
                "六口径·准入=5（归档不计）");
        // 回放以 URL 判内容身份=5（LLM 富化改写双语标题属预期；admin SQL 面用标题规范化代理口径）
        assertEquals(5, rows.stream().filter(r -> !NewsItemStatus.ARCHIVED.equals(r.getStatus()))
                        .map(NewsItemDO::getUrl).distinct().count(),
                "六口径·唯一内容=5");
        assertEquals(3, rows.stream()
                        .filter(r -> NewsItemStatus.SUMMARY_SOURCE_LLM.equals(r.getSummarySource())).count(),
                "六口径·富化成功=3");
        assertEquals(4, visibleCount(), "六口径·公开展示=4");
        // 六口径·事件数：本票为 0 口径占位——事件身份与聚簇归 #187（回放中无事件落库面）
    }
}
