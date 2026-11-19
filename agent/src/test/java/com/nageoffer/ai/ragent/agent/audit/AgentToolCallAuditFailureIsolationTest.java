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
import com.nageoffer.ai.ragent.agent.dto.AgentCompletionPayload;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunHandle;
import com.nageoffer.ai.ragent.agent.service.handler.AgentStreamEventBridge;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旁路不阻断（#486 验收 4）：writer 抛异常/DB 不可用 → settle 主路径正常返回 messageId、
 * 消息行已落、仅 WARN，不改消息落库时序。
 */
@ExtendWith(OutputCaptureExtension.class)
class AgentToolCallAuditFailureIsolationTest {

    private static final String TASK_ID = "t-9001";
    private static final String CONVERSATION_ID = "c-2002";
    private static final String USER_ID = "u-1001";

    private SseEmitterSender sender;
    private StreamTaskManager taskManager;
    private AgentConversationService conversationService;
    private AgentToolExecutionFacts facts;
    private AgentProperties properties;

    @BeforeEach
    void setUp() {
        sender = mock(SseEmitterSender.class);
        taskManager = mock(StreamTaskManager.class);
        conversationService = mock(AgentConversationService.class);
        facts = new AgentToolExecutionFacts(TASK_ID, Clock.systemDefaultZone());
        properties = new AgentProperties();
        properties.getAudit().setEnabled(true);
        // 消息行总能落进去：审计失败与否与消息落库解耦
        when(conversationService.addAssistantMessage(anyString(), anyString(), any(), any(), any(),
                any(), any(), any())).thenReturn("m-5001");
    }

    private AgentStreamEventBridge bridgeWith(AgentToolCallAuditWriter writer) {
        return AgentStreamEventBridge.builder()
                .runHandle(new AgentRunHandle(TASK_ID, sender, taskManager, facts, null))
                .conversationService(conversationService)
                .catalog(new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of()))
                .conversationId(CONVERSATION_ID)
                .userId(USER_ID)
                .title("会话标题")
                .clock(Clock.systemDefaultZone())
                .facts(facts)
                .role("user")
                .auditWriter(writer)
                .build();
    }

    private void runToolRound(AgentStreamEventBridge bridge) {
        bridge.onEvent(new TextBlockDeltaEvent("r-1", "b-1", "先查一下"));
        bridge.onEvent(new ToolCallStartEvent("r-1", "call-1", "search_knowledge"));
        bridge.onEvent(new ToolResultEndEvent("r-1", "call-1", "search_knowledge", ToolResultState.SUCCESS));
        bridge.onComplete();
    }

    /** finish 帧携带的 messageId 必须不受审计失败影响 */
    private String finishMessageId() {
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(sender, org.mockito.Mockito.atLeastOnce()).sendEvent(anyString(), payloads.capture());
        return payloads.getAllValues().stream()
                .filter(AgentCompletionPayload.class::isInstance)
                .map(AgentCompletionPayload.class::cast)
                .findFirst()
                .map(AgentCompletionPayload::messageId)
                .orElse(null);
    }

    @Test
    void dbFailureDuringAuditWriteLeavesSettlePathIntact(CapturedOutput output) {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        doThrow(new RuntimeException("db down")).when(jdbc).batchUpdate(anyString(), anyList());
        AgentToolCallAuditWriter writer = new AgentToolCallAuditWriter(jdbc,
                mock(com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper.class), properties, Runnable::run);

        runToolRound(bridgeWith(writer));

        // 主路径口径不变：消息行已落、finish 带非空 messageId、正常收尾
        verify(conversationService).addAssistantMessage(anyString(), anyString(), any(), any(),
                any(), any(), any(), any());
        assertThat(finishMessageId()).isEqualTo("m-5001");
        verify(sender).complete();
        // 审计失败只以 WARN 留痕，不抛出、不改落库时序
        assertThat(output).contains("WARN");
        assertThatCode(() -> verify(jdbc).batchUpdate(anyString(), anyList())).doesNotThrowAnyException();
    }

    @Test
    void writerThrowingAtSubmissionStillSettlesMessage(CapturedOutput output) {
        AgentToolCallAuditWriter writer = mock(AgentToolCallAuditWriter.class);
        doThrow(new IllegalStateException("审计装配炸了")).when(writer)
                .recordSettled(anyString(), anyString(), any(), anyString(), any(), any());

        assertThatCode(() -> runToolRound(bridgeWith(writer))).doesNotThrowAnyException();

        // 桥侧兜底捕获：即便 writer 自身违约抛出，settle 结果不受影响
        assertThat(finishMessageId()).isEqualTo("m-5001");
        assertThat(output).contains("WARN");
    }

    @Test
    void executorSaturationDropsAuditSilently(CapturedOutput output) {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        // 队列满=审计丢行，仅 WARN，主链无感
        java.util.concurrent.Executor saturated = task -> {
            throw new RejectedExecutionException("queue full");
        };
        AgentToolCallAuditWriter writer = new AgentToolCallAuditWriter(jdbc,
                mock(com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper.class), properties, saturated);

        assertThatCode(() -> runToolRound(bridgeWith(writer))).doesNotThrowAnyException();

        verify(conversationService).addAssistantMessage(anyString(), anyString(), any(), any(),
                any(), any(), any(), any());
        assertThat(output).contains("WARN");
    }
}
