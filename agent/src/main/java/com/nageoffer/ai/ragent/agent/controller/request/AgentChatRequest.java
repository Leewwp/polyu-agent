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

package com.nageoffer.ai.ragent.agent.controller.request;

import com.nageoffer.ai.ragent.framework.validation.ChatQuestion;
import jakarta.validation.constraints.NotNull;

/**
 * Agent 对话 POST 请求体（L34/#95 扩展面）：问题全文随 body 携带——
 * 此前 GET 查询串形态与 RAG v3 链同病（问题进浏览器历史与 nginx access log），
 * 与隐私声明口径不一致；消费方仅本前端，GET 变体随前端同窗移除。
 */
public record AgentChatRequest(
        @NotNull @ChatQuestion String question,
        String conversationId) {
}
