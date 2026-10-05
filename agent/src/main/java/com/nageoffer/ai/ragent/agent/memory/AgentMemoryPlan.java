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

package com.nageoffer.ai.ragent.agent.memory;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * 冻结的记忆变更计划：受审批次（含 RETRACT/CLEAR）不落库，整批快照进台账等待用户裁决
 * 冻结之后执行只读这一份，不再让 Judge 重选范围；改范围只能重新发起生成新计划
 * 展示用的目标正文与消息范围一并冻结，卡片展示的是用户批准过的那一份
 */
@Slf4j
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentMemoryPlan(
        String operationId,
        String userId,
        String conversationId,
        String fromMessageId,
        String toMessageId,
        int pendingMessageCount,
        int activeItemCount,
        long expectedRevision,
        String expectedWatermark,
        String triggerType,
        List<AgentMemoryDecision> decisions,
        Map<String, String> targetContents) {

    private static final ObjectMapper CODEC = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /**
     * 受审动作：撤回单条或清空全部必须先经用户确认，其余动作沿普通提交流程
     */
    public static boolean requiresApproval(List<AgentMemoryDecision> decisions) {
        return decisions.stream().anyMatch(decision -> decision.action() == AgentMemoryDecision.Action.RETRACT
                || decision.action() == AgentMemoryDecision.Action.CLEAR);
    }

    public boolean containsClear() {
        return AgentMemoryDecision.containsClear(decisions);
    }

    public long retractCount() {
        return decisions.stream()
                .filter(decision -> decision.action() == AgentMemoryDecision.Action.RETRACT)
                .count();
    }

    public List<AgentMemoryDecision> additions() {
        return decisions.stream().filter(AgentMemoryDecision::introducesContent).toList();
    }

    public String toJson() {
        try {
            return CODEC.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("记忆变更计划序列化失败", e);
        }
    }

    public static AgentMemoryPlan fromJson(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return CODEC.readValue(payload, AgentMemoryPlan.class);
        } catch (Exception e) {
            log.error("记忆变更计划解析失败, 按无计划处理", e);
            return null;
        }
    }
}
