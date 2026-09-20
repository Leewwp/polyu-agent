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

package com.nageoffer.ai.ragent.rag.controller.request;

import com.nageoffer.ai.ragent.framework.validation.ChatQuestion;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * RAG 对话 POST 请求体（L34）：问题全文随 body 携带——不再经 GET 查询串
 * 进浏览器历史与 nginx access log。字段与原 GET 参数一一对应。
 */
@Data
public class RAGChatRequest {

    /**
     * 用户问题全文
     */
    @NotNull
    @ChatQuestion
    private String question;

    /**
     * 会话 id（新会话首问可空）
     */
    private String conversationId;

    /**
     * 是否深思考
     */
    private Boolean deepThinking;
}
