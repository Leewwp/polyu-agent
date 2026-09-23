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

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.nageoffer.ai.ragent.framework.convention.SourceRef;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 答案分享快照载荷（issue #124 统一机制后的粒度侧载荷形状）：序列化/反序列化全归
 * 本 adapter，统一 module 对 payload 零解析。必须是可变 Bean——hutool 不序列化 record
 * （落库丢字段判例）；contentVersion 来自 rag.share.content-version（粒度侧自持键）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnswerSharePayload {

    /**
     * 快照来源 assistant 消息 ID（内部溯源，公开投影不含）
     */
    private String messageId;

    /**
     * 问题快照（值复制自 reply_to_message_id 前驱 user 消息）
     */
    private String question;

    /**
     * 回答 Markdown 快照（值复制自 assistant 消息 content）
     */
    private String answerMd;

    /**
     * 结构化官方引用快照；null 时序列化省略，反序列化回 null
     */
    private List<SourceRef> citations;

    /**
     * 内容/知识版本标记（rag.share.content-version）
     */
    private String contentVersion;

    static String toJson(AnswerSharePayload payload) {
        return JSONUtil.toJsonStr(payload);
    }

    static AnswerSharePayload parse(String json) {
        if (StrUtil.isBlank(json)) {
            return new AnswerSharePayload();
        }
        return JSONUtil.toBean(json, AnswerSharePayload.class);
    }
}
