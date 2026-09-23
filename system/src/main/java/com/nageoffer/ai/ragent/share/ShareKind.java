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

package com.nageoffer.ai.ragent.share;

/**
 * 分享快照粒度（issue #124 统一机制）：答案分享与会话分享是同一机制的两种粒度，
 * kind 只是 t_share_snapshot 的判别列，载荷形状由各粒度 adapter 自持——module 对 payload 零解析
 */
public enum ShareKind {

    /**
     * 单条问答快照（原 t_answer_share；载荷=messageId/question/answerMd/citations/contentVersion）
     */
    ANSWER("answer"),

    /**
     * agent 会话快照（原 t_agent_conversation_share；载荷=title/messages/contentVersion）
     */
    CONVERSATION("conversation");

    private final String code;

    ShareKind(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
