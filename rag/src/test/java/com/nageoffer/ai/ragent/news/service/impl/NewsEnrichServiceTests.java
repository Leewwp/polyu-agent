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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
    private PromptTemplateLoader promptTemplateLoader;

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
        promptTemplateLoader = mock(PromptTemplateLoader.class);
        // prompt_version 取模板哈希：mock 加载器给固定模板文本（真模板路径由 serviceWithRealTemplateLoader 覆盖）
        when(promptTemplateLoader.load(anyString())).thenReturn("# 角色\n模板\n");
        service = new NewsEnrichService(itemMapper, sourceMapper, topicMapper, itemTopicMapper,
                httpFetchClient, new HtmlDocumentParser(), llmBudgetService,
                promptTemplateLoader, new com.fasterxml.jackson.databind.ObjectMapper(),
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

    // ================== #184 修正点5：解析 fixture（只证明解析，不证明 token 长度） ==================

    /**
     * <b>parse-only fixture</b>（#184 修正点5 重标注，2026-09-29）：只证明解析路径对
     * 双语长度合同<b>极限形状</b>载荷（中文摘要 400 字 2 段+英文摘要 260 词+双语标题
     * +分类+4 标签）的鲁棒性。<b>不构成 maxTokens=1024 必然容纳该载荷的证据</b>——
     * 字符/4 估算是保守口径，未经 qwen 系真实分词或生成 token 证据核验；若真实输出
     * 被 1024 截断，产生的不完整 JSON 走解析失败受控降级（见
     * {@link #b06TruncatedResponseNotPublishedAndBatchBounded()}），必要时上调
     * rag.news.summary-max-tokens 并同步成本模型
     */
    @Test
    void maximalContractPayloadIsParseableParseOnlyFixture() {
        String zh = "理".repeat(400);
        String en = String.join(" ",
                java.util.stream.IntStream.rangeClosed(1, 260).mapToObj(i -> "word" + i).toList());
        String json = "{\"title_zh\":\"二〇二六年秋季招生与研究要闻汇总\",\"title_en\":\"Admission and Research Digest Autumn 2026\","
                + "\"summary_zh\":\"" + zh.substring(0, 200) + "\\n\\n" + zh.substring(200) + "\","
                + "\"summary_en\":\"" + en + "\","
                + "\"category\":\"research\",\"topics\":[\"ai\",\"research\",\"campus\",\"admission\"]}";

        NewsEnrichService.NewsSummaryPayload payload = service.parsePayload(json);
        assertEquals("research", payload.category());
        assertEquals(4, payload.topics().size());
        assertTrue(payload.summary_zh().contains("\n\n"), "段间分隔按约定解析");
    }

    // ================== B06：1024 截断/无效 JSON 不发布且重试有限（富化侧） ==================

    @Test
    void b06TruncatedResponseNotPublishedAndBatchBounded() {
        NewsItemDO first = item(41);
        NewsItemDO second = item(42);
        when(itemMapper.selectList(any())).thenReturn(List.of(first, second));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));
        // max_tokens 截断形状：JSON 在字符串中途断裂（无闭合括号）——解析必然失败
        String truncated = "{\"title_zh\":\"标题\",\"title_en\":\"Title\",\"summary_zh\":\"摘要被截断于中";
        Runnable invalidReporter = mock(Runnable.class);
        when(llmBudgetService.call(any(ChatRequest.class), any(Tier.class)))
                .thenReturn(new NewsLlmBudgetService.LlmCall(truncated, false, invalidReporter))
                .thenReturn(new NewsLlmBudgetService.LlmCall(VALID_PAYLOAD, false, () -> { }));

        int enriched = service.enrichPendingItems();

        assertEquals(1, enriched, "截断响应条目不发布，批内下一条正常处理（单条隔离，不无限重试）");
        verify(invalidReporter).run();
        verify(itemMapper, org.mockito.Mockito.times(1)).update(any(), any());
    }

    // ================== B04：完整渲染请求的输入闭合（富化侧） ==================

    /**
     * 真实模板（prompt/news-summary.st）+超长标题+动态词表+混合字符正文：
     * 送出的<b>完整渲染请求</b>（模板指令+词表+标题+正文合计）在输入限额
     * （max-input-tokens+提示词开销=6000，估算口径）内——静态部分+正文合计超限时
     * 压缩正文块（保留头部、移除尾部标记）而非裸送
     */
    @Test
    void b04CompleteRenderedPromptStaysWithinInputQuota() {
        NewsEnrichService realLoaderService = serviceWithRealTemplateLoader();
        // 静态部分做大（超长标题+100 条词表 ≈3800 token）+正文顶满 4000 token → 完整 prompt ≈7800 >6000 触发收缩
        String longTitle = "理大研究通报国际合作联合实验室公告 ".repeat(20);
        NewsItemDO pending = NewsItemDO.builder().id(51L).sourceId(11L)
                .url("https://www.polyu.edu.hk/en/media/51")
                .titleEn(longTitle).langRaw("en").status("published").category("other").heat(0).build();
        when(itemMapper.selectList(any())).thenReturn(List.of(pending));
        // 混合字符正文（ASCII 头部+CJK 主体+ASCII 尾部标记 ≈4000 token）：预截断不触界，完整 prompt 超限后由输入闭合唱收缩
        String content = "HEADMARK9 ".repeat(200) + "理大研究要闻 ".repeat(480) + "TAILMARK9 ".repeat(200);
        when(httpFetchClient.get(any())).thenReturn(
                ("<html><body><main><p>" + content + "</p></main></body></html>").getBytes(StandardCharsets.UTF_8));
        List<NewsTopicDO> vocab = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            vocab.add(vocabTopic("topic-" + i, "第" + i + "号超长动态主题词表中文名称条目",
                    "Oversized Dynamic Vocabulary Topic Entry Number " + i));
        }
        when(topicMapper.selectList(any())).thenReturn(vocab);

        realLoaderService.enrichPendingItems();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmBudgetService).call(captor.capture(), org.mockito.ArgumentMatchers.eq(Tier.FAST));
        String prompt = captor.getValue().getMessages().get(0).getContent();
        assertTrue(NewsEnrichService.estimatePromptTokens(prompt) <= 6000,
                "完整渲染请求（模板+词表+标题+正文）≤ 输入限额 6000（估算口径），实际="
                        + NewsEnrichService.estimatePromptTokens(prompt));
        assertTrue(prompt.contains("理大研究通报国际合作联合实验室公告"), "超长标题完整入 prompt");
        assertTrue(prompt.contains("topic-99"), "动态词表渲染入 prompt");
        assertTrue(prompt.contains("HEADMARK9"), "正文保留头部");
        assertFalse(prompt.contains("TAILMARK9"), "超限部分经压缩正文移除（完整 prompt 收口，非仅正文截断）");
    }

    /**
     * 静态部分（模板+词表+标题）自身超输入限额：拒绝付费准入（条目隔离不发出），
     * 不裸送超限请求
     */
    @Test
    void b04OversizedStaticPromptRefusesAdmissionWithoutSending() {
        NewsEnrichService realLoaderService = serviceWithRealTemplateLoader();
        NewsItemDO pending = NewsItemDO.builder().id(52L).sourceId(11L)
                .url("https://www.polyu.edu.hk/en/media/52")
                .titleEn("PolyU news").langRaw("en").status("published").category("other").heat(0).build();
        when(itemMapper.selectList(any())).thenReturn(List.of(pending));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));
        List<NewsTopicDO> hugeVocab = new java.util.ArrayList<>();
        for (int i = 0; i < 500; i++) {
            hugeVocab.add(vocabTopic("topic-" + i, "超长动态词表主题第" + i + "号中文名称用于撑爆静态预算", "Oversized Vocabulary Entry Number " + i));
        }
        when(topicMapper.selectList(any())).thenReturn(hugeVocab);

        int enriched = realLoaderService.enrichPendingItems();

        assertEquals(0, enriched, "静态部分超限=拒绝付费准入，当日该条目仅入库不富化");
        verify(llmBudgetService, never()).call(any(ChatRequest.class), any(Tier.class));
        verify(itemMapper, never()).update(any(), any());
    }

    // ================== #185：选题口径/发布资格落库/零调用回退/提示词版本 ==================

    private NewsItemDO pendingItem(long id) {
        return NewsItemDO.builder().id(id).sourceId(11L).url("https://www.polyu.edu.hk/en/media/" + id)
                .titleEn("PolyU team wins award").titleZh("理大团队获奖").langRaw("en")
                .status("pending").category("other").heat(0)
                .fetchTime(new java.util.Date(System.currentTimeMillis() - 3600_000L)).build();
    }

    @Test
    void selectionTargetsPendingFifoWithinTtl() {
        when(itemMapper.selectList(any())).thenReturn(List.of(pendingItem(61)));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));

        service.enrichPendingItems();

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<NewsItemDO>> captor =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.Wrapper.class);
        verify(itemMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("status ="), "选题限定 pending（富化前不可公开），实际=" + sql);
        assertTrue(sql.contains("summary_en IS NULL"), "缺摘要条目，实际=" + sql);
        assertTrue(sql.contains("fetch_time >="), "超龄（TTL 48h 外）条目不选题，实际=" + sql);
        assertTrue(sql.contains("ORDER BY id ASC"), "FIFO 确定性选题（重启不重排），实际=" + sql);
    }

    @Test
    void enrichSuccessRecordsPublishEligibilityAndPromptVersion() {
        java.util.Date fixedNow = new java.util.Date(1757548800000L);
        NewsEnrichService clockService = new NewsEnrichService(itemMapper, sourceMapper, topicMapper,
                itemTopicMapper, httpFetchClient, new HtmlDocumentParser(), llmBudgetService,
                promptTemplateLoader, new com.fasterxml.jackson.databind.ObjectMapper(),
                fetchProperties, () -> fixedNow);
        NewsItemDO pending = pendingItem(62);
        when(itemMapper.selectList(any())).thenReturn(List.of(pending));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));

        clockService.enrichPendingItems();

        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).update(any(), captor.capture());
        Map<String, Object> params = ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<NewsItemDO>)
                captor.getValue()).getParamNameValuePairs();
        assertEquals("published", statusValue(params), "富化成功落发布资格（处理状态/可见性分离），实际=" + params);
        assertEquals("llm", params.values().stream()
                .filter("llm"::equals).findFirst().orElse(null), "summary_source=llm");
        assertEquals(fixedNow, params.values().stream()
                .filter(java.util.Date.class::isInstance).findFirst().orElse(null),
                "eligible_time=资格就绪时刻（发布门 180s 从此起算）");
        Object version = params.values().stream()
                .filter(v -> v instanceof String vStr && vStr.matches("[0-9a-f]{12}")).findFirst().orElse(null);
        assertNotNull(version, "prompt_version=模板哈希 12 位随行落库（可追溯）");
    }

    @Test
    void guardRejectionOnFreshResponseStaysPendingForOneFreeReuse() {
        NewsItemDO pending = pendingItem(63);
        when(itemMapper.selectList(any())).thenReturn(List.of(pending));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));
        // 守卫拒绝形状：英文摘要缺失（双语完整性）
        String halfBilingual = "{\"title_zh\":\"标题\",\"title_en\":\"Title\","
                + "\"summary_zh\":\"摘要\",\"summary_en\":\"\",\"category\":\"research\",\"topics\":[]}";
        Runnable invalidReporter = mock(Runnable.class);
        when(llmBudgetService.call(any(ChatRequest.class), any(Tier.class)))
                .thenReturn(new NewsLlmBudgetService.LlmCall(halfBilingual, false, invalidReporter));

        int enriched = service.enrichPendingItems();

        assertEquals(0, enriched, "守卫拒绝的新鲜响应：本轮不发布");
        verify(invalidReporter).run();
        verify(itemMapper, never()).update(any(), any());
    }

    @Test
    void guardRejectionOnReusedResponseAppliesZeroCallFallback() {
        NewsItemDO pending = pendingItem(64);
        when(itemMapper.selectList(any())).thenReturn(List.of(pending));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));
        String halfBilingual = "{\"title_zh\":\"标题\",\"title_en\":\"Title\","
                + "\"summary_zh\":\"摘要\",\"summary_en\":\"\",\"category\":\"research\",\"topics\":[]}";
        Runnable invalidReporter = mock(Runnable.class);
        when(llmBudgetService.call(any(ChatRequest.class), any(Tier.class)))
                .thenReturn(new NewsLlmBudgetService.LlmCall(halfBilingual, true, invalidReporter));

        int enriched = service.enrichPendingItems();

        assertEquals(0, enriched, "零调用回退不算 LLM 成功");
        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).update(any(), captor.capture());
        Map<String, Object> params = ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<NewsItemDO>)
                captor.getValue()).getParamNameValuePairs();
        assertEquals("fallback", params.values().stream()
                .filter("fallback"::equals).findFirst().orElse(null), "summary_source=fallback（明示零调用回退）");
        assertTrue(params.containsValue("原文标题（AI 摘要暂缺）：理大团队获奖"), "回退摘要=标题派生（zh 槽）");
        assertTrue(params.containsValue("Source headline (AI summary unavailable): PolyU team wins award"),
                "回退摘要=标题派生（en 槽，双语回退）");
        assertFalse(params.values().stream()
                .anyMatch(v -> v instanceof String vStr && vStr.matches("[0-9a-f]{12}")),
                "回退无提示词参与，不落 prompt_version");
    }

    @Test
    void terminalReceiptStateAppliesFallbackWithoutNewCalls() {
        NewsItemDO pending = pendingItem(65);
        when(itemMapper.selectList(any())).thenReturn(List.of(pending));
        when(httpFetchClient.get(any())).thenReturn("<p>content</p>".getBytes(StandardCharsets.UTF_8));
        // 回执终态（POISONED 隔离/重试预算耗尽）：预算服务抛 IllegalStateException，零新增请求
        when(llmBudgetService.call(any(ChatRequest.class), any(Tier.class)))
                .thenThrow(new IllegalStateException("资讯 LLM 回执已隔离（模型输出持续无效）"));

        int enriched = service.enrichPendingItems();

        assertEquals(0, enriched, "回退不算 LLM 成功但条目处理完成（不留在待办无限重试）");
        ArgumentCaptor<Wrapper<NewsItemDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(itemMapper).update(any(), captor.capture());
        Map<String, Object> params = ((com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<NewsItemDO>)
                captor.getValue()).getParamNameValuePairs();
        assertEquals("fallback", params.values().stream().filter("fallback"::equals).findFirst().orElse(null));
    }

    @Test
    void promptVersionIsTemplateHashAndStablePerProcess() throws Exception {
        NewsEnrichService realLoaderService = serviceWithRealTemplateLoader();
        String version = realLoaderService.currentPromptVersion();
        assertTrue(version.matches("[0-9a-f]{12}"), "版本=sha256(模板全文) 前 12 位，实际=" + version);
        byte[] template = new org.springframework.core.io.DefaultResourceLoader()
                .getResource("classpath:prompt/news-summary.st").getInputStream().readAllBytes();
        assertEquals(com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer
                        .sha256Hex(new String(template, StandardCharsets.UTF_8)).substring(0, 12),
                version, "版本与模板全文哈希一致——改词即版本变化");
        assertEquals(version, realLoaderService.currentPromptVersion(), "进程内缓存稳定");
    }

    /** 更新参数里的 status 目标值（published/…） */
    private static String statusValue(Map<String, Object> params) {
        return params.values().stream()
                .filter(v -> v instanceof String s
                        && java.util.Set.of("pending", "published", "archived", "expired", "hidden").contains(s))
                .map(Object::toString).findFirst().orElse(null);
    }

    private NewsEnrichService serviceWithRealTemplateLoader() {
        PromptTemplateLoader loader = new PromptTemplateLoader(new org.springframework.core.io.DefaultResourceLoader());
        return new NewsEnrichService(itemMapper, sourceMapper, topicMapper, itemTopicMapper,
                httpFetchClient, new HtmlDocumentParser(), llmBudgetService,
                loader, new com.fasterxml.jackson.databind.ObjectMapper(), fetchProperties);
    }

    /**
     * 测试侧同口径 token 估算（CJK/全角=1，其余 4 字符=1）——估算口径，非分词证明
     */
    private static long estimateTokens(String text) {
        long quarter = 0;
        for (int i = 0; i < text.length(); i++) {
            quarter += NewsEnrichService.isFullWidth(text.charAt(i)) ? 4L : 1L;
        }
        return (quarter + 3) / 4;
    }
}
