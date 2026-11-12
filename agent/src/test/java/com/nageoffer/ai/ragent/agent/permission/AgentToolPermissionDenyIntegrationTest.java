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
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryLearner;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.impl.AgentChatServiceImpl;
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
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * deny 行为+审计集成（#487 验收 5/6，mock 模型）：flag=true + 表配 role=user
 * DENY 某 MCP 工具 → 框架 writeAutoDeniedResults 形态的事件流（delta
 * "Permission denied by rules" + ToolResultEndEvent(DENIED)，2.0.2 字节码实证）
 * → ①tool 块 status=denied 且 result 为框架文案 ②经 T1 写入路径同表落行
 * decision=RULE_DENY ③run 正常收口不中断 ④未配规则的工具行为不变。
 * 文案判别（验收 6）：同一链路跑用户拒卡文案 "Permission denied by user" → ASK_DENIED。
 */
class AgentToolPermissionDenyIntegrationTest {

    private static final String USER_ID = "u-2001";
    private static final String CONVERSATION_ID = "c-5002";

    private ReActAgent agent;
    private AgentConversationService conversationService;
    private JdbcTemplate jdbcTemplate;
    private AgentChatServiceImpl service;

    @BeforeEach
    void setUp() {
        agent = mock(ReActAgent.class);
        conversationService = mock(AgentConversationService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        when(conversationService.touchConversation(anyString(), anyString(), anyString())).thenReturn("标题");
        when(conversationService.addUserMessage(anyString(), anyString(), anyString())).thenReturn("m-3003");
        when(conversationService.addAssistantMessage(anyString(), anyString(), any(), any(), any(),
                any(), any(), any())).thenReturn("m-5001");

        var agentProvider = mock(ReActAgentProvider.class);
        when(agentProvider.getAgent()).thenReturn(new ActiveAgent(
                agent, AgentToolPermissionCheckpointTest.catalogWithMcpTools("search_exam_papers", "search_news")));

        var taskManager = mock(StreamTaskManager.class);
        var runGate = mock(AgentRunGate.class);
        var memoryLearner = mock(AgentMemoryLearner.class);
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(() -> {
        });
        when(memoryLearner.learn(anyString())).thenReturn(
                AgentMemoryOutcome.of(AgentMemoryOutcome.Status.BELOW_THRESHOLD));

        // flag=true + role=user DENY search_exam_papers（未配 search_news）
        AgentToolPermissionMapper permissionMapper = mock(AgentToolPermissionMapper.class);
        when(permissionMapper.selectList(any())).thenReturn(List.of(
                AgentToolPermissionDO.builder()
                        .role("user").toolId("search_exam_papers").decision("DENY").enabled(1).build()));
        AgentProperties permissionProperties = new AgentProperties();
        permissionProperties.getToolPermission().setEnabled(true);
        AgentToolPermissionService permissionService = new AgentToolPermissionService(permissionProperties,
                new AgentToolPermissionSnapshotCache(permissionMapper));

        // 审计开（T1 写入路径）：公共构造器走默认旁路线程，断言用 Mockito timeout 等待异步落行
        AgentProperties auditProperties = new AgentProperties();
        auditProperties.getAudit().setEnabled(true);
        AgentToolCallAuditWriter auditWriter = new AgentToolCallAuditWriter(
                jdbcTemplate, mock(AgentMessageMapper.class), auditProperties);

        service = new AgentChatServiceImpl(agentProvider, conversationService, taskManager, runGate,
                memoryLearner, new QuestionScopeGuard(new RAGClassifierProperties(), mock(ScopeJudgeClient.class)),
                auditWriter, permissionService);
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").role("user").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /**
     * 框架 rule-deny 的可观测事件形态：工具有开头事件，结果以框架固定文案 delta + DENIED 终态流出
     */
    private static Flux<AgentEvent> denyThenUntouchedTool() {
        return Flux.<AgentEvent>just(
                new ToolCallStartEvent("r-1", "call-1", "search_exam_papers"),
                new ToolResultTextDeltaEvent("r-1", "call-1", "search_exam_papers",
                        AgentToolCallDecisionText.RULE_DENIAL),
                new ToolResultEndEvent("r-1", "call-1", "search_exam_papers", ToolResultState.DENIED),
                new ToolCallStartEvent("r-1", "call-2", "search_news"),
                new ToolResultEndEvent("r-1", "call-2", "search_news", ToolResultState.SUCCESS));
    }

    /** 拒绝文案常量单点（与 T1 AgentToolCallDecision.RULE_DENIAL_TEXT 同值，此处显式字面量防误改写传染） */
    private static final class AgentToolCallDecisionText {
        static final String RULE_DENIAL = "Permission denied by rules";
        static final String USER_DENIAL = "Permission denied by user";
    }

    @Test
    @SuppressWarnings("unchecked")
    void ruleDeny落denied块框架文案_审计RULE_DENY_未配规则工具行为不变_且run正常收口() {
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(denyThenUntouchedTool());

        service.streamChat("查试卷和资讯", CONVERSATION_ID, new SseEmitter());

        // ③run 正常收口不中断：assistant 消息以 NORMAL 落库
        ArgumentCaptor<AgentMessageStatus> status = ArgumentCaptor.forClass(AgentMessageStatus.class);
        verify(conversationService, atLeastOnce()).addAssistantMessage(anyString(), anyString(), any(),
                any(), any(), any(), status.capture(), any());
        assertThat(status.getValue()).isEqualTo(AgentMessageStatus.NORMAL);

        // ①④块产物：被拒工具 denied+框架文案；未配规则工具照常 done
        ArgumentCaptor<List<AgentBlock>> persistedBlocks = ArgumentCaptor.forClass((Class) List.class);
        verify(conversationService, atLeastOnce()).addAssistantMessage(anyString(), anyString(), any(), any(),
                persistedBlocks.capture(), any(), any(), any());
        AgentBlock denied = blockOf(persistedBlocks.getValue(), "search_exam_papers");
        assertThat(denied.getStatus()).isEqualTo("denied");
        assertThat(denied.getResult()).isEqualTo(AgentToolCallDecisionText.RULE_DENIAL);
        AgentBlock untouched = blockOf(persistedBlocks.getValue(), "search_news");
        assertThat(untouched.getStatus()).isEqualTo("done");

        // ②经 T1 写入路径同表落行：RULE_DENY 与 ALLOW（未配规则工具）两行
        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass((Class) List.class);
        verify(jdbcTemplate, timeout(3000)).batchUpdate(anyString(), batch.capture());
        assertThat(batch.getValue()).hasSize(2);
        assertThat(decisionRowOf(batch.getValue(), "search_exam_papers")).isEqualTo("RULE_DENY");
        assertThat(decisionRowOf(batch.getValue(), "search_news")).isEqualTo("ALLOW");
    }

    @Test
    @SuppressWarnings("unchecked")
    void 文案判别_用户拒卡路径落ASK_DENIED_与RULE_DENY可区分() {
        // 用户拒卡（确认后拒绝）的框架形态：delta 文案 "Permission denied by user" + DENIED 终态
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>just(
                new ToolCallStartEvent("r-1", "call-9", "search_news"),
                new ToolResultTextDeltaEvent("r-1", "call-9", "search_news",
                        AgentToolCallDecisionText.USER_DENIAL),
                new ToolResultEndEvent("r-1", "call-9", "search_news", ToolResultState.DENIED)));

        service.streamChat("查资讯", CONVERSATION_ID, new SseEmitter());

        // T1 行工厂 decision 判别：同 denied 块、不同文案 → ASK_DENIED（而非 RULE_DENY）
        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass((Class) List.class);
        verify(jdbcTemplate, timeout(3000)).batchUpdate(anyString(), batch.capture());
        assertThat(batch.getValue()).hasSize(1);
        assertThat(decisionRowOf(batch.getValue(), "search_news")).isEqualTo("ASK_DENIED");

        // 块层同样可判读：denied + 用户拒卡文案
        ArgumentCaptor<List<AgentBlock>> persistedBlocks = ArgumentCaptor.forClass((Class) List.class);
        verify(conversationService, atLeastOnce()).addAssistantMessage(anyString(), anyString(), any(), any(),
                persistedBlocks.capture(), any(), any(), any());
        AgentBlock denied = blockOf(persistedBlocks.getValue(), "search_news");
        assertThat(denied.getStatus()).isEqualTo("denied");
        assertThat(denied.getResult()).isEqualTo(AgentToolCallDecisionText.USER_DENIAL);
    }

    private static AgentBlock blockOf(List<AgentBlock> blocks, String name) {
        return blocks.stream()
                .filter(b -> AgentBlock.KIND_TOOL.equals(b.getKind()) && name.equals(b.getName()))
                .findFirst().orElseThrow(() -> new AssertionError("未找到工具块: " + name));
    }

    /**
     * 批量行 → 指定工具的 decision（列序：..., tool_name@7, decision@10，见 T1 UPSERT_SQL）
     */
    private static String decisionRowOf(List<Object[]> rows, String toolName) {
        return rows.stream()
                .filter(row -> toolName.equals(row[7]))
                .map(row -> (String) row[10])
                .findFirst().orElseThrow(() -> new AssertionError("审计行缺工具: " + toolName));
    }
}
