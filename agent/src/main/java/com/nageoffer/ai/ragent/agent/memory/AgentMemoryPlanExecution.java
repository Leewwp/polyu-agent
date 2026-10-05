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

/**
 * 变更计划的执行结局：只有 APPLIED/REPLAYED 动过记忆，REFUSED 一律未执行任何变更
 * resultJson 是落进台账 plan_result_json 的那份事实源，重复执行直接回放它，不二次提交
 */
@Slf4j
public record AgentMemoryPlanExecution(Outcome outcome, String detail, String resultJson) {

    private static final ObjectMapper CODEC = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    public enum Outcome {

        /**
         * 本次事务提交了计划变更，结果已随同一事务写入台账
         */
        APPLIED,

        /**
         * 计划此前已执行，本次读原结果回放，未产生第二次变更
         */
        REPLAYED,

        /**
         * 未执行：计划不存在/非本人/未获批准/已拒绝/已过期/已失效/调用绑定不符
         */
        REFUSED
    }

    public boolean executed() {
        return outcome != Outcome.REFUSED;
    }

    public static AgentMemoryPlanExecution applied(String detail, String resultJson) {
        return new AgentMemoryPlanExecution(Outcome.APPLIED, detail, resultJson);
    }

    public static AgentMemoryPlanExecution replayed(String resultJson) {
        return new AgentMemoryPlanExecution(Outcome.REPLAYED, summaryOf(resultJson), resultJson);
    }

    public static AgentMemoryPlanExecution refused(String reason) {
        return new AgentMemoryPlanExecution(Outcome.REFUSED, reason, null);
    }

    /**
     * 执行结果的落库形态：summary 供回放直读，计数供审计
     */
    public static String resultJsonOf(String summary, int applied, boolean cleared, int clearedItems) {
        try {
            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("summary", summary);
            result.put("applied", applied);
            result.put("cleared", cleared);
            result.put("clearedItems", clearedItems);
            return CODEC.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("记忆计划结果序列化失败", e);
        }
    }

    /**
     * 重复执行读原结果：summary 在冻结执行时一并写进结果 JSON，回放不用重算
     */
    public static String summaryOf(String resultJson) {
        try {
            Object summary = CODEC.readValue(resultJson, java.util.Map.class).get("summary");
            return summary == null ? "该计划此前已执行" : summary.toString();
        } catch (Exception e) {
            log.warn("记忆计划结果回放解析失败, 退通用文案", e);
            return "该计划此前已执行";
        }
    }
}
