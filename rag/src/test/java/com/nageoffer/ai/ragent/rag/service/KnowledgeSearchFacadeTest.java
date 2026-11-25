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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.KnowledgeEvidenceRenderer;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 门面契约：知识库作答形态在门面边界分流（默认合成；取 evidence 交回资料原文），
 * 内部 docId 不得随工具结果外泄，近期轮次要喂到改写
 */
class KnowledgeSearchFacadeTest {

    private static final String QUESTION = "报销标准是多少";
    private static final String KB_CONTEXT = """
            <content data-ragent-doc-id="doc-a">
            差旅报销上限 800 元
            </content>
            """;

    private final QueryRewriteService queryRewriteService = mock(QueryRewriteService.class);
    private final IntentResolver intentResolver = mock(IntentResolver.class);
    private final KnowledgeDocumentMapper knowledgeDocumentMapper = mock(KnowledgeDocumentMapper.class);
    private final IntentGuidanceService guidanceService = mock(IntentGuidanceService.class);
    private final RetrievalEngine retrievalEngine = mock(RetrievalEngine.class);
    private final RAGPromptService promptService = mock(RAGPromptService.class);
    private final LLMService llmService = mock(LLMService.class);
    private final KnowledgeEvidenceRenderer evidenceRenderer = mock(KnowledgeEvidenceRenderer.class);

    /**
     * 引用开关开着也不能漏：门面这条路不装配来源，锚点只能被抹掉不能被翻译成编号
     */
    @Test
    void removesInternalDocumentIdsBeforeHandingContextToModel() {
        KnowledgeSearchFacade facade = facade(true);
        stubRetrievalHit();

        facade.search(QUESTION);

        String kbContext = capturePromptContext().getKbContext();
        assertFalse(kbContext.contains("data-ragent-doc-id"), "内部文档 ID 不得进入模型可见文本");
        assertFalse(kbContext.contains("ref="), "门面无来源编号，不得注入角标");
        assertTrue(kbContext.contains("差旅报销上限 800 元"), "正文不应被改写规则误伤");
    }

    /**
     * 改写与合成两端都不带历史
     * <p>
     * 主 Agent 传进来的已是消解过、且被它有意收窄的查询，再喂历史会让改写拿上一轮的
     * 实体把它反向补全，产出看着合理却检索错的问题
     */
    @Test
    void passesNoHistoryToRewriteNorSynthesis() {
        KnowledgeSearchFacade facade = facade(false);
        stubRetrievalHit();

        facade.search("它的上限是多少");

        ArgumentCaptor<List<ChatMessage>> rewriteHistory = ArgumentCaptor.forClass(List.class);
        verify(queryRewriteService).rewriteWithSplit(anyString(), rewriteHistory.capture());
        assertTrue(rewriteHistory.getValue().isEmpty(), "改写阶段不得拿到会话历史");

        ArgumentCaptor<List<ChatMessage>> promptHistory = ArgumentCaptor.forClass(List.class);
        verify(promptService).buildStructuredMessages(
                any(PromptContext.class), promptHistory.capture(), anyString(), anyList(), anyBoolean(), any());
        assertTrue(promptHistory.getValue().isEmpty(), "合成阶段只依据本次证据");
    }

    /**
     * 零 KB 意图不是「没得查」而是「不知道查哪」：子问题须原样下传，由作用域解析器回落全局检索
     * <p>
     * 逐子问题各判各的，与 v1 管线同构：一个子问题蒙中意图，不该让其余子问题连库都进不去
     */
    @Test
    void keepsIntentlessSubQuestionsSoScopeResolverCanFallBackToGlobal() {
        KnowledgeSearchFacade facade = facade(false);
        NodeScore kbNode = NodeScore.builder()
                .node(IntentNode.builder().id("kb-1").build())
                .score(1.0D)
                .build();
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult(QUESTION, List.of("报销标准是多少", "公司福利有哪些")));
        when(intentResolver.resolve(any(RewriteResult.class))).thenReturn(List.of(
                new SubQuestionIntent("报销标准是多少", List.of(kbNode)),
                new SubQuestionIntent("公司福利有哪些", List.of())
        ));
        when(intentResolver.mergeKbIntents(anyList())).thenReturn(List.of(kbNode));
        when(retrievalEngine.retrieve(anyList()))
                .thenReturn(RetrievalContext.builder().kbContext(KB_CONTEXT).build());
        when(promptService.buildStructuredMessages(
                any(PromptContext.class), anyList(), anyString(), anyList(), anyBoolean(), any()))
                .thenReturn(List.of());
        when(llmService.chat(any())).thenReturn("答案");

        facade.search(QUESTION);

        ArgumentCaptor<List<SubQuestionIntent>> retrieved = ArgumentCaptor.forClass(List.class);
        verify(retrievalEngine).retrieve(retrieved.capture());
        assertEquals(List.of("报销标准是多少", "公司福利有哪些"),
                retrieved.getValue().stream().map(SubQuestionIntent::subQuestion).toList(),
                "零意图子问题不得在门面被丢弃");
    }

    @Test
    void returnsGuidanceWithoutRetrievalOrAnswerSynthesisWhenQuestionIsAmbiguous() {
        KnowledgeSearchFacade facade = facade(false);
        NodeScore oaSecurity = NodeScore.builder()
                .node(IntentNode.builder().id("oa-security").name("数据安全").build())
                .score(0.62D)
                .build();
        NodeScore insuranceSecurity = NodeScore.builder()
                .node(IntentNode.builder().id("insurance-security").name("数据安全").build())
                .score(0.60D)
                .build();
        List<SubQuestionIntent> subIntents = List.of(
                new SubQuestionIntent(QUESTION, List.of(oaSecurity, insuranceSecurity)));
        String prompt = "关于数据安全，请选择 OA 系统或保险系统";
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult(QUESTION, List.of(QUESTION)));
        when(intentResolver.resolve(any(RewriteResult.class))).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity(QUESTION, subIntents))
                .thenReturn(GuidanceDecision.prompt(prompt));

        String result = facade.search(QUESTION);

        assertEquals(prompt, result);
        verify(retrievalEngine, never()).retrieve(anyList());
        verify(promptService, never()).buildStructuredMessages(
                any(PromptContext.class), anyList(), anyString(), anyList(), anyBoolean(), any());
        verify(llmService, never()).chat(any());
    }

    /**
     * 来源按 docId 去重保序、上限 8 条、摘录抹锚点；歧义引导路径 sources 恒空
     */
    @Test
    void collectsDedupedCappedSourcesBesideAnswer() {
        KnowledgeSearchFacade facade = facade(false);
        NodeScore kbNode = NodeScore.builder()
                .node(IntentNode.builder().id("kb-1").build())
                .score(1.0D)
                .build();
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult(QUESTION, List.of(QUESTION)));
        when(intentResolver.resolve(any(RewriteResult.class)))
                .thenReturn(List.of(new SubQuestionIntent(QUESTION, List.of(kbNode))));
        when(intentResolver.mergeKbIntents(anyList()))
                .thenReturn(List.of(kbNode));
        // doc-a 跨意图重复出现只算一篇；doc-b..doc-j 共 9 篇新文档，凑满 10 篇后截 8 条（doc-h 止）
        java.util.Map<String, List<RetrievedChunk>> intentChunks = new java.util.LinkedHashMap<>();
        // chunk 原文先于 kbContext 装配（锚点是 context-format.st 装配时才加的），摘录天然无锚点；防御性 strip 仍在
        RetrievedChunk docA = RetrievedChunk.builder()
                .id("c-1").docId("doc-a").docName("文档A")
                .text("甲文档首段").score(0.9F).build();
        intentChunks.put("intent-1", new java.util.ArrayList<>(List.of(docA)));
        List<RetrievedChunk> second = new java.util.ArrayList<>();
        for (int i = 0; i < 9; i++) {
            second.add(RetrievedChunk.builder()
                    .id("c-" + (i + 2)).docId("doc-" + (char) ('a' + i + 1)).docName("文档" + (char) ('A' + i + 1))
                    .text("第" + (i + 1) + "篇正文").score(0.8F).build());
        }
        // doc-a 二次命中：同文档不同 chunk，去重后仍一篇
        second.add(RetrievedChunk.builder()
                .id("c-99").docId("doc-a").docName("文档A").text("甲文档第二段").score(0.7F).build());
        intentChunks.put("intent-2", second);
        when(retrievalEngine.retrieve(anyList()))
                .thenReturn(RetrievalContext.builder().kbContext(KB_CONTEXT).intentChunks(intentChunks).build());
        when(promptService.buildStructuredMessages(
                any(PromptContext.class), anyList(), anyString(), anyList(), anyBoolean(), any()))
                .thenReturn(List.of());
        when(llmService.chat(any())).thenReturn("答案");

        KnowledgeSearchFacade.KnowledgeSearchOutcome outcome = facade.searchWithSources(QUESTION);

        assertEquals("答案", outcome.answer());
        assertEquals(8, outcome.sources().size(), "来源上限 8 条");
        assertEquals("doc-a", outcome.sources().get(0).docId(), "首见保序");
        assertEquals("文档A", outcome.sources().get(0).docName());
        assertFalse(outcome.sources().get(0).excerpt().contains("data-ragent-doc-id"), "摘录须抹内部锚点");
        assertTrue(outcome.sources().get(0).excerpt().contains("甲文档首段"));
        assertEquals("doc-h", outcome.sources().get(7).docId(), "截 8 条即止");
        assertFalse(outcome.sources().stream().anyMatch(s -> "doc-i".equals(s.docId()) || "doc-j".equals(s.docId())),
                "第 9 篇起不出现");
    }

    /**
     * 歧义引导：answer 是引导文案，sources 恒空（没查过库就没有来源）
     */
    @Test
    void guidancePathCarriesNoSources() {
        KnowledgeSearchFacade facade = facade(false);
        NodeScore a = NodeScore.builder().node(IntentNode.builder().id("oa").build()).score(0.62D).build();
        NodeScore b = NodeScore.builder().node(IntentNode.builder().id("ins").build()).score(0.60D).build();
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent(QUESTION, List.of(a, b)));
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult(QUESTION, List.of(QUESTION)));
        when(intentResolver.resolve(any(RewriteResult.class))).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity(QUESTION, subIntents))
                .thenReturn(GuidanceDecision.prompt("请选择系统"));

        KnowledgeSearchFacade.KnowledgeSearchOutcome outcome = facade.searchWithSources(QUESTION);

        assertEquals("请选择系统", outcome.answer());
        assertTrue(outcome.sources().isEmpty());
    }

    private KnowledgeSearchFacade facade(boolean citationEnabled) {
        return facade(citationEnabled, new SearchChannelProperties(), evidenceRenderer);
    }

    private KnowledgeSearchFacade facade(boolean citationEnabled, SearchChannelProperties search,
                                         KnowledgeEvidenceRenderer renderer) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setCitationEnabled(citationEnabled);
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        return new KnowledgeSearchFacade(queryRewriteService, intentResolver, guidanceService, retrievalEngine,
                new CitationContextEnricher(properties), promptService, llmService, knowledgeDocumentMapper,
                renderer, search);
    }

    /**
     * 取 evidence 的门面：渲染器用真的，文档名开关按入参
     */
    private KnowledgeSearchFacade evidenceFacade(boolean includeDocName) {
        SearchChannelProperties search = new SearchChannelProperties();
        search.setKnowledgeAnswerForm(SearchChannelProperties.KnowledgeAnswerForm.EVIDENCE);
        search.setIncludeDocName(includeDocName);
        return facade(false, search, new KnowledgeEvidenceRenderer(search));
    }

    /**
     * 开关缺省走合成：资料原文渲染器一次都不碰，答案来自工具内的合成
     */
    @Test
    void synthesizedFormIsTheDefaultAndNeverRendersEvidence() {
        stubRetrievalHit();

        KnowledgeSearchFacade.KnowledgeSearchOutcome outcome = facade(false).searchWithSources(QUESTION);

        assertEquals("差旅报销上限 800 元", outcome.answer());
        verify(evidenceRenderer, never()).render(anyList(), anyList());
    }

    /**
     * 资料原文：按文档归组的 retrieval 文本，工具内不合成；来源徽章照旧带出，docId 只走旁路
     */
    @Test
    void evidenceFormReturnsSourceTextWithBadgesAndWithoutSynthesis() {
        stubEvidenceHit();

        KnowledgeSearchFacade.KnowledgeSearchOutcome outcome = evidenceFacade(false).searchWithSources(QUESTION, "zh");

        assertEquals("<retrieval>\n<document>\n差旅报销上限 800 元\n</document>\n</retrieval>", outcome.answer());
        assertEquals(List.of("doc-a"),
                outcome.sources().stream().map(KnowledgeSearchFacade.KnowledgeSearchSource::docId).toList());
        verify(promptService, never()).buildStructuredMessages(
                any(), anyList(), anyString(), anyList(), anyBoolean(), any());
        verify(llmService, never()).chat(any());
    }

    @Test
    void evidenceFormPutsDocNameFirstOnlyWhenDocNameSwitchIsOn() {
        stubEvidenceHit();

        String result = evidenceFacade(true).searchWithSources(QUESTION).answer();

        assertEquals("<retrieval>\n<document>\n<doc_name>差旅制度.md</doc_name>\n差旅报销上限 800 元\n</document>\n</retrieval>",
                result);
    }

    /**
     * 规则带意图全路径（叶子可能同名）；没留下资料的子问题，其意图虽仍 eligible，也不输出规则
     */
    @Test
    void evidenceFormLabelsRulesByIntentPathAndSkipsIntentsWithoutEvidence() {
        stubEvidenceHit();
        when(intentResolver.mergeKbIntents(anyList())).thenReturn(List.of(
                rule("kb-1", "数据安全", "手机服务 > 数据安全", "先说要不要备份"),
                rule("kb-2", "数据安全", "账户服务 > 数据安全", "先确认账号归属"),
                rule("kb-3", "以旧换新", "售后服务 > 以旧换新", "先说能不能折抵")));
        when(retrievalEngine.retrieve(anyList())).thenReturn(
                evidenceHit(Set.of("kb-1", "kb-2", "kb-3"), Set.of("kb-1", "kb-2")));

        String result = evidenceFacade(false).searchWithSources(QUESTION).answer();

        assertTrue(result.contains(
                "<rules>\n[手机服务 > 数据安全] 先说要不要备份\n[账户服务 > 数据安全] 先确认账号归属\n</rules>"), result);
        assertFalse(result.contains("以旧换新"), result);
    }

    /**
     * 没留下分片：按本轮语言出一句未检索到，渲染器与合成都不碰
     */
    @Test
    void evidenceFormReturnsLocalizedNoticeWhenNothingRetrieved() {
        stubEvidenceHit();
        when(retrievalEngine.retrieve(anyList())).thenReturn(RetrievalContext.builder().build());

        assertEquals("未在知识库中检索到与该问题相关的内容。",
                evidenceFacade(false).searchWithSources(QUESTION, "zh").answer());
        assertEquals("No relevant content was found in the knowledge base for this question.",
                evidenceFacade(false).searchWithSources(QUESTION, "en").answer());
        assertEquals("未在知识库中检索到与该问题相关的内容。",
                evidenceFacade(false).searchWithSources(QUESTION).answer());
        verify(llmService, never()).chat(any());
    }

    /**
     * 入口判定的语言贯穿到合成：answerLanguage 原样传给提示词服务第六参，不中途变形
     */
    @Test
    void passesAnswerLanguageThroughToSynthesis() {
        KnowledgeSearchFacade facade = facade(false);
        stubRetrievalHit();

        facade.searchWithSources(QUESTION, "en");

        verify(promptService).buildStructuredMessages(
                any(PromptContext.class), anyList(), anyString(), anyList(), anyBoolean(), eq("en"));
    }

    /**
     * 空检索兜底按本轮语言出：英文轮次不再收固定中文文案，未判定语言维持中文原样
     */
    @Test
    void returnsLocalizedEmptyResultNotice() {
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult(QUESTION, List.of(QUESTION)));
        when(intentResolver.resolve(any(RewriteResult.class))).thenReturn(List.of());
        when(guidanceService.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(retrievalEngine.retrieve(anyList())).thenReturn(RetrievalContext.builder().build());

        assertEquals("未在知识库中检索到与该问题相关的内容。",
                facade(false).searchWithSources(QUESTION, "zh").answer());
        assertEquals("No relevant content was found in the knowledge base for this question.",
                facade(false).searchWithSources(QUESTION, "en").answer());
        assertEquals("未在知识库中检索到与该问题相关的内容。",
                facade(false).searchWithSources(QUESTION, null).answer());
    }

    private void stubEvidenceHit() {
        NodeScore kbNode = NodeScore.builder()
                .node(IntentNode.builder().id("kb-1").build())
                .score(1.0D)
                .build();
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult(QUESTION, List.of(QUESTION)));
        when(intentResolver.resolve(any(RewriteResult.class)))
                .thenReturn(List.of(new SubQuestionIntent(QUESTION, List.of(kbNode))));
        when(intentResolver.mergeKbIntents(anyList())).thenReturn(List.of(kbNode));
        when(retrievalEngine.retrieve(anyList())).thenReturn(evidenceHit(Set.of("kb-1"), Set.of("kb-1")));
    }

    private static RetrievalContext evidenceHit(Set<String> eligibleIntentIds, Set<String> evidencedIntentIds) {
        RetrievedChunk chunk = RetrievedChunk.builder()
                .id("chunk-1").docId("doc-a").docName("差旅制度.md").chunkIndex(0).score(0.9F)
                .text("差旅报销上限 800 元").build();
        return RetrievalContext.builder()
                .contextChunks(List.of(chunk))
                .intentChunks(Map.of("kb-1", List.of(chunk)))
                .eligibleIntentIds(eligibleIntentIds)
                .evidencedIntentIds(evidencedIntentIds)
                .build();
    }

    private static NodeScore rule(String id, String name, String fullPath, String snippet) {
        return NodeScore.builder()
                .node(IntentNode.builder().id(id).name(name).fullPath(fullPath).promptSnippet(snippet).build())
                .score(1.0D)
                .build();
    }

    private void stubRetrievalHit() {
        NodeScore kbNode = NodeScore.builder()
                .node(IntentNode.builder().id("kb-1").build())
                .score(1.0D)
                .build();
        when(queryRewriteService.rewriteWithSplit(anyString(), anyList()))
                .thenReturn(new RewriteResult(QUESTION, List.of(QUESTION)));
        when(intentResolver.resolve(any(RewriteResult.class)))
                .thenReturn(List.of(new SubQuestionIntent(QUESTION, List.of(kbNode))));
        when(intentResolver.mergeKbIntents(anyList())).thenReturn(List.of(kbNode));
        when(retrievalEngine.retrieve(anyList()))
                .thenReturn(RetrievalContext.builder().kbContext(KB_CONTEXT).build());
        when(promptService.buildStructuredMessages(
                any(PromptContext.class), anyList(), anyString(), anyList(), anyBoolean(), any()))
                .thenReturn(List.of());
        when(llmService.chat(any())).thenReturn("差旅报销上限 800 元");
    }

    private PromptContext capturePromptContext() {
        ArgumentCaptor<PromptContext> captor = ArgumentCaptor.forClass(PromptContext.class);
        verify(promptService).buildStructuredMessages(
                captor.capture(), anyList(), anyString(), anyList(), anyBoolean(), any());
        return captor.getValue();
    }
}
