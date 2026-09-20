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

package com.nageoffer.ai.ragent.agent.controller;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.controller.request.ConfirmRequest;
import com.nageoffer.ai.ragent.agent.controller.request.AgentChatRequest;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.rag.service.AnonymousTrialGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * Agent 对话入口，仅 ragent.engine.type=agent 时注册；RAG v3 接口不受影响
 */
@RestController
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentChatService agentChatService;
    private final AgentProperties agentProperties;
    private final AnonymousTrialGuard anonymousTrialGuard;

    /**
     * L34（#95 扩展面）：问题全文改经 POST body——与 RAG v3 链同款；GET 查询串形态
     * （问题进浏览器历史与 access log）随前端同窗移除，消费方仅本前端。
     */
    @PostMapping(value = "/agent/v1/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@Valid @RequestBody AgentChatRequest chatRequest,
                           HttpServletRequest request) {
        String question = chatRequest.question();
        String conversationId = chatRequest.conversationId();
        SseEmitter emitter = new SseEmitter(agentProperties.getSseTimeoutMs());
        try {
            // 匿名试用配额（方案 A 硬前置，2026-09-13）：agent 链此前无 guard，
            // 引擎对普通用户放开后游客可绕过每日 3 次配额——与 RAGChatController 同款接线，
            // 仅 role=guest 生效，普通用户/admin 零开销放行
            anonymousTrialGuard.checkAndConsume(UserContext.get(), resolveClientIp(request));
        } catch (ClientException rejected) {
            // 同 RAGChatController：text/event-stream 下建流前抛 ClientException
            // 无法经媒体协商写出 JSON Result（落容器 500），拒绝改走 emitter error 事件，
            // 前端 useAgentStream 按 error 事件转结构化配额提示（超限弹登录窗）
            SseEmitterSender sender = new SseEmitterSender(emitter);
            sender.sendEvent(SSEEventType.ERROR.value(), Map.of("error", rejected.errorMessage));
            sender.complete();
            return emitter;
        }
        agentChatService.streamChat(question, conversationId, emitter);
        return emitter;
    }

    @PostMapping(value = "/agent/v1/chat/confirm", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter confirm(@RequestBody ConfirmRequest requestParam) {
        SseEmitter emitter = new SseEmitter(agentProperties.getSseTimeoutMs());
        agentChatService.confirmPendingTool(requestParam.conversationId(), requestParam.messageId(),
                requestParam.approved(), emitter);
        return emitter;
    }

    @PostMapping("/agent/v1/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        agentChatService.stopTask(taskId);
        return Results.success();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
    }
}
