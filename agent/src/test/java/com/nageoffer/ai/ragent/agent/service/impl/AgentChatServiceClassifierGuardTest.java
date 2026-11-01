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

package com.nageoffer.ai.ragent.agent.service.impl;

import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.dto.AgentCompletionPayload;
import com.nageoffer.ai.ragent.agent.dto.AgentMessageDelta;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryLearner;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import com.nageoffer.ai.ragent.rag.config.RAGClassifierProperties;
import com.nageoffer.ai.ragent.rag.service.preguard.GuardDecision;
import com.nageoffer.ai.ragent.rag.service.preguard.QuestionScopeGuard;
import com.nageoffer.ai.ragent.rag.service.preguard.RejectTemplates;
import com.nageoffer.ai.ragent.rag.service.preguard.ScopeJudgeClient;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atMostOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回答前分类器守卫在 Agent 链的接缝行为（#430）：拒答走既有消息流落会话历史、
 * SSE 事件序与普通回答一致、租约/记忆抽取零触发、flag 开关与模板语言跟随。
 * 只测外部行为；守卫为真实组件（规则层真实、LLM 面 mock）
 */
class AgentChatServiceClassifierGuardTest {

    private static final String USER_ID = "u-4301";
    private static final String CONVERSATION_ID = "c-4302";

    private ReActAgentProvider agentProvider;
    private AgentConversationService conversationService;
    private StreamTaskManager taskManager;
    private AgentRunGate runGate;
    private AgentMemoryLearner memoryLearner;
    private ReActAgent agent;
    private QuestionScopeGuard guard;
    private RAGClassifierProperties properties;
    private AgentChatServiceImpl service;

    @BeforeEach
    void setUp() {
        agentProvider = mock(ReActAgentProvider.class);
        conversationService = mock(AgentConversationService.class);
        taskManager = mock(StreamTaskManager.class);
        runGate = mock(AgentRunGate.class);
        memoryLearner = mock(AgentMemoryLearner.class);
        agent = mock(ReActAgent.class);
        properties = new RAGClassifierProperties();
        properties.setEnabled(true);
        guard = new QuestionScopeGuard(properties, mock(ScopeJudgeClient.class));
        service = new AgentChatServiceImpl(agentProvider, conversationService, taskManager, runGate,
                memoryLearner, guard);

        when(runGate.acquire(anyString(), anyString(), anyString())).thenReturn(() -> {
        });
        when(agentProvider.getAgent()).thenReturn(new ActiveAgent(
                agent, new ResolvedCatalog("知识库工具描述", null, List.of(), List.of(), List.of())));
        when(agent.streamEvents(any(Msg.class), any(RuntimeContext.class))).thenReturn(Flux.empty());
        when(conversationService.touchConversation(anyString(), anyString(), anyString())).thenReturn("会话标题");
        when(conversationService.addUserMessage(anyString(), anyString(), anyString())).thenReturn("m-q-4303");
        when(conversationService.addAssistantMessage(anyString(), anyString(), anyString(), any(),
                any(), anyString(), any(), any())).thenReturn("m-a-4304");
        when(memoryLearner.learn(anyString()))
                .thenReturn(AgentMemoryOutcome.of(AgentMemoryOutcome.Status.BELOW_THRESHOLD));
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void 觢则层开放域题拒答_SSE序列与普通回答一致且落会话历史() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();

        service.streamChat("What is the capital of Australia?", CONVERSATION_ID, emitter);

        // SSE 事件序=meta→message→finish→done（与普通回答一致，前端零改动；不走 emitter error）
        assertThat(emitter.eventNames()).containsExactly("meta", "message", "finish", "done");
        assertThat(emitter.payload(0)).isInstanceOfSatisfying(com.nageoffer.ai.ragent.agent.dto.AgentMetaPayload.class,
                meta -> assertThat(meta.conversationId()).isEqualTo(CONVERSATION_ID));
        assertThat(emitter.payload(1)).isInstanceOfSatisfying(AgentMessageDelta.class,
                delta -> {
                    assertThat(delta.type()).isEqualTo("answer");
                    assertThat(delta.delta()).isEqualTo(RejectTemplates.template("en"));
                });
        assertThat(emitter.payload(2)).isInstanceOfSatisfying(AgentCompletionPayload.class,
                finish -> {
                    assertThat(finish.messageId()).isEqualTo("m-a-4304");
                    assertThat(finish.title()).isEqualTo("会话标题");
                    assertThat(finish.messageStatus()).isEqualTo("NORMAL");
                });
        assertThat(emitter.payload(3)).isEqualTo("[DONE]");
        assertThat(emitter.isCompleted()).isTrue();

        // 拒绝问答双双落会话历史
        verify(conversationService).touchConversation(CONVERSATION_ID, USER_ID, "What is the capital of Australia?");
        verify(conversationService).addUserMessage(CONVERSATION_ID, USER_ID, "What is the capital of Australia?");
        ArgumentCaptor<String> assistantContent = ArgumentCaptor.forClass(String.class);
        verify(conversationService).addAssistantMessage(eq(CONVERSATION_ID), eq(USER_ID),
                assistantContent.capture(), any(), any(), eq("m-q-4303"), any(), any());
        assertThat(assistantContent.getValue()).isEqualTo(RejectTemplates.template("en"));
    }

    @Test
    void 拒答轮不触发模型流_租约_任务注册与记忆抽取() {
        service.streamChat("What is the capital of Australia?", CONVERSATION_ID, new RecordingSseEmitter());

        verify(agentProvider, never()).getAgent();
        verify(agent, never()).streamEvents(any(Msg.class), any(RuntimeContext.class));
        // 守卫决策先于 runGate.acquire：拒答轮无锁无名额被占，无租约残留
        verify(runGate, never()).acquire(anyString(), anyString(), anyString());
        verify(taskManager, never()).register(anyString(), anyString(), any());
        // AGENT_MEMORY_EXTRACTION 面三个触发点的上游全部未执行
        verify(memoryLearner, never()).ensureCursor(anyString());
        verify(memoryLearner, never()).learn(anyString());
    }

    @Test
    void 拒答后二次请求可正常进入_配额照扣语义由控制器先行扣减保证() {
        // 配额在 AgentChatController 的 anonymousTrialGuard.checkAndConsume 先于 streamChat 扣减，
        // 拒答发生在扣减之后=照扣不退；本缝断言拒答不挡后续请求
        service.streamChat("What is the capital of Australia?", CONVERSATION_ID, new RecordingSseEmitter());

        service.streamChat("图书馆几点开门", CONVERSATION_ID, new RecordingSseEmitter());

        // 拒答轮零 acquire；放行轮正常拿闸门
        verify(runGate, times(1)).acquire(eq(USER_ID), anyString(), eq(CONVERSATION_ID));
        verify(agentProvider, times(1)).getAgent();
    }

    @Test
    void 中文开放域题拒绝模板跟随题面语言() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();

        service.streamChat("法国的首都是哪座城市", CONVERSATION_ID, emitter);

        assertThat(emitter.eventNames()).containsExactly("meta", "message", "finish", "done");
        assertThat(emitter.payload(1)).isInstanceOfSatisfying(AgentMessageDelta.class,
                delta -> assertThat(delta.delta()).isEqualTo(RejectTemplates.template("zh")));
    }

    @Test
    void 校园语境题放行走正常回答流() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();

        service.streamChat("图书馆几点开门", CONVERSATION_ID, emitter);

        verify(agentProvider).getAgent();
        verify(agent).streamEvents(any(Msg.class), any(RuntimeContext.class));
        verify(conversationService).addUserMessage(CONVERSATION_ID, USER_ID, "图书馆几点开门");
        // 放行轮不落拒绝模板（正常空流的收尾落库允许存在，但不能是拒答文案）
        ArgumentCaptor<String> assistantContent = ArgumentCaptor.forClass(String.class);
        verify(conversationService, atMostOnce()).addAssistantMessage(anyString(), anyString(),
                assistantContent.capture(), any(), any(), anyString(), any(), any());
        assertThat(assistantContent.getAllValues())
                .noneMatch(content -> content.contains("校园问答助手"));
    }

    @Test
    void 寒暄不经拒答路径() {
        service.streamChat("你好", CONVERSATION_ID, new RecordingSseEmitter());

        verify(agentProvider).getAgent();
        verify(conversationService).addUserMessage(CONVERSATION_ID, USER_ID, "你好");
    }

    @Test
    void flag关闭时开放域题也走正常回答流() {
        properties.setEnabled(false);

        service.streamChat("What is the capital of Australia?", CONVERSATION_ID, new RecordingSseEmitter());

        verify(agentProvider).getAgent();
        verify(runGate).acquire(eq(USER_ID), anyString(), eq(CONVERSATION_ID));
    }

    @Test
    void 首问无会话号时拒答回显服务端生成的会话号() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();

        service.streamChat("法国的首都是哪座城市", "  ", emitter);

        assertThat(emitter.eventNames()).containsExactly("meta", "message", "finish", "done");
        assertThat(emitter.payload(0)).isInstanceOfSatisfying(com.nageoffer.ai.ragent.agent.dto.AgentMetaPayload.class,
                meta -> assertThat(meta.conversationId()).matches("\\d+"));
    }

    /**
     * 测试用 SSE 捕获器：只经公共 API（SseEventBuilder.build/DataWithMediaType.getData）还原事件名与载荷
     */
    static final class RecordingSseEmitter extends SseEmitter {

        private static final java.util.regex.Pattern EVENT_LINE =
                java.util.regex.Pattern.compile("(?m)^event:\\s*(\\S+)\\s*$");

        private final List<String> eventNames = new ArrayList<>();
        private final List<Object> payloads = new ArrayList<>();
        private final AtomicInteger completions = new AtomicInteger();

        RecordingSseEmitter() {
            super(0L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) {
            String currentEvent = null;
            for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
                if (entry.getData() instanceof String frame) {
                    java.util.regex.Matcher matcher = EVENT_LINE.matcher(frame);
                    if (matcher.find()) {
                        currentEvent = matcher.group(1);
                    } else if (currentEvent != null && !frame.isBlank()) {
                        // data 载荷本身是字符串（如 done 事件的 "[DONE]"）
                        eventNames.add(currentEvent);
                        payloads.add(frame);
                        currentEvent = null;
                    }
                } else if (currentEvent != null) {
                    eventNames.add(currentEvent);
                    payloads.add(entry.getData());
                    currentEvent = null;
                }
            }
        }

        @Override
        public synchronized void complete() {
            completions.incrementAndGet();
        }

        List<String> eventNames() {
            return eventNames;
        }

        Object payload(int index) {
            return payloads.get(index);
        }

        boolean isCompleted() {
            return completions.get() > 0;
        }
    }
}
