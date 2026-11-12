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

import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolInputSummary;

import java.util.Map;

/**
 * 工具入参摘要捕获点（#486 取数决议）：挂在 AgentToolBodyTracer.trace 同层——
 * 四把工具（内置三件套+McpToolProxy）的 callAsync 都经该层，单点即覆盖全部工具调用。
 * flag 关态零采集：capture 在 digest 计算之前判闸，不执行任何摘要计算。
 * 装配沿 AgentToolBodyTracer.serializer 的静态配置模式（AgentAuditConfiguration 启动期一次性绑定）。
 */
public final class AgentToolInputAuditCapture {

    /**
     * 摘要策略（测试可注入计数桩验证关态零计算契约）
     */
    interface ToolInputDigester {

        ToolInputSummary summarize(Map<String, Object> input);
    }

    private static volatile boolean enabled = false;
    private static volatile ToolInputDigester digester = ToolInputDigest::summarize;

    private AgentToolInputAuditCapture() {
    }

    /**
     * 启动期装配（传 false/null 即关态复位，测试收尾也用它）
     */
    static void configure(boolean auditEnabled, ToolInputDigester configured) {
        enabled = auditEnabled;
        digester = configured == null ? ToolInputDigest::summarize : configured;
    }

    /**
     * 工具体入口处采集：摘要进 run 级事实源（facts），settle 点由审计 writer 读取。
     * 遮蔽短路（McpToolProxy 二线防御）发生在进入本层之前，短路行无摘要属预期
     */
    public static void capture(AgentToolExecutionFacts facts, String toolCallId, Map<String, Object> input) {
        if (!enabled || facts == null || input == null) {
            return;
        }
        facts.markInputSummary(toolCallId, digester.summarize(input));
    }
}
