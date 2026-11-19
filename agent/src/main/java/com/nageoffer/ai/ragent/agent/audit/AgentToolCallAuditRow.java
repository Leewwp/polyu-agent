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

package com.nageoffer.ai.ragent.agent.audit;

/**
 * t_agent_tool_call 的一行（#486）：键 = messageId + blockIndex（幂等 upsert 冲突键）；
 * id 由 writer 落库时生成，不进纯函数产物——同输入重算行集恒相等
 */
public record AgentToolCallAuditRow(
        String conversationId,
        String userId,
        String role,
        String messageId,
        int blockIndex,
        String runId,
        String toolName,
        String toolCallId,
        String batchId,
        String decision,
        String status,
        String inputDigest,
        String inputExcerpt,
        Long durationMs) {
}
