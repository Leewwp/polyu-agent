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
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryLearner;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.impl.AgentChatServiceImpl;
import com.nageoffer.ai.ragent.agent.tool.AgentMcpClients.RemoteTool;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.tool.McpToolProxy;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.config.RAGClassifierProperties;
import com.nageoffer.ai.ragent.rag.service.preguard.QuestionScopeGuard;
import com.nageoffer.ai.ragent.rag.service.preguard.ScopeJudgeClient;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ASK 收紧特性（#487 验收 7）：needsConfirm=false 的 MCP 工具配 ASK → 产生确认。
 * 两级断言：
 * ①引擎级——真实 PermissionEngine + 真实 McpToolProxy（编译产物驱动框架原生的判定序：
 *   deny 短路→ask→per-tool→allow→兜底），DENY/ASK/ALLOW 三态与判定序全部实测；
 * ②服务链级——needsConfirm=false 工具配 ASK 后，run 以 RequireUserConfirmEvent 挂起
 *   → 确认卡（kind=confirm）+ 工具块 awaiting（新增确认=声明式矩阵可收紧的特性，票面语义）。
 */
class AgentToolPermissionAskTest {

    private static final String USER_ID = "u-2002";
    private static final String CONVERSATION_ID = "c-5003";
    private static final String TOOL_ID = "query_order";

    /**
     * needsConfirm 由 McpToolBinding.of 推导：confirmConfigured=false + readOnlyHint=true → false
     */
    private static McpToolProxy needsConfirmFalseTool() {
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("default");
        Tool definition = new Tool(TOOL_ID, null, "查询订单",
                new JsonSchema("object", Map.of(), null, null, null, null), null,
                new ToolAnnotations(null, true, null, null, null, null), null);
        return new McpToolProxy(McpToolBinding.of(TOOL_ID, "订单查询", "查询订单", false,
                new RemoteTool(definition, client)));
    }

    /**
     * needsConfirm=true（readOnlyHint 未声明 → confirmConfigured||!readOnly=true）
     */
    private static McpToolProxy needsConfirmTrueTool(String toolId) {
        McpClientWrapper client = mock(McpClientWrapper.class);
        when(client.getName()).thenReturn("default");
        Tool definition = new Tool(toolId, null, "写操作",
                new JsonSchema("object", Map.of(), null, null, null, null), null, null, null);
        return new McpToolProxy(McpToolBinding.of(toolId, "写操作", "写操作", false,
                new RemoteTool(definition, client)));
    }

    private static PermissionEngine engineOver(String decision) {
        PermissionContextState state = AgentToolPermissionCompiler.compile("user",
                List.of(new AgentToolPermissionEntry("user", TOOL_ID, decision)), List.of(TOOL_ID));
        return new PermissionEngine(state);
    }

    @AfterEach
    void clearUserContext() {
        // UserContext 是 ThreadLocal：服务链级用例往里塞 LoginUser，统一收尾防泄漏到同线程后续用例
        UserContext.clear();
    }

    @Test
    void 引擎级_needsConfirmFalse工具配ASK_引擎判ASK_新增确认() {
        PermissionDecision decision = engineOver("ASK")
                .checkPermission(needsConfirmFalseTool(), Map.of()).block();
        assertThat(decision).isNotNull();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ASK);
    }

    @Test
    void 引擎级_无规则与ALLOW规则时needsConfirmFalse均放行_现状逐位相同() {
        // 现状语义回归：无规则 → per-tool allow（"无需执行前确认"）
        PermissionDecision noRule = new PermissionEngine(
                        AgentToolPermissionCompiler.compile("user", List.of(), List.of(TOOL_ID)))
                .checkPermission(needsConfirmFalseTool(), Map.of()).block();
        assertThat(noRule.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
        // 显式 ALLOW 规则同样放行
        PermissionDecision allowRule = engineOver("ALLOW")
                .checkPermission(needsConfirmFalseTool(), Map.of()).block();
        assertThat(allowRule.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void 引擎级_DENY压过perTool放行_资格先于批准() {
        PermissionDecision decision = engineOver("DENY")
                .checkPermission(needsConfirmFalseTool(), Map.of()).block();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.DENY);
    }

    @Test
    void 引擎级_ALLOW规则可压过needsConfirmTrue确认卡_矩阵可放宽() {
        // 判定序实证：per-tool 非 safety ASK 落空 → allowRules 命中 → ALLOW
        PermissionContextState state = AgentToolPermissionCompiler.compile("user",
                List.of(new AgentToolPermissionEntry("user", "submit_form", "ALLOW")),
                List.of("submit_form"));
        PermissionDecision decision = new PermissionEngine(state)
                .checkPermission(needsConfirmTrueTool("submit_form"), Map.of()).block();
        assertThat(decision.getBehavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 服务链级_needsConfirmFalse工具配ASK_产生确认卡挂起run() {
        ReActAgent agent = mock(ReActAgent.class);
        AgentConversationService conversationService = mock(AgentConversationService.class);
        when(conversationService.touchConversation(anyString(), anyString(), anyString())).thenReturn("标题");
        when(conversationService.addUserMessage(anyString(), anyString(), anyString())).thenReturn("m-3003");
        when(conversationService.addAssistantMessage(anyString(), anyString(), any(), any(), any(),
                any(), any(), any())).thenReturn("m-5001");
        var agentProvider = mock(ReActAgentProvider.class);
        when(agentProvider.getAgent()).thenReturn(new ActiveAgent(
                agent, AgentToolPermissionCheckpointTest.catalogWithMcpTools("query_order")));
        var taskManager = mock(StreamTaskManager.class);
        var runGate = mock(AgentRunGate.class);
        var memoryLearner = mock(AgentMemoryLearner.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(() -> {
        });
        when(memoryLearner.learn(anyString())).thenReturn(
                AgentMemoryOutcome.of(AgentMemoryOutcome.Status.BELOW_THRESHOLD));

        AgentToolPermissionMapper permissionMapper = mock(AgentToolPermissionMapper.class);
        when(permissionMapper.selectList(any())).thenReturn(List.of(
                AgentToolPermissionDO.builder()
                        .role("user").toolId("query_order").decision("ASK").enabled(1).build()));
        AgentProperties properties = new AgentProperties();
        properties.getToolPermission().setEnabled(true);
        AgentToolPermissionService permissionService = new AgentToolPermissionService(properties,
                new AgentToolPermissionSnapshotCache(permissionMapper));
        AgentToolCallAuditWriter auditWriter = new AgentToolCallAuditWriter(
                mock(JdbcTemplate.class), mock(AgentMessageMapper.class), new AgentProperties());
        AgentChatServiceImpl service = new AgentChatServiceImpl(agentProvider, conversationService,
                taskManager, runGate, memoryLearner,
                new QuestionScopeGuard(new RAGClassifierProperties(), mock(ScopeJudgeClient.class)),
                auditWriter, permissionService);
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").role("user").build());

        // needsConfirm=false 工具配 ASK 后的框架行为：PermissionGate 判 ASK → RequireUserConfirmEvent 挂起
        ToolUseBlock asking = ToolUseBlock.builder()
                .id("call-1").name("query_order").input(Map.of("order", "A1"))
                .state(ToolCallState.ASKING).build();
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>just(
                new ToolCallStartEvent("r-1", "call-1", "query_order"),
                new RequireUserConfirmEvent("r-1", List.of(asking))));

        service.streamChat("查订单", CONVERSATION_ID, new SseEmitter());

        ArgumentCaptor<List<AgentBlock>> persistedBlocks = ArgumentCaptor.forClass((Class) List.class);
        verify(conversationService).addAssistantMessage(anyString(), anyString(), any(), any(),
                persistedBlocks.capture(), any(), any(), any());
        List<AgentBlock> blocks = persistedBlocks.getValue();
        // 确认卡产生：kind=confirm 块 + 同批工具块 awaiting
        assertThat(blocks.stream().anyMatch(b -> AgentBlock.KIND_CONFIRM.equals(b.getKind()))).isTrue();
        AgentBlock toolBlock = blocks.stream()
                .filter(b -> AgentBlock.KIND_TOOL.equals(b.getKind()) && "query_order".equals(b.getName()))
                .findFirst().orElseThrow();
        assertThat(toolBlock.getStatus()).isEqualTo("awaiting");
    }
}
