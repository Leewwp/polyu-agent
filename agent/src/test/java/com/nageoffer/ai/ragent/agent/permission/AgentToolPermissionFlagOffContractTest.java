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

package com.nageoffer.ai.ragent.agent.permission;

import com.nageoffer.ai.ragent.agent.audit.AgentToolCallAuditWriter;
import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentToolPermissionMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryLearner;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
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
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 关态契约（#487 验收 4）：flag=false（默认）→
 * ①replacePermissionContext 零调用（连空编译都不做）；
 * ②MCP 工具行为与现状逐位相同（needsConfirm 原逻辑：无需确认直接执行，needsConfirm=true 走确认卡）；
 * ③未被装载规则时 state.getPermissionContext()==null（检查点从未触碰会话状态），
 * 空编译产物 isTrivial（引擎三 map 空→判定退化为 per-tool 层=现状）。
 */
class AgentToolPermissionFlagOffContractTest {

    private static final String USER_ID = "u-1001";
    private static final String CONVERSATION_ID = "c-2002";

    private ReActAgent agent;
    private AgentToolPermissionMapper permissionMapper;
    private AgentConversationService conversationService;
    private AgentChatServiceImpl service;

    @BeforeEach
    void setUp() {
        agent = mock(ReActAgent.class);
        conversationService = mock(AgentConversationService.class);
        when(conversationService.touchConversation(anyString(), anyString(), anyString())).thenReturn("标题");
        when(conversationService.addUserMessage(anyString(), anyString(), anyString())).thenReturn("m-3003");
        when(conversationService.addAssistantMessage(anyString(), anyString(), any(), any(), any(),
                any(), any(), any())).thenReturn("m-5001");

        var agentProvider = mock(ReActAgentProvider.class);
        // 目录快照挂一个 MCP 工具：关态下其行为应与现状逐位相同
        when(agentProvider.getAgent()).thenReturn(new ActiveAgent(
                agent, AgentToolPermissionCheckpointTest.catalogWithMcpTools("search_exam_papers")));

        var taskManager = mock(StreamTaskManager.class);
        var runGate = mock(AgentRunGate.class);
        var memoryLearner = mock(AgentMemoryLearner.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(() -> {
        });
        when(memoryLearner.learn(anyString())).thenReturn(
                AgentMemoryOutcome.of(AgentMemoryOutcome.Status.BELOW_THRESHOLD));

        // 真实服务链（非 mock）：flag 关态由真实门控短路——契约锁定的是门控行为本身
        permissionMapper = mock(AgentToolPermissionMapper.class);
        AgentProperties properties = new AgentProperties();
        properties.getToolPermission().setEnabled(false);
        AgentToolPermissionService permissionService = new AgentToolPermissionService(properties,
                new AgentToolPermissionSnapshotCache(permissionMapper));

        AgentToolCallAuditWriter auditWriter = new AgentToolCallAuditWriter(
                mock(JdbcTemplate.class), mock(AgentMessageMapper.class), new AgentProperties());
        service = new AgentChatServiceImpl(agentProvider, conversationService, taskManager, runGate,
                memoryLearner, new QuestionScopeGuard(new RAGClassifierProperties(), mock(ScopeJudgeClient.class)),
                auditWriter, permissionService);
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").role("user").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void flagOffZeroReplacePermissionContextCallsAndZeroSnapshotLoads() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>just(
                new ToolCallStartEvent("r-1", "call-1", "search_exam_papers"),
                new ToolResultEndEvent("r-1", "call-1", "search_exam_papers", ToolResultState.SUCCESS)));

        service.streamChat("查往年试卷", CONVERSATION_ID, new SseEmitter());

        // ①连空编译都不做：装载零调用
        verify(agent, never()).replacePermissionContext(anyString(), anyString(), any());
        // 关态零额外面：快照缓存零触碰（连首次加载都不发生）
        verifyNoInteractions(permissionMapper);
    }

    @Test
    @SuppressWarnings("unchecked")
    void flagOffMcpToolBehaviorBitwiseSameAsCurrent_needsConfirmFalseRunsThrough() {
        // 现状语义（McpToolProxy.checkPermissions）：needsConfirm=false → allow → 直接执行 done
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>just(
                new ToolCallStartEvent("r-1", "call-1", "search_exam_papers"),
                new ToolResultEndEvent("r-1", "call-1", "search_exam_papers", ToolResultState.SUCCESS)));

        service.streamChat("查往年试卷", CONVERSATION_ID, new SseEmitter());

        // 工具块照常 done：块产物与引入权限门控前的既有断言口径逐位相同（沿 T1 集成测试基线）
        ArgumentCaptor<List<AgentBlock>> persistedBlocks = ArgumentCaptor.forClass((Class) List.class);
        verify(conversationService).addAssistantMessage(anyString(), anyString(), any(), any(),
                persistedBlocks.capture(), any(), any(), any());
        AgentBlock toolBlock = persistedBlocks.getValue().stream()
                .filter(b -> AgentBlock.KIND_TOOL.equals(b.getKind()))
                .findFirst().orElseThrow();
        assertThat(toolBlock.getName()).isEqualTo("search_exam_papers");
        assertThat(toolBlock.getStatus()).isEqualTo("done");
    }

    @Test
    void 未装载规则的会话状态权限上下文为空或trivial_空编译产物为trivial() {
        // ③检查点从未被调用的会话：框架 freshState 的 permissionContext 为 null 或 trivial
        // （2.0.2 实测：builder 默认装 DEFAULT 空 map 态——票面允许的两种形态之一，引擎三 map 空）
        AgentState fresh = AgentState.builder().userId(USER_ID).sessionId(CONVERSATION_ID).build();
        assertThat(fresh.getPermissionContext() == null || fresh.getPermissionContext().isTrivial())
                .as("关态会话权限上下文必须为 null 或 trivial（三 map 空）")
                .isTrue();
        // 空规则编译产物（若 flag 开且无规则）：三 map 空 + isTrivial —— 等价于现状判定链
        PermissionContextState empty = PermissionContextState.builder().mode(PermissionMode.DEFAULT).build();
        assertThat(empty.isTrivial()).isTrue();
        assertThat(empty.getDenyRules()).isEmpty();
        assertThat(empty.getAskRules()).isEmpty();
        assertThat(empty.getAllowRules()).isEmpty();
    }
}
