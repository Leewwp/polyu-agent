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

package com.nageoffer.ai.ragent.agent.tool;

import com.nageoffer.ai.ragent.agent.dto.AgentBlockSource;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeSearchToolTest {

    @AfterEach
    void cleanStash() {
        AgentToolSourceStash.reset();
    }

    @Test
    void shouldExposeConfiguredDescriptionAndDelegateSearch() {
        KnowledgeSearchFacade knowledgeSearchFacade = mock(KnowledgeSearchFacade.class);
        when(knowledgeSearchFacade.searchWithSources("需要哪些材料"))
                .thenReturn(new KnowledgeSearchFacade.KnowledgeSearchOutcome(
                        "需要发票和审批单", List.of()));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                "检索当前 Agent 的企业知识库", knowledgeSearchFacade);
        ToolCallParam param = ToolCallParam.builder()
                .input(Map.of("query", " 需要哪些材料 "))
                .runtimeContext(RuntimeContext.builder()
                        .sessionId("conversation-1")
                        .userId("user-1")
                        .build())
                .build();

        ToolResultBlock result = tool.callAsync(param).block();

        assertThat(tool.getName()).isEqualTo(KnowledgeSearchTool.TOOL_NAME);
        assertThat(tool.getDescription()).isEqualTo("检索当前 Agent 的企业知识库");
        assertThat(tool.getParameters()).containsEntry("required", List.of("query"));
        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).isEqualTo("需要发票和审批单");
        verify(knowledgeSearchFacade).searchWithSources("需要哪些材料");
    }

    /**
     * docId 不进模型上下文：来源只进旁路 stash，返回值只有答案文本
     */
    @Test
    void shouldStashSourcesBesideTextOnlyAnswer() {
        KnowledgeSearchFacade knowledgeSearchFacade = mock(KnowledgeSearchFacade.class);
        List<KnowledgeSearchFacade.KnowledgeSearchSource> sources = List.of(
                new KnowledgeSearchFacade.KnowledgeSearchSource(
                        "doc-42", "图书馆服务指南", "游泳池开放时间为早七至晚十…", "url",
                        "https://www.polyu.edu.hk/library/hours/"));
        when(knowledgeSearchFacade.searchWithSources(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new KnowledgeSearchFacade.KnowledgeSearchOutcome("开放时间是早七至晚十", sources));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                "检索企业知识库", knowledgeSearchFacade);
        ToolCallParam param = ToolCallParam.builder()
                .input(Map.of("query", "游泳池什么时间开放"))
                .toolUseBlock(io.agentscope.core.message.ToolUseBlock.builder()
                        .id("call-42").name(KnowledgeSearchTool.TOOL_NAME).build())
                .build();

        ToolResultBlock result = tool.callAsync(param).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        String answer = ((TextBlock) result.getOutput().get(0)).getText();
        assertThat(answer).isEqualTo("开放时间是早七至晚十");
        assertThat(answer).doesNotContain("doc-42");
        List<AgentBlockSource> stashed = AgentToolSourceStash.take("call-42");
        assertThat(stashed).hasSize(1);
        assertThat(stashed.get(0).getDocId()).isEqualTo("doc-42");
        assertThat(stashed.get(0).getDocName()).isEqualTo("图书馆服务指南");
    }

    /**
     * 空来源不留 stash 痕迹：取不到就是没有，不给桥一个空列表去挂块
     */
    @Test
    void shouldNotStashWhenSourcesEmpty() {
        KnowledgeSearchFacade knowledgeSearchFacade = mock(KnowledgeSearchFacade.class);
        when(knowledgeSearchFacade.searchWithSources(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new KnowledgeSearchFacade.KnowledgeSearchOutcome("答案", List.of()));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                "检索企业知识库", knowledgeSearchFacade);

        tool.callAsync(ToolCallParam.builder()
                        .input(Map.of("query", "你好"))
                        .toolUseBlock(io.agentscope.core.message.ToolUseBlock.builder()
                                .id("call-1").name(KnowledgeSearchTool.TOOL_NAME).build())
                        .build())
                .block();

        assertThat(AgentToolSourceStash.take("call-1")).isNull();
    }

    @Test
    void shouldRejectBlankQueryWithoutSearching() {
        KnowledgeSearchFacade knowledgeSearchFacade = mock(KnowledgeSearchFacade.class);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                "检索企业知识库", knowledgeSearchFacade);

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                        .input(Map.of("query", " "))
                        .build())
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("query 不能为空");
        verify(knowledgeSearchFacade, never())
                .searchWithSources(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldRejectMissingInputWithoutSearching() {
        KnowledgeSearchFacade knowledgeSearchFacade = mock(KnowledgeSearchFacade.class);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                "检索企业知识库", knowledgeSearchFacade);

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder().build())
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("query 不能为空");
        verify(knowledgeSearchFacade, never()).searchWithSources(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldReturnGenericErrorWithoutLeakingExceptionDetails() {
        KnowledgeSearchFacade knowledgeSearchFacade = mock(KnowledgeSearchFacade.class);
        when(knowledgeSearchFacade.searchWithSources("报销规则"))
                .thenThrow(new IllegalStateException("jdbc:postgresql://internal-host/ragent?password=secret"));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                "检索企业知识库", knowledgeSearchFacade);

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                        .input(Map.of("query", "报销规则"))
                        .build())
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(((TextBlock) result.getOutput().get(0)).getText())
                .isEqualTo("知识库检索异常，请稍后重试")
                .doesNotContain("internal-host", "secret");
    }

    @Test
    void shouldReturnInterruptedWhenSearchIsCancelled() {
        KnowledgeSearchFacade knowledgeSearchFacade = mock(KnowledgeSearchFacade.class);
        when(knowledgeSearchFacade.searchWithSources("报销规则"))
                .thenThrow(new CancellationException("任务已取消"));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                "检索企业知识库", knowledgeSearchFacade);

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                        .input(Map.of("query", "报销规则"))
                        .build())
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.INTERRUPTED);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("用户已停止");
    }

    @Test
    void shouldReturnInterruptedWhenSearchDegradesInsteadOfThrowing() {
        KnowledgeSearchFacade knowledgeSearchFacade = mock(KnowledgeSearchFacade.class);
        // RAG 逐层降级，取消时拿到的往往不是异常而是一份空结果，只在 catch 里判会漏掉
        when(knowledgeSearchFacade.searchWithSources("报销规则")).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return new KnowledgeSearchFacade.KnowledgeSearchOutcome("", List.of());
        });
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                "检索企业知识库", knowledgeSearchFacade);

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                        .input(Map.of("query", "报销规则"))
                        .build())
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.INTERRUPTED);
        assertThat(((TextBlock) result.getOutput().get(0)).getText()).contains("用户已停止");
    }

    @Test
    void shouldKeepInterruptedStateThroughToolkitBoundary() {
        KnowledgeSearchFacade knowledgeSearchFacade = mock(KnowledgeSearchFacade.class);
        when(knowledgeSearchFacade.searchWithSources("报销规则"))
                .thenThrow(new CancellationException("任务已取消"));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                "检索企业知识库", knowledgeSearchFacade);
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tool);

        List<ToolResultBlock> results = toolkit.callTools(List.of(
                        ToolUseBlock.builder()
                                .id("call-1")
                                .name(KnowledgeSearchTool.TOOL_NAME)
                                .input(Map.of("query", "报销规则"))
                                // 框架按 content 里的原始 JSON 做 schema 校验，只给 input 会被判参数非法而进不了工具体
                                .content("{\"query\":\"报销规则\"}")
                                .build()), null, null, RuntimeContext.empty())
                .block();

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.getState()).isEqualTo(ToolResultState.INTERRUPTED);
            assertThat(((TextBlock) result.getOutput().get(0)).getText())
                    .contains("用户已停止")
                    .doesNotContain("Tool execution failed");
        });
    }

    @Test
    void shouldTreatBlankFacadeResultAsError() {
        KnowledgeSearchFacade knowledgeSearchFacade = mock(KnowledgeSearchFacade.class);
        when(knowledgeSearchFacade.searchWithSources("报销规则"))
                .thenReturn(new KnowledgeSearchFacade.KnowledgeSearchOutcome(" ", List.of()));
        KnowledgeSearchTool tool = new KnowledgeSearchTool(
                "检索企业知识库", knowledgeSearchFacade);

        ToolResultBlock result = tool.callAsync(ToolCallParam.builder()
                        .input(Map.of("query", "报销规则"))
                        .build())
                .block();

        assertThat(result).isNotNull();
        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(((TextBlock) result.getOutput().get(0)).getText())
                .isEqualTo("知识库检索异常，请稍后重试");
    }

}
