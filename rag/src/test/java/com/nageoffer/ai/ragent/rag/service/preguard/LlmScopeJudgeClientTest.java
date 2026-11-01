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

package com.nageoffer.ai.ragent.rag.service.preguard;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.rag.config.RAGClassifierProperties;
import com.nageoffer.ai.ragent.rag.service.preguard.ScopeJudgeClient.Vote;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 判定客户端边界：解析、fail-open（报错/超时）、请求形态（LLM 面全部 mock）
 */
@ExtendWith(MockitoExtension.class)
class LlmScopeJudgeClientTest {

    @Mock
    private LLMService llmService;

    private RAGClassifierProperties properties;
    private LlmScopeJudgeClient client;

    @BeforeEach
    void setUp() {
        properties = new RAGClassifierProperties();
        properties.setLlmTimeoutMs(500L);
        client = new LlmScopeJudgeClient(llmService, properties);
    }

    @AfterEach
    void tearDown() {
        client.shutdownExecutor();
    }

    @Test
    void 模型答YES判域内() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("YES");

        assertThat(client.vote("怎么做番茄炒蛋")).isEqualTo(Vote.IN_SCOPE);
    }

    @Test
    void 模型答NO判域外() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("NO");

        assertThat(client.vote("怎么做番茄炒蛋")).isEqualTo(Vote.OUT_OF_SCOPE);
    }

    @Test
    void 模型答围栏包裹的YES同样识别() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("```text\nYES\n```");

        assertThat(client.vote("怎么做番茄炒蛋")).isEqualTo(Vote.IN_SCOPE);
    }

    @Test
    void 模型答非约定词判解析失败fail_open() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("maybe, it depends");

        assertThat(client.vote("怎么做番茄炒蛋")).isEqualTo(Vote.FAILED);
    }

    @Test
    void 模型报错判FAILED() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST)))
                .thenThrow(new IllegalStateException("供应商不可用"));

        assertThat(client.vote("怎么做番茄炒蛋")).isEqualTo(Vote.FAILED);
    }

    @Test
    void 模型超时判FAILED() {
        properties.setLlmTimeoutMs(80L);
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenAnswer(invocation -> {
            Thread.sleep(500L);
            return "YES";
        });

        assertThat(client.vote("怎么做番茄炒蛋")).isEqualTo(Vote.FAILED);
    }

    @Test
    void 请求带判定系统提示与原问题() {
        when(llmService.chat(any(ChatRequest.class), eq(Tier.FAST))).thenReturn("YES");

        client.vote("怎么做番茄炒蛋");

        ArgumentCaptor<ChatRequest> request = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llmService).chat(request.capture(), eq(Tier.FAST));
        assertThat(request.getValue().getMessages()).hasSize(2);
        assertThat(request.getValue().getMessages().get(0).getRole()).isEqualTo(ChatMessage.Role.SYSTEM);
        assertThat(request.getValue().getMessages().get(0).getContent()).contains("OUT OF SCOPE");
        assertThat(request.getValue().getMessages().get(1).getRole()).isEqualTo(ChatMessage.Role.USER);
        assertThat(request.getValue().getMessages().get(1).getContent()).isEqualTo("怎么做番茄炒蛋");
    }

    @Test
    void 解析边界_NO不误伤NOT等词() {
        assertThat(LlmScopeJudgeClient.parse("NOT SURE")).isEqualTo(Vote.FAILED);
        assertThat(LlmScopeJudgeClient.parse("No.")).isEqualTo(Vote.OUT_OF_SCOPE);
        assertThat(LlmScopeJudgeClient.parse("yes")).isEqualTo(Vote.IN_SCOPE);
        assertThat(LlmScopeJudgeClient.parse("")).isEqualTo(Vote.FAILED);
        assertThat(LlmScopeJudgeClient.parse(null)).isEqualTo(Vote.FAILED);
    }
}
