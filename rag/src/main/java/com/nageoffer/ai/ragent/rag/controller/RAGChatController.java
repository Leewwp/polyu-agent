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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.rag.config.RAGDefaultProperties;
import com.nageoffer.ai.ragent.rag.controller.request.RAGChatRequest;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.rag.service.AnonymousTrialGuard;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
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
 * RAG 对话控制器
 * 提供流式问答与任务取消接口
 */
@RestController
@RequiredArgsConstructor
public class RAGChatController {

    private final RAGChatService ragChatService;
    private final RAGDefaultProperties ragDefaultProperties;
    private final AnonymousTrialGuard anonymousTrialGuard;

    /**
     * 发起 SSE 流式对话
     * <p>
     * L34（#95）：问题全文改经 POST body 携带——原先 GET 查询串里的 question
     * 会进浏览器历史与 nginx access log，与隐私声明口径不一致；消费方仅本前端，
     * GET 变体已随前端同窗移除（对齐 agent 链 /agent/v1/chat 的 POST 形态）。
     */
    @IdempotentSubmit(
            key = "T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    @PostMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chat(@Valid @RequestBody RAGChatRequest chatRequest,
                           HttpServletRequest request) {
        String question = chatRequest.getQuestion();
        String conversationId = chatRequest.getConversationId();
        boolean deepThinking = Boolean.TRUE.equals(chatRequest.getDeepThinking());
        SseEmitter emitter = new SseEmitter(ragDefaultProperties.getSseTimeoutMs());
        try {
            // 匿名试用配额：仅对 role=guest 会话生效，普通用户零开销放行
            anonymousTrialGuard.checkAndConsume(UserContext.get(), resolveClientIp(request));
        } catch (ClientException rejected) {
            // 本端点 produce=text/event-stream，建流前抛 ClientException 时 GlobalExceptionHandler
            // 的 JSON Result 无法通过媒体协商写出（落到容器 500，前端只见「SSE 请求失败」）——
            // 拒绝改走 emitter error 事件，前端 useStreamResponse 已按该事件转结构化配额提示
            SseEmitterSender sender = new SseEmitterSender(emitter);
            sender.sendEvent(SSEEventType.ERROR.value(), Map.of("error", rejected.errorMessage));
            sender.complete();
            return emitter;
        }
        ragChatService.streamChat(question, conversationId, deepThinking, emitter);
        return emitter;
    }

    /**
     * 停止指定任务
     */
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/stop")
    public Result<Void> stop(@RequestParam String taskId) {
        ragChatService.stopTask(taskId);
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
