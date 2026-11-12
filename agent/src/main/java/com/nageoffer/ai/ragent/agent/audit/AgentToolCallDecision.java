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

import com.nageoffer.ai.ragent.agent.enums.AgentToolStatus;
import com.nageoffer.ai.ragent.agent.tool.McpToolProxy;

/**
 * 工具调用审计 decision 词汇（#486 T1 定义，T2 权限表复用，勿增删）。
 * 七值按（块 status，result 文案）映射；块 status 原词另落审计行 status 列。
 * <ul>
 *   <li>done→ALLOW</li>
 *   <li>failed+遮蔽固定文案→MASKED（一线防御下模型通常调不到，二线短路的留痕形态）</li>
 *   <li>failed（其余）→ERROR</li>
 *   <li>denied+框架规则文案→RULE_DENY（T2 规则引擎接入后出现，本票只定义词汇与判别）</li>
 *   <li>denied（其余=用户拒卡）→ASK_DENIED</li>
 *   <li>awaiting→ASK_APPROVED（卡批后块停留 awaiting 的历史形态，spike-1 §1 实证）</li>
 *   <li>interrupted→INTERRUPTED</li>
 * </ul>
 */
public enum AgentToolCallDecision {

    ALLOW,
    MASKED,
    ERROR,
    RULE_DENY,
    ASK_DENIED,
    ASK_APPROVED,
    INTERRUPTED;

    /**
     * 规则拒绝的框架固定文案（agentscope writeAutoDeniedResults，spike-2 jar 实证）；
     * 与用户拒卡文案 "Permission denied by user"（AgentConfirmDenialMiddleware 识别）天然可区分
     */
    public static final String RULE_DENIAL_TEXT = "Permission denied by rules";

    /**
     * 未观测到终态的块（pending/running 等收尾漏网形态）兜底为 INTERRUPTED，不造第三种语义
     */
    public static AgentToolCallDecision of(String blockStatus, String resultText) {
        if (AgentToolStatus.AWAITING.value().equals(blockStatus)) {
            return ASK_APPROVED;
        }
        if (AgentToolStatus.DENIED.value().equals(blockStatus)) {
            return RULE_DENIAL_TEXT.equals(resultText == null ? "" : resultText.trim())
                    ? RULE_DENY
                    : ASK_DENIED;
        }
        if (AgentToolStatus.FAILED.value().equals(blockStatus)) {
            // 与 RULE_DENY 同判别口径：固定文案比对前先 trim，result 首尾空白不改变归类
            return McpToolProxy.MASKED_TOOL_MESSAGE.equals(resultText == null ? "" : resultText.trim())
                    ? MASKED
                    : ERROR;
        }
        if (AgentToolStatus.INTERRUPTED.value().equals(blockStatus)) {
            return INTERRUPTED;
        }
        if (AgentToolStatus.DONE.value().equals(blockStatus)) {
            return ALLOW;
        }
        // 兜底（pending/running 等未观测到终态的收尾漏网形态）：INTERRUPTED，不造第三种语义
        return INTERRUPTED;
    }
}
