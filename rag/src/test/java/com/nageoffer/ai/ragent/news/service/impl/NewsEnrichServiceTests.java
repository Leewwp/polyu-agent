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
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.core.parser.HtmlDocumentParser;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.model.LlmBudgetExhaustedException;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsHttpFetchClient;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 补全服务测试：JSON 解析容错（代码围栏/前后缀/非法）、
 * 固定 8 类越界落 other、词表命中回链、新词 curated=false 提案流、单条失败隔离、
 * YouTube 跳过正文抓取、maxTokens 透传与输入 token 截断（#184）、
 * 预算耗尽批降级（#184）。LLM 预算网关/HTTP/加载器全 mock，解析器用真实实现。
 */
class NewsEnrichServiceTests {

    private static final String VALID_PAYLOAD = "{\"title_zh\":\"标题\",\"title_en\":\"Title\","
            + "\"summary_zh\":\"摘要\",\"summary_en\":\"Summary\",\"category\":\"research\",\"topics\":[]}";

    private NewsItemMapper itemMapper;
    private NewsSourceMapper sourceMapper;
    private NewsTopicMapper topicMapper;
    private NewsItemTopicMapper itemTopicMapper;
    private NewsHttpFetchClient httpFetchClient;
    private NewsLlmBudgetService llmBudgetService;
    private NewsFetchProperties fetchProperties;
    private NewsEnrichService service;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsTopicDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsItemTopicDO.class);
        itemMapper = mock(NewsItemMapper.class);
        sourceMapper = mock(NewsSourceMapper.class);
        topicMapper = mock(NewsTopicMapper.class);
        itemTopicMapper = mock(NewsItemTopicMapper.class);
        httpFetchClient = mock(NewsHttpFetchClient.class);
        llmBudgetService = mock(NewsLlmBudgetService.class);
        fetchProperties = new NewsFetchProperties();
        service = new NewsEnrichService(itemMapper, sourceMapper, topicMapper, itemTopicMapper,
                httpFetchClient, new HtmlDocumentParser(), llmBudgetService,
                mock(PromptTemplateLoader.class), new com.fasterxml.jackson.databind.ObjectMapper(),
                fetchProperties);
        // 默认桩：预算网关直通返回有效载荷
        when(llmBudgetService.call(any(ChatRequest.class), any(Tier.class)))
                .thenReturn(new NewsLlmBudgetService.LlmCall(VALID_PAYLOAD, false, () -> { }));
    }

    private NewsItemDO item(long id) {
        return NewsItemDO.builder().id(id).sourceId(11L).url("https://www.polyu.edu.hk/en/media/" + id)
                .titleEn("PolyU team wins award").langRaw("en").status("published")
                .category("other").heat(0).build();
    }

    private NewsTopicDO vocabTopic(String slug, String zh, String en) {
        return NewsTopicDO.builder().id((long) (slug.hashCode() & 0xffff)).slug(slug)
                .nameZh(zh).nameEn(en).topicGroup("RESEARCH").curated(true).status("active").build();
    }

    @Test
    void parsePayloadStripsCodeFencesAndPrefix() {
        NewsEnrichService.NewsSummaryPayload payload = service.parsePayload("""
                ```json
                {"title_zh":"标题","title_en":"Title","summary_zh":"摘要","summary_en":"Summary",
                 "category":"research","topics":["ai"]}
                ```
                """);
        assertEquals("research", payload.category());
        assertEquals("标题", payload.title_zh());
        assertEquals(List.of("ai"), payload.topics());
    }

    @Test
    void parsePayloadRejectsNonJson() {
        assertThrows(IllegalArgumentException.class, () -> service.parsePayload("抱歉，我无法完成"));
        assertThrows(IllegalArgumentException.class, () -> service.parsePayload("{\"category\":}"));
        assertThrows(IllegalArgumentException.class, () -> service.parsePayload(null));
    }

    @Test
    void enrichPendingItemsIsolatesPerItemFailure() {
        NewsItemDO first = item(1);
        NewsItemDO second = item(2);
        when(itemMapper.selectList(any())).thenReturn(List.of(first, second));
        when(httpFetchClient.get(any())).thenReturn(
                ("<html><body><main><p>Research news content.</p></main></body></html>")
                        .getBytes(StandardCharsets.UTF_8));
        when(llmBudgetService.call(any(ChatRequest.class), any(Tier.class)))
                .thenReturn(new NewsLlmBudgetService.LlmCall("模型炸了", false, () -> { }))
                .thenReturn(new NewsLlmBudgetService.LlmCall(VALID_PAYLOAD, false, () -> { }));

        int enriched = service.enrichPendingItems();

        assertEquals(1, enriched, "首条 LLM 输出非法被隔离，第二条成功");
        verify(itemMapper, times(1)).update(any(), any());
    }

    @Test
    void applyPayloadClampsCategoryAndLinksVocabTopics() {
        NewsTopicDO ai = vocabTopic("ai", "人工智能", "Artificial Intelligence");
        when(topicMapper.selectList(any())).thenReturn(List.of(ai));
        when(itemTopicMapper.selectCount(any())).thenReturn(0L);
        // 模拟 MyBatis AUTO 主键回填（真实库由 BIGSERIAL+IdType.AUTO 承担）
        when(topicMapper.insert(any(NewsTopicDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, NewsTopicDO.class).setId(777L);
            return 1;
        });
        NewsEnrichService.NewsSummaryPayload payload = new NewsEnrichService.NewsSummaryPayload(
                "理大团队获奖", "PolyU team wins award", "理大团队获奖。", "PolyU team won.",
                "不存在的类别", List.of("Artificial intelligence", "全新概念"));

        service.applyPayload(item(5), payload);

        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).update(any(), captor.capture());
        Map<String, Object> params = ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<NewsItemDO>)
                captor.getValue()).getParamNameValuePairs();
        assertTrue(params.containsValue("other"), "越界分类落 other，实际=" + params);
        assertTrue(params.containsValue("理大团队获奖"));
        // 词表英文名忽略大小写命中 → 回链；新词提案回填 id 后也回链 → 两条关联行
        verify(itemTopicMapper, times(2)).insert(any(NewsItemTopicDO.class));
        ArgumentCaptor<NewsTopicDO> proposalCaptor = ArgumentCaptor.forClass(NewsTopicDO.class);
        verify(topicMapper).insert(proposalCaptor.capture());
        NewsTopicDO proposal = proposalCaptor.getValue();
        assertFalse(proposal.getCurated(), "新词以 curated=false 提案入库");
        assertEquals(NewsEnrichService.PROPOSAL_GROUP, proposal.getTopicGroup());
        assertTrue(proposal.getSlug().startsWith("prop-"));
    }

    @Test
    void youtubeItemsSkipDetailFetch() {
        NewsItemDO yt = NewsItemDO.builder().id(9L).sourceId(22L)
                .url("https://www.youtube.com/watch?v=x").titleEn("Video title")
                .langRaw("en").status("published").category("other").heat(0).build();
        when(sourceMapper.selectById(22L)).thenReturn(
                com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO.builder()
                        .id(22L).sourceKey("youtube-main").platform("youtube").build());
        when(itemMapper.selectList(any())).thenReturn(List.of(yt));

        int enriched = service.enrichPendingItems();

        assertEquals(1, enriched);
        verify(httpFetchClient, never()).get(anyString());
    }

    @Test
    void detailFetchFailureLeavesItemPending() {
        when(itemMapper.selectList(any())).thenReturn(List.of(item(3)));
        when(httpFetchClient.get(anyString())).thenThrow(
                new com.nageoffer.ai.ragent.news.fetch.NewsFetchException("Connect timed out", true));

        assertEquals(0, service.enrichPendingItems());
        verify(itemMapper, never()).update(any(), any());
    }

    @Test
    void vocabRenderedIntoPromptWithSlugs() {
        when(topicMapper.selectList(any())).thenReturn(List.of(vocabTopic("ai", "人工智能", "Artificial Intelligence")));
        // renderVocab 是私有逻辑，经 enrichOne 的 prompt 间接验证——此处直接验证词表查询发生
        NewsItemDO one = item(7);
        when(itemMapper.selectList(any())).thenReturn(List.of(one));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));
        when(llmBudgetService.call(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            throw new ClientException("stop-here");
        });
        service.enrichPendingItems();
        verify(topicMapper, times(1)).selectList(any());
    }

    @Test
    void renderPlainTextWalksBlocksAndTruncates() {
        var provenance = com.nageoffer.ai.ragent.core.parser.model.Provenance.ofFile("test.html");
        ParsedDocument doc = new ParsedDocument(java.util.Arrays.asList(
                new com.nageoffer.ai.ragent.core.parser.model.HeadingBlock(provenance, 2, "Title"),
                new ParagraphBlock(provenance, "First paragraph."),
                new com.nageoffer.ai.ragent.core.parser.model.ListBlock(provenance, false, List.of("a", "b")),
                null), Map.of());
        String text = NewsEnrichService.renderPlainText(doc, 100);
        assertTrue(text.contains("Title") && text.contains("First paragraph.") && text.contains("a；b"));
        assertEquals(10, NewsEnrichService.renderPlainText(doc, 10).length(), "超长截断到 maxChars");
        assertNull(NewsEnrichService.renderPlainText(new ParsedDocument(List.of(), Map.of()), 100));
    }

    // ================== #184：maxTokens 透传与输入截断 ==================

    @Test
    void enrichOneSetsMaxTokensAndEffectiveParamsOnRequest() {
        when(itemMapper.selectList(any())).thenReturn(List.of(item(11)));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));

        service.enrichPendingItems();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmBudgetService).call(captor.capture(), org.mockito.ArgumentMatchers.eq(Tier.FAST));
        ChatRequest request = captor.getValue();
        assertEquals(1024, request.getMaxTokens(), "输出上限 maxTokens=1024 透传（#184 范围1）");
        assertEquals(0.2D, request.getTemperature());
        assertEquals(0.3D, request.getTopP());
        assertFalse(Boolean.TRUE.equals(request.getThinking()));
    }

    @Test
    void enrichOneRespectsConfiguredSummaryMaxTokens() {
        fetchProperties.setSummaryMaxTokens(512);
        when(itemMapper.selectList(any())).thenReturn(List.of(item(12)));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));

        service.enrichPendingItems();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmBudgetService).call(captor.capture(), any(Tier.class));
        assertEquals(512, captor.getValue().getMaxTokens(), "输出上限随 rag.news.summary-max-tokens 外置可调");
    }

    @Test
    void fetchDetailTextTruncatesCjkContentToInputTokenBudget() {
        String cjk = "理".repeat(9000);
        when(httpFetchClient.get(any())).thenReturn(
                ("<html><body><main><p>" + cjk + "</p></main></body></html>").getBytes(StandardCharsets.UTF_8));

        String detail = service.fetchDetailText("https://www.polyu.edu.hk/x");

        assertTrue(estimateTokens(detail) <= 4000, "CJK 正文按 1 token/字截断 ≤4000（#184 范围2），实际估算="
                + estimateTokens(detail));
        assertEquals(4000, detail.length(), "CJK 全文场景截到 4000 字");
    }

    @Test
    void fetchDetailTextKeepsEnglishWithinCharAndTokenBudget() {
        String english = "word ".repeat(1200); // 6000 字符 ≈1500 token，双上限均不触界
        when(httpFetchClient.get(any())).thenReturn(
                ("<html><body><main><p>" + english + "</p></main></body></html>").getBytes(StandardCharsets.UTF_8));

        String detail = service.fetchDetailText("https://www.polyu.edu.hk/y");

        assertTrue(estimateTokens(detail) <= 4000);
        assertTrue(detail.length() <= NewsEnrichService.MAX_CONTENT_CHARS);
    }

    @Test
    void truncateByTokenEstimateBoundaryCases() {
        assertEquals("a".repeat(16000), NewsEnrichService.truncateByTokenEstimate("a".repeat(16000), 4000),
                "16000 ASCII=4000 token 恰好不截断");
        assertEquals(16000, NewsEnrichService.truncateByTokenEstimate("a".repeat(16001), 4000).length(),
                "16001 ASCII 超预算截到 16000");
        assertEquals(4000, NewsEnrichService.truncateByTokenEstimate("中".repeat(4001), 4000).length(),
                "4001 CJK 超预算截到 4000");
        assertNull(NewsEnrichService.truncateByTokenEstimate(null, 4000));
        assertEquals("text", NewsEnrichService.truncateByTokenEstimate("text", 0), "上限非正时不截断（守卫）");
    }

    // ================== #184：预算耗尽批降级与无效响应回报 ==================

    @Test
    void budgetExhaustionDegradesRemainingBatchWithoutEnriching() {
        NewsItemDO first = item(21);
        NewsItemDO second = item(22);
        when(itemMapper.selectList(any())).thenReturn(List.of(first, second));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));
        when(llmBudgetService.call(any(ChatRequest.class), any(Tier.class)))
                .thenThrow(new LlmBudgetExhaustedException("资讯 LLM 预算耗尽"));

        int enriched = service.enrichPendingItems();

        assertEquals(0, enriched, "预算耗尽当日降级=仅入库不富化");
        verify(llmBudgetService, times(1)).call(any(ChatRequest.class), any(Tier.class));
        verify(itemMapper, never()).update(any(), any());
        verify(itemMapper, times(1)).selectList(any());
    }

    @Test
    void parseFailureReportsInvalidBackToReceipt() {
        Runnable invalidReporter = mock(Runnable.class);
        when(itemMapper.selectList(any())).thenReturn(List.of(item(31)));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));
        when(llmBudgetService.call(any(ChatRequest.class), any(Tier.class)))
                .thenReturn(new NewsLlmBudgetService.LlmCall("不是JSON", false, invalidReporter));

        assertEquals(0, service.enrichPendingItems());

        verify(invalidReporter).run();
        verify(itemMapper, never()).update(any(), any());
    }

    /**
     * 测试侧同口径 token 估算（CJK/全角=1，其余 4 字符=1）
     */
    private static long estimateTokens(String text) {
        long quarter = 0;
        for (int i = 0; i < text.length(); i++) {
            quarter += NewsEnrichService.isFullWidth(text.charAt(i)) ? 4L : 1L;
        }
        return (quarter + 3) / 4;
    }
}
