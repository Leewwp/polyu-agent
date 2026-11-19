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

import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts.ToolInputSummary;
import com.nageoffer.ai.ragent.agent.tool.McpToolProxy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行构建纯函数契约（#486 验收 2）：blocks + run 元数据 → 审计行集。
 * 零 DB 零 LLM；幂等性与 decision 七值映射在此锁定（T2 复用词汇，勿增删）。
 */
class AgentToolCallAuditRowFactoryTest {

    private static final AgentToolCallAuditRowFactory.RunMetadata META =
            new AgentToolCallAuditRowFactory.RunMetadata("c-2002", "u-1001", "user", "m-5001");

    /** 规则拒绝的框架固定文案（T2 接入后出现在块 result 里，spike-2 jar 实证） */
    private static final String RULE_DENIAL_TEXT = "Permission denied by rules";

    private static AgentBlock tool(String status, String result, String batchId) {
        return AgentBlock.builder()
                .kind(AgentBlock.KIND_TOOL)
                .name("search_knowledge")
                .status(status)
                .result(result)
                .batchId(batchId)
                .toolCallId("call-" + status)
                .durationMs(1200L)
                .build();
    }

    @Test
    void eachToolBlockProducesExactlyOneRowAndConfirmBlockNone() {
        AgentBlock confirm = AgentBlock.builder()
                .kind(AgentBlock.KIND_CONFIRM)
                .status("approved")
                .build();
        List<AgentBlock> blocks = List.of(tool("done", "命中", "9001-0"), confirm, tool("failed", "炸了", "9001-1"));

        List<AgentToolCallAuditRow> rows = AgentToolCallAuditRowFactory.rowsOf(META, blocks, id -> null);

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.conversationId()).isEqualTo("c-2002");
            assertThat(row.userId()).isEqualTo("u-1001");
            assertThat(row.role()).isEqualTo("user");
            assertThat(row.messageId()).isEqualTo("m-5001");
            assertThat(row.toolName()).isEqualTo("search_knowledge");
        });
    }

    @Test
    void decisionVocabularyMapsEveryStatusAndBothDenialTexts() {
        List<AgentBlock> blocks = List.of(
                tool("done", "命中", null),
                // 遮蔽二线防御短路：failed + McpToolProxy 固定文案
                tool("failed", McpToolProxy.MASKED_TOOL_MESSAGE, null),
                tool("failed", "知识库检索异常，请稍后重试", null),
                // T2 接入后的规则拒绝：denied + 框架固定文案
                tool("denied", RULE_DENIAL_TEXT, null),
                // 用户拒卡：denied 且非规则文案（本地库实证形态为无 result，此处两种都须归 ASK_DENIED）
                tool("denied", null, null),
                tool("denied", "Permission denied by user", null),
                // 卡批后块停留 awaiting 的历史形态（spike-1 §1 实证）
                tool("awaiting", null, null),
                tool("interrupted", null, null));

        List<String> decisions = AgentToolCallAuditRowFactory.rowsOf(META, blocks, id -> null)
                .stream().map(AgentToolCallAuditRow::decision).toList();

        assertThat(decisions).containsExactly(
                "ALLOW", "MASKED", "ERROR", "RULE_DENY", "ASK_DENIED", "ASK_DENIED", "ASK_APPROVED", "INTERRUPTED");
    }

    @Test
    void unobservedTerminalStatusesFallBackToInterruptedAndDoneIsExplicitlyAllow() {
        List<AgentBlock> blocks = List.of(
                tool("pending", null, null),
                tool("running", "断在半路", null),
                tool("done", "命中", null));

        List<String> decisions = AgentToolCallAuditRowFactory.rowsOf(META, blocks, id -> null)
                .stream().map(AgentToolCallAuditRow::decision).toList();

        // 兜底语义锁定（对齐 of() 注释）：pending/running 等未观测终态=INTERRUPTED，
        // 不再笼统落 ALLOW；done 显式判 ALLOW（正常完成态唯一入口）
        assertThat(decisions).containsExactly("INTERRUPTED", "INTERRUPTED", "ALLOW");
    }

    @Test
    void maskedDetectionToleratesSurroundingWhitespaceLikeRuleDenial() {
        // MASKED 判别与 RULE_DENY 同口径：固定文案比对先 trim，首尾空白不改变归类
        List<AgentBlock> blocks = List.of(
                tool("failed", "  \n" + McpToolProxy.MASKED_TOOL_MESSAGE + "\t", null),
                tool("failed", "  其他失败文案  ", null));

        List<String> decisions = AgentToolCallAuditRowFactory.rowsOf(META, blocks, id -> null)
                .stream().map(AgentToolCallAuditRow::decision).toList();

        assertThat(decisions).containsExactly("MASKED", "ERROR");
    }

    @Test
    void statusColumnKeepsBlockStatusVerbatim() {
        List<AgentBlock> blocks = List.of(tool("awaiting", null, null), tool("done", "ok", null));

        List<String> statuses = AgentToolCallAuditRowFactory.rowsOf(META, blocks, id -> null)
                .stream().map(AgentToolCallAuditRow::status).toList();

        assertThat(statuses).containsExactly("awaiting", "done");
    }

    @Test
    void runIdIsParsedFromBatchIdPrefix() {
        List<AgentBlock> blocks = List.of(tool("done", "ok", "2116899171269521409-0"));

        List<AgentToolCallAuditRow> rows = AgentToolCallAuditRowFactory.rowsOf(META, blocks, id -> null);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.runId()).isEqualTo("2116899171269521409");
            assertThat(row.batchId()).isEqualTo("2116899171269521409-0");
        });
        // 无批次号的块（拒卡补建等）runId 留空，不造数
        assertThat(AgentToolCallAuditRowFactory.rowsOf(META, List.of(tool("denied", null, null)), id -> null))
                .singleElement().satisfies(row -> assertThat(row.runId()).isNull());
    }

    @Test
    void inputSummaryResolvesByToolCallIdAndCarriesDuration() {
        List<AgentBlock> blocks = List.of(tool("done", "ok", null));
        Function<String, ToolInputSummary> summaries =
                id -> "call-done".equals(id)
                        ? new ToolInputSummary("abc256", "{\"q\":\"x\"")
                        : null;

        List<AgentToolCallAuditRow> rows = AgentToolCallAuditRowFactory.rowsOf(META, blocks, summaries);

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.inputDigest()).isEqualTo("abc256");
            assertThat(row.inputExcerpt()).isEqualTo("{\"q\":\"x\"");
            assertThat(row.durationMs()).isEqualTo(1200L);
        });
    }

    @Test
    void recomputingSameInputYieldsEqualRowSet() {
        List<AgentBlock> blocks = List.of(
                tool("done", "ok", "9001-0"),
                AgentBlock.builder().kind(AgentBlock.KIND_ANSWER).text("答").build(),
                tool("awaiting", null, null));

        List<AgentToolCallAuditRow> first = AgentToolCallAuditRowFactory.rowsOf(META, blocks, id -> null);
        List<AgentToolCallAuditRow> second = AgentToolCallAuditRowFactory.rowsOf(META, blocks, id -> null);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void nullBlocksYieldEmptyRows() {
        assertThat(AgentToolCallAuditRowFactory.rowsOf(META, null, id -> null)).isEmpty();
    }

    @Test
    void mapBasedApiCoversNestedInputSerialization() {
        // 与取数点捕获时的序列化一致：canonical JSON 为 TreeMap 键序紧凑串
        Map<String, Object> input = Map.of("query", "polyu library", "topK", 5);
        ToolInputSummary summary = ToolInputDigest.summarize(input);

        assertThat(summary.excerpt()).isEqualTo("{\"query\":\"polyu library\",\"topK\":5}");
    }
}
