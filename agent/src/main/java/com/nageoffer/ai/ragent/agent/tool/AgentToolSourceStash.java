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

package com.nageoffer.ai.ragent.agent.tool;

import com.nageoffer.ai.ragent.agent.dto.AgentBlockSource;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 工具执行线程 -> 流事件桥的来源旁路：agentscope ToolResultBlock 只承载文本，
 * 结构化来源按 toolCallId 暂存到这里，onToolEnd 收尾工具块时取走挂到 AgentBlock.sources。
 * 键为全局唯一雪花 id，正常毫秒级被取走；上界触顶整表清空兜底，不引入逐出序
 */
public final class AgentToolSourceStash {

    /**
     * 触顶清空阈值：按异常中断缓慢积压的兜底量级，正常流量到不了这里
     */
    private static final int MAX_ENTRIES = 512;

    private static final ConcurrentMap<String, List<AgentBlockSource>> STASH = new ConcurrentHashMap<>();

    private AgentToolSourceStash() {
    }

    public static void put(String toolCallId, List<AgentBlockSource> sources) {
        if (toolCallId == null || toolCallId.isBlank() || sources == null || sources.isEmpty()) {
            return;
        }
        if (STASH.size() >= MAX_ENTRIES) {
            STASH.clear();
        }
        STASH.put(toolCallId, sources);
    }

    /**
     * 取出即移除：来源只被消费一次，取走的挂块，没取走的（流异常中断）就地作废
     */
    public static List<AgentBlockSource> take(String toolCallId) {
        if (toolCallId == null || toolCallId.isBlank()) {
            return null;
        }
        List<AgentBlockSource> sources = STASH.remove(toolCallId);
        return sources == null || sources.isEmpty() ? null : sources;
    }

    /**
     * 测试与兜底清场用
     */
    public static void reset() {
        STASH.clear();
    }
}
