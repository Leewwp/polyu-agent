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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.rag.config.RAGClassifierProperties;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.pipeline.StreamChatPipeline;
import com.nageoffer.ai.ragent.rag.service.preguard.QuestionScopeGuard;
import com.nageoffer.ai.ragent.rag.service.preguard.RejectTemplates;
import com.nageoffer.ai.ragent.rag.service.preguard.ScopeJudgeClient;
import com.nageoffer.ai.ragent.rag.service.ratelimit.ChatQueueLimiter;
import com.nageoffer.ai.ragent.rag.trace.StreamChatTraceRunner;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 回答前分类器守卫在 RAG 链的接缝行为（#430）：拒答走既有 emitter 拒绝流
 * （meta→reject→finish→done，由 ChatQueueLimiter 渲染）、放行走正常管道、flag 开关。
 * 只测外部行为；守卫为真实组件（规则层真实、LLM 面 mock）
 */
@ExtendWith(MockitoExtension.class)
class RAGChatServiceClassifierGuardTest {

    @Mock
    private StreamChatPipeline chatPipeline;

    @Mock
    private ChatQueueLimiter chatQueueLimiter;

    @Mock
    private StreamCallbackFactory callbackFactory;

    @Mock
    private StreamChatTraceRunner traceRunner;

    @Mock
    private StreamTaskManager taskManager;

    private RAGClassifierProperties properties;
    private RAGChatServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new RAGClassifierProperties();
        properties.setEnabled(true);
        QuestionScopeGuard guard = new QuestionScopeGuard(properties, mock(ScopeJudgeClient.class));
        service = new RAGChatServiceImpl(chatPipeline, chatQueueLimiter, callbackFactory, traceRunner,
                taskManager, guard);
    }

    @Test
    void 规则层开放域题走emitter拒绝流_不建回调() {
        service.streamChat("What is the capital of Australia?", "conv-r1", false, new SseEmitter());

        // 拒答交由 ChatQueueLimiter 按既有拒绝口径渲染（meta→reject→finish→done + 双消息落库）
        verify(chatQueueLimiter).rejectWithTemplate(eq("What is the capital of Australia?"), anyString(),
                any(SseEmitter.class), eq(RejectTemplates.template("en")));
        // 建流前拒绝：callback 未建（meta 不会先发出）、排队与管道不触发
        verify(callbackFactory, never()).createChatEventHandler(any(), anyString(), anyString());
        verify(chatQueueLimiter, never()).enqueue(anyString(), anyString(), any(), any());
        verify(chatPipeline, never()).execute(any());
    }

    @Test
    void 中文开放域题拒绝模板跟随题面语言() {
        service.streamChat("法国的首都是哪座城市", "conv-r2", false, new SseEmitter());

        verify(chatQueueLimiter).rejectWithTemplate(eq("法国的首都是哪座城市"), eq("conv-r2"),
                any(SseEmitter.class), eq(RejectTemplates.template("zh")));
    }

    @Test
    void 校园语境题放行走正常管道() {
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString()))
                .thenReturn(mock(StreamCallback.class));

        service.streamChat("图书馆几点开门", "conv-r3", false, new SseEmitter());

        verify(callbackFactory).createChatEventHandler(any(), eq("conv-r3"), anyString());
        verify(chatQueueLimiter).enqueue(eq("图书馆几点开门"), eq("conv-r3"), any(), any());
        verify(chatQueueLimiter, never()).rejectWithTemplate(anyString(), anyString(), any(), anyString());
    }

    @Test
    void 寒暄不经拒答路径() {
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString()))
                .thenReturn(mock(StreamCallback.class));

        service.streamChat("你好", "conv-r4", false, new SseEmitter());

        verify(chatQueueLimiter, never()).rejectWithTemplate(anyString(), anyString(), any(), anyString());
        verify(chatQueueLimiter).enqueue(eq("你好"), eq("conv-r4"), any(), any());
    }

    @Test
    void flag关闭时开放域题也走正常管道() {
        properties.setEnabled(false);
        when(callbackFactory.createChatEventHandler(any(), anyString(), anyString()))
                .thenReturn(mock(StreamCallback.class));

        service.streamChat("What is the capital of Australia?", "conv-r5", false, new SseEmitter());

        verify(chatQueueLimiter, never()).rejectWithTemplate(anyString(), anyString(), any(), anyString());
        verify(chatQueueLimiter).enqueue(anyString(), anyString(), any(), any());
    }
}
