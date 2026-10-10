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

package com.nageoffer.ai.ragent.rag.service.pipeline;

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.ragent.rag.core.source.GroundingChunksAssembler;
import com.nageoffer.ai.ragent.rag.core.source.SourcesAssembler;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StreamChatPipelineTest {

    @Mock
    private ConversationMemoryService memoryService;
    @Mock
    private QueryRewriteService queryRewriteService;
    @Mock
    private IntentResolver intentResolver;
    @Mock
    private IntentGuidanceService guidanceService;
    @Mock
    private RetrievalEngine retrievalEngine;
    @Mock
    private LLMService llmService;
    @Mock
    private RAGPromptService promptBuilder;
    @Mock
    private AgentPromptResolver agentPromptResolver;
    @Mock
    private StreamTaskManager taskManager;
    @Mock
    private SourcesAssembler sourcesAssembler;
    @Mock
    private GroundingChunksAssembler groundingChunksAssembler;
    @Mock
    private CitationContextEnricher citationContextEnricher;

    @InjectMocks
    private StreamChatPipeline pipeline;

    @Test
    void passesEligibleIntentIdsToPromptContext() {
        StreamCallback callback = mock(StreamCallback.class);
        RewriteResult rewriteResult = new RewriteResult("改写问题", List.of("改写问题"));
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("改写问题", List.of()));
        Set<String> eligibleIntentIds = Set.of("intent-1");
        RetrievalContext retrievalContext = RetrievalContext.builder()
                .kbContext("<content>资料</content>")
                .intentChunks(Map.of())
                .eligibleIntentIds(eligibleIntentIds)
                .build();

        when(memoryService.load("conversation-1", "user-1")).thenReturn(List.of());
        when(memoryService.append(any(), any(), any())).thenReturn("message-1");
        when(queryRewriteService.rewriteWithSplit("原问题", List.of())).thenReturn(rewriteResult);
        when(intentResolver.resolve(rewriteResult)).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity("改写问题", subIntents)).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(subIntents)).thenReturn(retrievalContext);
        when(intentResolver.mergeKbIntents(subIntents)).thenReturn(List.of());
        when(citationContextEnricher.enrich("<content>资料</content>", List.of()))
                .thenReturn("<content>资料</content>");
        when(promptBuilder.buildStructuredMessages(any(), anyList(), any(), anyList(), eq(true), any()))
                .thenReturn(List.of());

        pipeline.execute(StreamChatContext.builder()
                .question("原问题")
                .conversationId("conversation-1")
                .taskId("task-1")
                .userId("user-1")
                .callback(callback)
                .build());

        ArgumentCaptor<PromptContext> promptContext = ArgumentCaptor.forClass(PromptContext.class);
        verify(promptBuilder).buildStructuredMessages(promptContext.capture(), anyList(), any(), anyList(), eq(true), any());
        assertEquals(eligibleIntentIds, promptContext.getValue().getEligibleIntentIds());
    }

    /**
     * #370：语言只对原始提问判定一次——英文原始提问即使被改写成中文问题，透传的也必须是 "en"
     */
    @Test
    void detectsLanguageFromRawQuestionNotRewrittenQuery() {
        StreamCallback callback = mock(StreamCallback.class);
        RewriteResult rewriteResult = new RewriteResult("图书馆几点开门", List.of("图书馆几点开门"));
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("图书馆几点开门", List.of()));
        RetrievalContext retrievalContext = RetrievalContext.builder()
                .kbContext("<content>资料</content>")
                .intentChunks(Map.of())
                .eligibleIntentIds(Set.of("intent-1"))
                .build();

        when(memoryService.load("conversation-1", "user-1")).thenReturn(List.of());
        when(memoryService.append(any(), any(), any())).thenReturn("message-1");
        when(queryRewriteService.rewriteWithSplit("What time does the library open?", List.of()))
                .thenReturn(rewriteResult);
        when(intentResolver.resolve(rewriteResult)).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity("图书馆几点开门", subIntents)).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(subIntents)).thenReturn(retrievalContext);
        when(intentResolver.mergeKbIntents(subIntents)).thenReturn(List.of());
        when(citationContextEnricher.enrich("<content>资料</content>", List.of()))
                .thenReturn("<content>资料</content>");
        when(promptBuilder.buildStructuredMessages(any(), anyList(), any(), anyList(), eq(true), any()))
                .thenReturn(List.of());

        pipeline.execute(StreamChatContext.builder()
                .question("What time does the library open?")
                .conversationId("conversation-1")
                .taskId("task-1")
                .userId("user-1")
                .callback(callback)
                .build());

        verify(promptBuilder).buildStructuredMessages(any(), anyList(), eq("图书馆几点开门"), anyList(), eq(true), eq("en"));
    }

    /**
     * #370：判不出语言（纯编号）时不施加语言约束，6 参重载收 null
     */
    @Test
    void passesNullLanguageWhenRawQuestionUndeterminable() {
        StreamCallback callback = mock(StreamCallback.class);
        RewriteResult rewriteResult = new RewriteResult("改写问题", List.of("改写问题"));
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent("改写问题", List.of()));
        RetrievalContext retrievalContext = RetrievalContext.builder()
                .kbContext("<content>资料</content>")
                .intentChunks(Map.of())
                .eligibleIntentIds(Set.of())
                .build();

        when(memoryService.load("conversation-1", "user-1")).thenReturn(List.of());
        when(memoryService.append(any(), any(), any())).thenReturn("message-1");
        when(queryRewriteService.rewriteWithSplit("12345", List.of())).thenReturn(rewriteResult);
        when(intentResolver.resolve(rewriteResult)).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity("改写问题", subIntents)).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(subIntents)).thenReturn(retrievalContext);
        when(intentResolver.mergeKbIntents(subIntents)).thenReturn(List.of());
        when(citationContextEnricher.enrich("<content>资料</content>", List.of()))
                .thenReturn("<content>资料</content>");
        when(promptBuilder.buildStructuredMessages(any(), anyList(), any(), anyList(), eq(true), any()))
                .thenReturn(List.of());

        pipeline.execute(StreamChatContext.builder()
                .question("12345")
                .conversationId("conversation-1")
                .taskId("task-1")
                .userId("user-1")
                .callback(callback)
                .build());

        verify(promptBuilder).buildStructuredMessages(any(), anyList(), any(), anyList(), eq(true), eq((String) null));
    }

    /**
     * #370 兜底归一：零检索文案用入口共享判定——中文提问出中文边界，判不出维持默认英文边界
     */
    @Test
    void emptyRetrievalFallbackFollowsSharedDetection() {
        verifyFallbackLanguage("图书馆几点开门", "未检索到与该问题相关的 PolyU 官方资料");
        verifyFallbackLanguage("What time does the library open?", "No relevant PolyU material was found");
        verifyFallbackLanguage("12345", "No relevant PolyU material was found");
    }

    /**
     * 归一后的边界披露：英文为主夹中文专名的混语提问判 en，兜底出英文边界
     * （旧实现按「任意汉字」判中文，此类提问过去出中文兜底）
     */
    @Test
    void emptyRetrievalFallbackUsesMajorityLanguageOnMixedQuestion() {
        verifyFallbackLanguage("How do I book 研讨室?", "No relevant PolyU material was found");
    }

    private void verifyFallbackLanguage(String question, String expectedTextPart) {
        StreamCallback callback = mock(StreamCallback.class);
        RewriteResult rewriteResult = new RewriteResult(question, List.of(question));
        List<SubQuestionIntent> subIntents = List.of(new SubQuestionIntent(question, List.of()));
        RetrievalContext retrievalContext = RetrievalContext.builder()
                .kbContext("")
                .intentChunks(Map.of())
                .build();

        when(memoryService.load("conversation-1", "user-1")).thenReturn(List.of());
        when(memoryService.append(any(), any(), any())).thenReturn("message-1");
        when(queryRewriteService.rewriteWithSplit(question, List.of())).thenReturn(rewriteResult);
        when(intentResolver.resolve(rewriteResult)).thenReturn(subIntents);
        when(guidanceService.detectAmbiguity(question, subIntents)).thenReturn(GuidanceDecision.none());
        when(intentResolver.isSystemOnly(anyList())).thenReturn(false);
        when(retrievalEngine.retrieve(subIntents)).thenReturn(retrievalContext);

        pipeline.execute(StreamChatContext.builder()
                .question(question)
                .conversationId("conversation-1")
                .taskId("task-1")
                .userId("user-1")
                .callback(callback)
                .build());

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(callback).onContent(content.capture());
        verify(callback).onComplete();
        assertTrue(content.getValue().startsWith(expectedTextPart),
                "兜底文案应以下列开头：" + expectedTextPart + "，实际：" + content.getValue());
    }
}
