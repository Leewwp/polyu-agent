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
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryLearner;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.impl.AgentChatServiceImpl;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolInputSummary;
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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 开态集成（#486 验收 6）：flag=true + mock 模型跑一轮含 search_knowledge 的对话 →
 * 行数=blocks 中 tool 块数，user_id/role/conversation_id/message_id/tool_name/status/decision/duration_ms 逐列断言。
 * 真库部分沿用 AgentDashboardReaderTest 的可选 PostgreSQL 模式（临时表+回滚，系统属性门控，CI 缺省跳过）。
 */
class AgentToolCallAuditIntegrationTest {

    private static final String USER_ID = "u-1001";
    private static final String CONVERSATION_ID = "c-2002";

    private ReActAgent agent;
    private AgentConversationService conversationService;
    private JdbcTemplate jdbcTemplate;
    private AgentMessageMapper messageMapper;

    @BeforeEach
    void setUp() {
        agent = mock(ReActAgent.class);
        conversationService = mock(AgentConversationService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        messageMapper = mock(AgentMessageMapper.class);
        when(conversationService.touchConversation(anyString(), anyString(), anyString())).thenReturn("标题");
        when(conversationService.addUserMessage(anyString(), anyString(), anyString())).thenReturn("m-3003");
        when(conversationService.addAssistantMessage(anyString(), anyString(), any(), any(), any(),
                any(), any(), any())).thenReturn("m-5001");
    }

    private AgentChatServiceImpl serviceWith(AgentToolCallAuditWriter writer) {
        var agentProvider = mock(ReActAgentProvider.class);
        var taskManager = mock(StreamTaskManager.class);
        var runGate = mock(AgentRunGate.class);
        var memoryLearner = mock(AgentMemoryLearner.class);
        when(agentProvider.getAgent()).thenReturn(new ActiveAgent(
                agent, new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of())));
        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(() -> {
        });
        when(memoryLearner.learn(anyString())).thenReturn(
                AgentMemoryOutcome.of(AgentMemoryOutcome.Status.BELOW_THRESHOLD));
        return new AgentChatServiceImpl(agentProvider, conversationService, taskManager, runGate,
                memoryLearner, new QuestionScopeGuard(new RAGClassifierProperties(), mock(ScopeJudgeClient.class)),
                writer, mock(com.nageoffer.ai.ragent.agent.permission.AgentToolPermissionService.class));
    }

    private AgentToolCallAuditWriter enabledWriter() {
        AgentProperties properties = new AgentProperties();
        properties.getAudit().setEnabled(true);
        return new AgentToolCallAuditWriter(jdbcTemplate, messageMapper, properties, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void flagOnRunProducesOneRowPerToolBlockWithEveryColumnAsserted() {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").role("user").build());
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.<AgentEvent>just(
                new ToolCallStartEvent("r-1", "call-1", "search_knowledge"),
                new ToolResultEndEvent("r-1", "call-1", "search_knowledge", ToolResultState.SUCCESS)));

        serviceWith(enabledWriter()).streamChat("图书馆开放时间", CONVERSATION_ID, new SseEmitter());

        // 消息行确实带着 tool 块落的库，行数口径=blocks 中 tool 块数
        ArgumentCaptor<List<AgentBlock>> persistedBlocks = ArgumentCaptor.forClass((Class) List.class);
        verify(conversationService).addAssistantMessage(anyString(), anyString(), any(), any(),
                persistedBlocks.capture(), any(), any(), any());
        List<AgentBlock> blocks = persistedBlocks.getValue();
        long toolBlocks = blocks.stream().filter(b -> AgentBlock.KIND_TOOL.equals(b.getKind())).count();
        assertThat(toolBlocks).isEqualTo(1);

        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass((Class) List.class);
        verify(jdbcTemplate).batchUpdate(anyString(), batch.capture());
        assertThat(batch.getValue()).hasSize((int) toolBlocks);
        Object[] row = batch.getValue().get(0);
        // 列序 = (id, conversation_id, user_id, role, message_id, block_index, run_id, tool_name,
        //        tool_call_id, batch_id, decision, status, input_digest, input_excerpt, duration_ms)
        assertThat(((String) row[0])).matches("\\d{1,20}");
        assertThat(row[1]).isEqualTo(CONVERSATION_ID);
        assertThat(row[2]).isEqualTo(USER_ID);
        // role 由请求线程经 RunScope 携带到 settle 点（reactor 线程上 UserContext 不在场）
        assertThat(row[3]).isEqualTo("user");
        assertThat(row[4]).isEqualTo("m-5001");
        assertThat(row[5]).isEqualTo(blocks.indexOf(blocks.stream()
                .filter(b -> AgentBlock.KIND_TOOL.equals(b.getKind())).findFirst().orElseThrow()));
        assertThat(row[6]).isNull();
        assertThat(row[7]).isEqualTo("search_knowledge");
        assertThat(row[8]).isEqualTo("call-1");
        assertThat(row[9]).isNull();
        assertThat(row[10]).isEqualTo("ALLOW");
        assertThat(row[11]).isEqualTo("done");
        assertThat(row[12]).isNull();
        assertThat(row[13]).isNull();
        assertThat(row[14]).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertIsIdempotentAndRecomputeOverwritesDecision() throws Exception {
        String url = System.getProperty("ragent.audit.test.jdbc-url");
        assumeTrue(url != null, "Set ragent.audit.test.jdbc-url to run PostgreSQL integration test");
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.execute("""
                        CREATE TEMP TABLE t_agent_tool_call (
                            id VARCHAR(20) PRIMARY KEY,
                            conversation_id VARCHAR(20) NOT NULL,
                            user_id VARCHAR(20) NOT NULL,
                            role VARCHAR(16),
                            message_id VARCHAR(20) NOT NULL,
                            block_index SMALLINT NOT NULL,
                            run_id VARCHAR(64),
                            tool_name VARCHAR(128) NOT NULL,
                            tool_call_id VARCHAR(128),
                            batch_id VARCHAR(64),
                            decision VARCHAR(16) NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            input_digest VARCHAR(64),
                            input_excerpt VARCHAR(256),
                            duration_ms BIGINT,
                            create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )""");
                jdbc.execute("CREATE UNIQUE INDEX uk_agent_tool_call_message_block "
                        + "ON t_agent_tool_call (message_id, block_index)");

                AgentToolCallAuditWriter writer = realWriterOver(jdbc);
                AgentBlock done = AgentBlock.builder().kind(AgentBlock.KIND_TOOL)
                        .name("search_knowledge").status("done").toolCallId("call-1").build();
                // 首写：done→ALLOW，带摘要
                writer.upsertAll(AgentToolCallAuditRowFactory.rowsOf(
                        new AgentToolCallAuditRowFactory.RunMetadata(CONVERSATION_ID, USER_ID, "user", "m-5001"),
                        List.of(done), id -> new ToolInputSummary("digest-1", "{\"q\":\"x\"}")));
                // 拒卡改写后的补算：同键重算 denied→ASK_DENIED，摘要源为 null
                AgentBlock denied = AgentBlock.builder().kind(AgentBlock.KIND_TOOL)
                        .name("search_knowledge").status("denied").toolCallId("call-1").build();
                writer.upsertAll(AgentToolCallAuditRowFactory.rowsOf(
                        new AgentToolCallAuditRowFactory.RunMetadata(CONVERSATION_ID, USER_ID, null, "m-5001"),
                        List.of(denied), id -> null));

                var rows = jdbc.queryForList("SELECT id, decision, status, role, input_digest "
                        + "FROM t_agent_tool_call WHERE message_id = 'm-5001'");
                assertThat(rows).hasSize(1);
                Map<String, Object> row = rows.get(0);
                // 重算覆盖 decision/status；键不变仍是同一行
                assertThat(row.get("decision")).isEqualTo("ASK_DENIED");
                assertThat(row.get("status")).isEqualTo("denied");
                // 补算侧 role/摘要为 null 时保持库内原值（首写指纹不丢）
                assertThat(row.get("role")).isEqualTo("user");
                assertThat(row.get("input_digest")).isEqualTo("digest-1");
            } finally {
                connection.rollback();
            }
        }
    }

    private AgentToolCallAuditWriter realWriterOver(JdbcTemplate jdbc) {
        AgentProperties properties = new AgentProperties();
        properties.getAudit().setEnabled(true);
        return new AgentToolCallAuditWriter(jdbc, messageMapper, properties, Runnable::run);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rebuildMessageReadsPersistedBlocksAndUpserts() throws Exception {
        String url = System.getProperty("ragent.audit.test.jdbc-url");
        assumeTrue(url != null, "Set ragent.audit.test.jdbc-url to run PostgreSQL integration test");
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.execute("""
                        CREATE TEMP TABLE t_agent_tool_call (
                            id VARCHAR(20) PRIMARY KEY,
                            conversation_id VARCHAR(20) NOT NULL,
                            user_id VARCHAR(20) NOT NULL,
                            role VARCHAR(16),
                            message_id VARCHAR(20) NOT NULL,
                            block_index SMALLINT NOT NULL,
                            run_id VARCHAR(64),
                            tool_name VARCHAR(128) NOT NULL,
                            tool_call_id VARCHAR(128),
                            batch_id VARCHAR(64),
                            decision VARCHAR(16) NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            input_digest VARCHAR(64),
                            input_excerpt VARCHAR(256),
                            duration_ms BIGINT,
                            create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )""");
                jdbc.execute("CREATE UNIQUE INDEX uk_agent_tool_call_message_block "
                        + "ON t_agent_tool_call (message_id, block_index)");

                // 拒卡改写后的消息形态：awaiting→denied 已由结算写回 blocks
                AgentMessageDO rewritten = AgentMessageDO.builder()
                        .id("m-5001").conversationId(CONVERSATION_ID).userId(USER_ID)
                        .blocks(List.of(AgentBlock.builder().kind(AgentBlock.KIND_TOOL)
                                .name("apply_memory_change").status("denied").toolCallId("call-9").build()))
                        .build();
                when(messageMapper.selectOne(any())).thenReturn(rewritten);

                realWriterOver(jdbc).rebuildMessage(CONVERSATION_ID, USER_ID, "m-5001", "user");

                var decisions = jdbc.queryForList(
                        "SELECT decision FROM t_agent_tool_call WHERE message_id = 'm-5001'", String.class);
                // 拒卡路径 ASK_DENIED 行不缺失（补充触发点的存在理由）
                assertThat(decisions).containsExactly("ASK_DENIED");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void noToolBlocksMeansNoWrite() {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").role("user").build());
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());

        serviceWith(enabledWriter()).streamChat("纯文本问题", CONVERSATION_ID, new SseEmitter());

        // 纯文本轮没有 tool 块，审计零写入
        verify(jdbcTemplate, times(0)).batchUpdate(anyString(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankToolCallIdBlocksFallBackToSharedUnknownFactSummary() {
        // 端点不回 id（toolCallId 留空）：块退化读 facts 的 "unknown" 事实共享摘要，而非整行留空
        AgentToolExecutionFacts facts = new AgentToolExecutionFacts("r-1", java.time.Clock.systemDefaultZone());
        facts.markInputSummary(null, new ToolInputSummary("digest-blank", "{\"q\":\"x\"}"));
        AgentBlock blankId = AgentBlock.builder().kind(AgentBlock.KIND_TOOL)
                .name("search_knowledge").status("done").toolCallId("").build();

        enabledWriter().recordSettled(CONVERSATION_ID, USER_ID, "user", "m-5001", List.of(blankId), facts);

        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass((Class) List.class);
        verify(jdbcTemplate).batchUpdate(anyString(), batch.capture());
        assertThat(batch.getValue()).hasSize(1);
        Object[] row = batch.getValue().get(0);
        // blank id 块 digest/excerpt 非空（共享同一份摘要）；tool_call_id 列仍按归一 null 落库
        assertThat(row[12]).isEqualTo("digest-blank");
        assertThat(row[13]).isEqualTo("{\"q\":\"x\"}");
        assertThat(row[8]).isNull();
    }
}
