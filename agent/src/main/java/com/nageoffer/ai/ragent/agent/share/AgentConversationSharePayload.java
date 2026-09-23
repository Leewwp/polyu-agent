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

package com.nageoffer.ai.ragent.agent.share;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话分享快照载荷（issue #124 统一机制后的粒度侧载荷形状）：序列化/反序列化全归
 * 本 adapter，统一 module 对 payload 零解析。必须是可变 Bean——hutool 不序列化 record
 * （落库丢字段判例）；contentVersion 来自 agent.share.content-version（粒度侧自持键）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentConversationSharePayload {

    /**
     * 会话标题快照（值复制；空白回退「新对话」）
     */
    private String title;

    /**
     * 白名单消息快照有序数组（role/content/createTime + v2 sources 投影）
     */
    private List<AgentShareSnapshotItem> messages;

    /**
     * 内容/知识版本标记（agent.share.content-version；v2=assistant 条目携带 sources 投影）
     */
    private String contentVersion;

    static String toJson(AgentConversationSharePayload payload) {
        return JSONUtil.toJsonStr(payload);
    }

    static AgentConversationSharePayload parse(String json) {
        if (StrUtil.isBlank(json)) {
            return new AgentConversationSharePayload();
        }
        return JSONUtil.toBean(json, AgentConversationSharePayload.class);
    }
}
