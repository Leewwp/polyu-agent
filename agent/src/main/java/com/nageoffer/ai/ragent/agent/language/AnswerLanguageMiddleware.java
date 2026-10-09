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

package com.nageoffer.ai.ragent.agent.language;

import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 本轮回答语言指令注入：把入口判定出的语言约束以请求内副本形式交给模型
 * <p>
 * 每个请求的 RuntimeContext 各自携带语言值，改写只作用于本次推理输入——
 * 不改缓存 ReActAgent 的全局 persona（并发用户会串语言），也不写进落盘会话状态。
 * 注册在压缩之后：压缩中间件的消息比对永远看不到指令块，先滤旧再插新保证逐轮幂等。
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AnswerLanguageMiddleware implements MiddlewareBase {

    /**
     * 指令块标记：同名块先剔除再插入，跨轮重复注入也只留一份
     */
    public static final String DIRECTIVE_MESSAGE_NAME = "answer-language-directive";

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext runtimeContext, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        String answerLanguage = runtimeContext == null
                ? null
                : runtimeContext.get(AnswerLanguages.RUNTIME_CONTEXT_KEY) instanceof String value ? value : null;
        String directive = AnswerLanguages.turnDirective(answerLanguage);
        if (directive == null) {
            return next.apply(input);
        }
        List<Msg> messages = input.messages();
        boolean hasStale = messages.stream().anyMatch(AnswerLanguageMiddleware::isDirectiveMessage);
        if (!hasStale) {
            List<Msg> rebuilt = new ArrayList<>(messages.size() + 1);
            rebuilt.addAll(messages);
            rebuilt.add(personaBoundary(rebuilt), buildDirectiveMsg(directive));
            return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
        }
        List<Msg> rebuilt = new ArrayList<>(messages.size());
        for (Msg msg : messages) {
            if (!isDirectiveMessage(msg)) {
                rebuilt.add(msg);
            }
        }
        rebuilt.add(personaBoundary(rebuilt), buildDirectiveMsg(directive));
        return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
    }

    /**
     * 插在人设之后、会话首条之前：与记忆块同位，尾插会干扰依赖首格的既有比对
     */
    private int personaBoundary(List<Msg> messages) {
        int index = 0;
        while (index < messages.size() && messages.get(index).getRole() == MsgRole.SYSTEM) {
            index++;
        }
        return index;
    }

    private static boolean isDirectiveMessage(Msg msg) {
        return DIRECTIVE_MESSAGE_NAME.equals(msg.getName());
    }

    /**
     * 用 USER 不用 SYSTEM：与记忆块形制同源，供应商对中途 SYSTEM 的容忍度不一
     */
    private Msg buildDirectiveMsg(String directive) {
        return Msg.builder()
                .id("answer-language-" + UUID.randomUUID().toString().replace("-", ""))
                .name(DIRECTIVE_MESSAGE_NAME)
                .role(MsgRole.USER)
                .textContent(directive)
                .build();
    }
}
