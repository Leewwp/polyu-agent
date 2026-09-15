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

package com.nageoffer.ai.ragent.infra.chat;

import com.google.gson.JsonObject;import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * MiniMax 聊天客户端（OpenAI 兼容端点）
 * <p>
 * MiniMax M 系默认开启思考。不显式声明 reasoning_split 时，思考内容会以 &lt;think&gt; 标签
 * 混入 content 直接透给用户；声明后思考内容改走 reasoning_content 字段，
 * 与本基座的流式思考通道（OpenAIStyleSseParser）对齐。
 */
@Slf4j
@Service
public class MiniMaxChatClient extends AbstractOpenAIStyleChatClient {

    @Override
    public String provider() {
        return ModelProvider.MINIMAX.getId();
    }

    @Override
    protected void customizeRequestBody(JsonObject body, ChatRequest request) {
        body.addProperty("reasoning_split", true);
        // MiniMax M 系思考默认开启（与基座"未指定即不思考"的语义相反），必须显式声明：
        // 未指定→disabled（主位时延/成本合同），显式思考→adaptive（deep-thinking 档）
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", Boolean.TRUE.equals(request.getThinking()) ? "adaptive" : "disabled");
        body.add("thinking", thinking);
    }

    @Override
    @RagTraceNode(name = "minimax-chat", type = "LLM_PROVIDER")
    public String chat(ChatRequest request, ModelTarget target) {
        return doChat(request, target);
    }

    @Override
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback, ModelTarget target) {
        return doStreamChat(request, callback, target);
    }
}
