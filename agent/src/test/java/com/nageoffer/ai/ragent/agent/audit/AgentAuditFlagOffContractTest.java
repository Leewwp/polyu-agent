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

package com.nageoffer.ai.ragent.agent.audit;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryLearner;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.impl.AgentChatServiceImpl;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.config.RAGClassifierProperties;
import com.nageoffer.ai.ragent.rag.service.preguard.QuestionScopeGuard;
import com.nageoffer.ai.ragent.rag.service.preguard.ScopeJudgeClient;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 关态契约（#486 验收 5）：flag=false + 完整 run（mock 模型，沿 AgentChatServiceImplTest 基建）
 * → t_agent_tool_call 零新增行（JdbcTemplate 零交互）且 digest 计算零调用（计数断言）。
 */
class AgentAuditFlagOffContractTest {

    private static final String USER_ID = "u-1001";
    private static final String CONVERSATION_ID = "c-2002";

    private ReActAgent agent;
    private JdbcTemplate jdbcTemplate;
    private AgentToolCallAuditWriter writer;
    private AgentChatServiceImpl service;

    @BeforeEach
    void setUp() {
        agent = mock(ReActAgent.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        AgentProperties properties = new AgentProperties();
        // 契约前提：默认关（不显式设置即为 false，此处显式钉住防默认值漂移）
        properties.getAudit().setEnabled(false);
        writer = new AgentToolCallAuditWriter(jdbcTemplate,
                mock(com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper.class), properties, Runnable::run);

        var agentProvider = mock(ReActAgentProvider.class);
        var conversationService = mock(AgentConversationService.class);
        var taskManager = mock(StreamTaskManager.class);
        var runGate = mock(AgentRunGate.class);
        var memoryLearner = mock(AgentMemoryLearner.class);
        when(agentProvider.getAgent()).thenReturn(new ActiveAgent(
                agent, new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of())));
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(() -> {
        });
        when(conversationService.touchConversation(anyString(), anyString(), anyString())).thenReturn("标题");
        when(conversationService.addUserMessage(anyString(), anyString(), anyString())).thenReturn("m-3003");
        when(conversationService.addAssistantMessage(anyString(), anyString(), any(), any(), any(),
                any(), any(), any())).thenReturn("m-5001");
        when(memoryLearner.learn(anyString())).thenReturn(
                com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.of(
                        com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.Status.BELOW_THRESHOLD));

        service = new AgentChatServiceImpl(agentProvider, conversationService, taskManager, runGate,
                memoryLearner, new QuestionScopeGuard(new RAGClassifierProperties(), mock(ScopeJudgeClient.class)),
                writer, mock(com.nageoffer.ai.ragent.agent.permission.AgentToolPermissionService.class));
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").role("user").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        AgentToolInputAuditCapture.configure(false, null);
    }

    /** 完整 run（含 search_knowledge 工具事件），落库与收尾走真实链路 */
    private void runChatWithToolCall() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>just(
                new ToolCallStartEvent("r-1", "call-1", "search_knowledge"),
                new ToolResultEndEvent("r-1", "call-1", "search_knowledge", ToolResultState.SUCCESS)));
        service.streamChat("图书馆开放时间", CONVERSATION_ID, new SseEmitter());
    }

    @Test
    void flagOffRunWritesZeroAuditRowsAndZeroDigestComputations() {
        AtomicInteger digestComputations = new AtomicInteger();
        // 关态下捕获点装配的是计数 digester：任何一次摘要计算都会被数出来
        AgentToolInputAuditCapture.configure(false, input -> {
            digestComputations.incrementAndGet();
            return null;
        });

        runChatWithToolCall();

        // 消息链路照常工作（工具块已落消息行），审计面零新增动作
        verify(agent).streamEvents(any(Msg.class), any(RuntimeContext.class));
        verifyNoInteractions(jdbcTemplate);
        org.assertj.core.api.Assertions.assertThat(digestComputations.get()).isZero();
    }

    @Test
    void capturePointSkipsDigestWhenDisabled() {
        // 即便工具体真的执行到捕获点（flag 关），digest 计算也不得发生
        AtomicInteger digestComputations = new AtomicInteger();
        AgentToolInputAuditCapture.configure(false, input -> {
            digestComputations.incrementAndGet();
            return null;
        });

        var facts = new com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts(
                "t-1", java.time.Clock.systemDefaultZone());
        AgentToolInputAuditCapture.capture(facts, "call-1", Map.of("query", "polyu"));

        org.assertj.core.api.Assertions.assertThat(digestComputations.get()).isZero();
        org.assertj.core.api.Assertions.assertThat(facts.toolFact("call-1").inputSummary()).isNull();
    }

    @Test
    void flagOffRebuildAlsoWritesNothing() {
        writer.rebuildMessage(CONVERSATION_ID, USER_ID, "m-5001", "user");

        verifyNoInteractions(jdbcTemplate);
    }
}
