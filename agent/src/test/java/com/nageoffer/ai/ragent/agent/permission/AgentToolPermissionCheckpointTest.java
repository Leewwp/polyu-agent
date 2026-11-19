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
import com.nageoffer.ai.ragent.agent.dao.entity.AgentToolPermissionDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentToolPermissionMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryLearner;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.impl.AgentChatServiceImpl;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
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
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.AssistantMessage;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检查点测试（#487 验收 3，mock 模型）：flag=true 时 startRun 与 startConfirmRun
 * 两条路径均在 launchStream（agent.streamEvents）之前调
 * agent.replacePermissionContext(userId, conversationId, state)；
 * 编译输入 role 来自请求线程 UserContext（guest 用户编 guest 规则，user 规则不串门）。
 */
class AgentToolPermissionCheckpointTest {

    private static final String USER_ID = "u-guest-1";
    private static final String CONVERSATION_ID = "c-3007";

    private ReActAgent agent;
    private AgentConversationService conversationService;
    private AgentChatServiceImpl service;

    /**
     * 带 MCP 挂载的目录快照：ResolvedCatalog 经真实构造路径派生 displayNames/fieldLabels，
     * mcpToolIds() 即通配展开的目标集
     */
    static ResolvedCatalog catalogWithMcpTools(String... toolIds) {
        List<McpToolBinding> bindings = java.util.Arrays.stream(toolIds)
                .map(toolId -> McpToolBinding.of(toolId, toolId, "测试工具", false,
                        new RemoteTool(new Tool(toolId, null, "测试 MCP 工具",
                                new JsonSchema("object", Map.of(), null, null, null, null), null,
                                new ToolAnnotations(null, true, null, null, null, null), null),
                                mock(McpClientWrapper.class))))
                .toList();
        return new ResolvedCatalog("知识库工具描述", null, bindings, List.of(), List.of());
    }

    @BeforeEach
    void setUp() {
        agent = mock(ReActAgent.class);
        conversationService = mock(AgentConversationService.class);
        when(conversationService.touchConversation(anyString(), anyString(), anyString())).thenReturn("标题");
        when(conversationService.addUserMessage(anyString(), anyString(), anyString())).thenReturn("m-3003");
        when(conversationService.addAssistantMessage(anyString(), anyString(), any(), any(), any(),
                any(), any(), any())).thenReturn("m-5001");
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>just(
                new ToolResultEndEvent("r-1", "call-1", "search_exam_papers", ToolResultState.SUCCESS)));

        var agentProvider = mock(ReActAgentProvider.class);
        when(agentProvider.getAgent()).thenReturn(new ActiveAgent(
                agent, catalogWithMcpTools("search_exam_papers", "search_news")));

        var taskManager = mock(StreamTaskManager.class);
        var runGate = mock(AgentRunGate.class);
        var memoryLearner = mock(AgentMemoryLearner.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(() -> {
        });
        when(memoryLearner.learn(anyString())).thenReturn(
                AgentMemoryOutcome.of(AgentMemoryOutcome.Status.BELOW_THRESHOLD));

        // flag=true + 真实服务链（缓存+编译器），mapper 喂三角色规则验证 guest 只编 guest
        AgentToolPermissionMapper permissionMapper = mock(AgentToolPermissionMapper.class);
        when(permissionMapper.selectList(any())).thenReturn(List.of(
                AgentToolPermissionDO.builder().role("guest").toolId("mcp:*").decision("DENY").enabled(1).build(),
                AgentToolPermissionDO.builder().role("user").toolId("search_news").decision("DENY").enabled(1).build(),
                AgentToolPermissionDO.builder().role("admin").toolId("search_news").decision("ALLOW").enabled(1).build()));
        AgentProperties properties = new AgentProperties();
        properties.getToolPermission().setEnabled(true);
        AgentToolPermissionService permissionService = new AgentToolPermissionService(properties,
                new AgentToolPermissionSnapshotCache(permissionMapper));

        AgentToolCallAuditWriter auditWriter = new AgentToolCallAuditWriter(
                mock(JdbcTemplate.class), mock(AgentMessageMapper.class), new AgentProperties());
        service = new AgentChatServiceImpl(agentProvider, conversationService, taskManager, runGate,
                memoryLearner, new QuestionScopeGuard(new RAGClassifierProperties(), mock(ScopeJudgeClient.class)),
                auditWriter, permissionService);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void startRun路径_拿到agent后launchStream前装载guest规则() {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("guest-1").role("guest").build());

        service.streamChat("查往年试卷", CONVERSATION_ID, new SseEmitter());

        ArgumentCaptor<io.agentscope.core.permission.PermissionContextState> state =
                ArgumentCaptor.forClass(io.agentscope.core.permission.PermissionContextState.class);
        verify(agent).replacePermissionContext(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID), state.capture());
        // guest mcp:* 按挂载集展开；user/admin 的规则不串门
        assertThat(state.getValue().getDenyRules()).containsOnlyKeys("search_exam_papers", "search_news");
        assertThat(state.getValue().getDenyRules().get("search_news").get(0).source())
                .isEqualTo(AgentToolPermissionCompiler.RULE_SOURCE);
        // 检查点先于流启动
        InOrder order = inOrder(agent);
        order.verify(agent).replacePermissionContext(anyString(), anyString(), any());
        order.verify(agent).streamEvents(any(Msg.class), any(RuntimeContext.class));
    }

    @Test
    void startConfirmRun路径同样装载规则_先于流启动() {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("guest-1").role("guest").build());
        ToolUseBlock asking = ToolUseBlock.builder()
                .id("call-1").name("search_exam_papers")
                .input(Map.of("q", "x")).state(ToolCallState.ASKING).build();
        when(agent.getAgentState(USER_ID, CONVERSATION_ID)).thenReturn(AgentState.builder()
                .userId(USER_ID).sessionId(CONVERSATION_ID)
                .addMessage(AssistantMessage.builder().content(asking).build())
                .build());
        when(conversationService.getPendingConfirm(CONVERSATION_ID, USER_ID, "m-4004"))
                .thenReturn(new AgentConfirmSettlement("标题", "m-3003"));

        service.confirmPendingTool(CONVERSATION_ID, "m-4004", true, new SseEmitter());

        ArgumentCaptor<io.agentscope.core.permission.PermissionContextState> state =
                ArgumentCaptor.forClass(io.agentscope.core.permission.PermissionContextState.class);
        verify(agent).replacePermissionContext(org.mockito.ArgumentMatchers.eq(USER_ID),
                org.mockito.ArgumentMatchers.eq(CONVERSATION_ID), state.capture());
        assertThat(state.getValue().getDenyRules()).containsOnlyKeys("search_exam_papers", "search_news");
        InOrder order = inOrder(agent);
        order.verify(agent).replacePermissionContext(anyString(), anyString(), any());
        order.verify(agent).streamEvents(any(Msg.class), any(RuntimeContext.class));
    }
}
