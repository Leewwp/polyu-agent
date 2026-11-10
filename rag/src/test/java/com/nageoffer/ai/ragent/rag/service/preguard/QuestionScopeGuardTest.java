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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nageoffer.ai.ragent.rag.config.RAGClassifierProperties;
import com.nageoffer.ai.ragent.rag.service.preguard.ScopeJudgeClient.Vote;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 守卫组件行为：flag 开关、规则层先行、不确定带 LLM 兜底（单/双票）、fail-open、决策日志
 */
@ExtendWith(MockitoExtension.class)
class QuestionScopeGuardTest {

    @Mock
    private ScopeJudgeClient judgeClient;

    private RAGClassifierProperties properties;
    private QuestionScopeGuard guard;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger guardLogger;

    @BeforeEach
    void setUp() {
        properties = new RAGClassifierProperties();
        properties.setEnabled(true);
        properties.setLlmVotes(2);
        guard = new QuestionScopeGuard(properties, judgeClient);
        logAppender = new ListAppender<>();
        guardLogger = (Logger) LoggerFactory.getLogger(QuestionScopeGuard.class);
        guardLogger.addAppender(logAppender);
        logAppender.start();
    }

    @AfterEach
    void tearDown() {
        guardLogger.detachAppender(logAppender);
    }

    @Test
    void flag关闭时任何问题都放行且不判定() {
        properties.setEnabled(false);

        GuardDecision decision = guard.check("《围城》的作者是谁");

        assertThat(decision.rejected()).isFalse();
        verify(judgeClient, never()).vote(anyString());
    }

    @Test
    void 规则层放行的问题不触发LLM() {
        GuardDecision decision = guard.check("香港理工大学的学费是多少？");

        assertThat(decision.rejected()).isFalse();
        assertThat(decision.reasonFamily()).isEqualTo("institution");
        assertThat(decision.lang()).isEqualTo("zh");
        verify(judgeClient, never()).vote(anyString());
    }

    @Test
    void 规则层拒答不触发LLM() {
        GuardDecision decision = guard.check("What is the capital of Australia?");

        assertThat(decision.rejected()).isTrue();
        assertThat(decision.reasonFamily()).isEqualTo("open-domain-rule");
        verify(judgeClient, never()).vote(anyString());
    }

    @Test
    void 寒暄放行不触发LLM() {
        GuardDecision decision = guard.check("hello!");

        assertThat(decision.rejected()).isFalse();
        assertThat(decision.reasonFamily()).isEqualTo("chitchat");
        verify(judgeClient, never()).vote(anyString());
    }

    @Test
    void 不确定带双票一致拒答则拒答() {
        when(judgeClient.vote(anyString())).thenReturn(Vote.OUT_OF_SCOPE);

        GuardDecision decision = guard.check("怎么做番茄炒蛋");

        assertThat(decision.rejected()).isTrue();
        assertThat(decision.reasonFamily()).isEqualTo("llm-consensus");
        verify(judgeClient, times(2)).vote(anyString());
    }

    @Test
    void 不确定带双票一致放行则放行() {
        when(judgeClient.vote(anyString())).thenReturn(Vote.IN_SCOPE);

        GuardDecision decision = guard.check("怎么做番茄炒蛋");

        assertThat(decision.rejected()).isFalse();
        assertThat(decision.reasonFamily()).isEqualTo("llm-consensus");
    }

    @Test
    void 双票不一致放行_误拒优先于漏拒() {
        when(judgeClient.vote(anyString())).thenReturn(Vote.IN_SCOPE, Vote.OUT_OF_SCOPE);

        GuardDecision decision = guard.check("怎么做番茄炒蛋");

        assertThat(decision.rejected()).isFalse();
        assertThat(decision.reasonFamily()).isEqualTo("llm-disagreement");
    }

    @Test
    void 任一票失败即放行_fail_open() {
        when(judgeClient.vote(anyString())).thenReturn(Vote.FAILED, Vote.OUT_OF_SCOPE);

        GuardDecision decision = guard.check("怎么做番茄炒蛋");

        assertThat(decision.rejected()).isFalse();
        assertThat(decision.reasonFamily()).isEqualTo("llm-fail-open");
    }

    @Test
    void 单票模式按单票结论() {
        properties.setLlmVotes(1);
        when(judgeClient.vote(anyString())).thenReturn(Vote.OUT_OF_SCOPE);

        assertThat(guard.check("怎么做番茄炒蛋").rejected()).isTrue();

        when(judgeClient.vote(anyString())).thenReturn(Vote.IN_SCOPE);
        assertThat(guard.check("怎么做番茄炒蛋").rejected()).isFalse();

        when(judgeClient.vote(anyString())).thenReturn(Vote.FAILED);
        GuardDecision failOpen = guard.check("怎么做番茄炒蛋");
        assertThat(failOpen.rejected()).isFalse();
        assertThat(failOpen.reasonFamily()).isEqualTo("llm-fail-open");
        verify(judgeClient, times(3)).vote(anyString());
    }

    @Test
    void 票数配置超上界时按双票执行且钳制打WARN() {
        properties.setLlmVotes(3);
        when(judgeClient.vote(anyString())).thenReturn(Vote.OUT_OF_SCOPE);

        assertThat(guard.check("怎么做番茄炒蛋").rejected()).isTrue();

        // 行为不变：超上界配置仍按上界 2 票出票
        verify(judgeClient, times(2)).vote(anyString());
        // 可见性：静默钳制改为 WARN（含配置值与生效值）
        assertThat(logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("configured=3")
                        && event.getFormattedMessage().contains("effective=2")))
                .isTrue();
    }

    @Test
    void 判定组件抛出意外异常时放行() {
        when(judgeClient.vote(anyString())).thenThrow(new IllegalStateException("意外"));

        GuardDecision decision = guard.check("怎么做番茄炒蛋");

        assertThat(decision.rejected()).isFalse();
        assertThat(decision.reasonFamily()).isEqualTo("guard-error");
    }

    @Test
    void 英文问题语言跟随() {
        GuardDecision decision = guard.check("What is the capital of Australia?");

        assertThat(decision.lang()).isEqualTo("en");
    }

    @Test
    void 决策日志含决策要素且不含问句全文() {
        when(judgeClient.vote(anyString())).thenReturn(Vote.OUT_OF_SCOPE);
        String question = "怎么做番茄炒蛋要不要先放蛋";

        guard.check(question);

        List<ILoggingEvent> decisionLogs = logAppender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("回答前分类器决策"))
                .toList();
        assertThat(decisionLogs).hasSize(1);
        String message = decisionLogs.get(0).getFormattedMessage();
        assertThat(message).contains("decision=reject");
        assertThat(message).contains("reason=llm-consensus");
        assertThat(message).contains("uncertain=true");
        assertThat(message).contains("lang=zh");
        assertThat(message).contains("latencyMs=");
        // 决策日志不含问句全文（#430 契约：只记长度）
        assertThat(message).doesNotContain(question);
        assertThat(message).contains("questionChars=");
    }

    @Test
    void fail_open时打WARN() {
        when(judgeClient.vote(anyString())).thenReturn(Vote.FAILED);

        guard.check("怎么做番茄炒蛋");

        assertThat(logAppender.list.stream()
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("回答前分类器决策")
                        && event.getFormattedMessage().contains("reason=llm-fail-open")))
                .isTrue();
    }

    @Test
    void 拒绝模板语言跟随题面() {
        assertThat(RejectTemplates.template("zh")).contains("香港理工大学");
        assertThat(RejectTemplates.template("en")).contains("PolyU");
        // 判不出语言回落中文模板
        assertThat(RejectTemplates.template(null)).isEqualTo(RejectTemplates.template("zh"));
        // 模板是礼貌解释+引导提问，不含攻击性或机密内容
        assertThat(RejectTemplates.template("en")).contains("tuition");
    }
}
