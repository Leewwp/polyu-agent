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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolInputSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 审计行构建幂等纯函数（#486）：blocks + run 元数据 → 行集，键=messageId+块序。
 * 同一消息重算（拒卡改写后的补算）得到相同键集，writer 按键 upsert 覆盖。
 * runId 从 batchId 前缀解析（格式 "{runId}-{batchIndex}"，facts.beginBatch 唯一产生处）。
 */
public final class AgentToolCallAuditRowFactory {

    private AgentToolCallAuditRowFactory() {
    }

    /**
     * run 级元数据：请求线程（startRun/startConfirmRun）采集，经 RunScope 携带到 settle 点；
     * role 为拒绝/落库时刻 UserContext 不在 reactor 线程而设的冗余列（spike-2 §4）
     */
    public record RunMetadata(String conversationId, String userId, String role, String messageId) {
    }

    /**
     * @param inputSummaryResolver 入参摘要取数：settle 路径按 toolCallId 读 facts；重算路径传 id -> null
     *                             （upsert 对 null 摘要保持库内原值，不冲掉首写摘要）
     */
    public static List<AgentToolCallAuditRow> rowsOf(RunMetadata metadata, List<AgentBlock> blocks,
                                                     Function<String, ToolInputSummary> inputSummaryResolver) {
        if (metadata == null || blocks == null || blocks.isEmpty()) {
            return List.of();
        }
        List<AgentToolCallAuditRow> rows = new ArrayList<>();
        for (int index = 0; index < blocks.size(); index++) {
            AgentBlock block = blocks.get(index);
            if (block == null || !AgentBlock.KIND_TOOL.equals(block.getKind())) {
                continue;
            }
            ToolInputSummary summary = summaryOf(inputSummaryResolver, block.getToolCallId());
            rows.add(new AgentToolCallAuditRow(
                    metadata.conversationId(),
                    metadata.userId(),
                    metadata.role(),
                    metadata.messageId(),
                    index,
                    runIdOf(block.getBatchId()),
                    StrUtil.blankToDefault(block.getName(), "unknown"),
                    StrUtil.blankToDefault(block.getToolCallId(), null),
                    StrUtil.blankToDefault(block.getBatchId(), null),
                    AgentToolCallDecision.of(block.getStatus(), block.getResult()).name(),
                    StrUtil.blankToDefault(block.getStatus(), "unknown"),
                    summary == null ? null : summary.digest(),
                    summary == null ? null : summary.excerpt(),
                    block.getDurationMs()));
        }
        return rows;
    }

    private static ToolInputSummary summaryOf(Function<String, ToolInputSummary> resolver, String toolCallId) {
        if (resolver == null) {
            return null;
        }
        try {
            return resolver.apply(toolCallId);
        } catch (RuntimeException e) {
            // 摘要缺位不该让审计行流产：行照出，摘要留空
            return null;
        }
    }

    /**
     * batchId = runId + "-" + batchIndex（数字 runId 不含连字符），切掉末段即 runId；无批次号返回 null
     */
    static String runIdOf(String batchId) {
        if (StrUtil.isBlank(batchId)) {
            return null;
        }
        int split = batchId.lastIndexOf('-');
        return split > 0 ? batchId.substring(0, split) : null;
    }
}
