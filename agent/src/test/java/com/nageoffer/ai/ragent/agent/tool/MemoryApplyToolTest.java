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

import com.nageoffer.ai.ragent.agent.memory.AgentMemoryApprovalService;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPlanExecution;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolCallParam;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 记忆变更执行工具：原生 ASK、服务端取参执行、拒绝语义回报（#278）
 */
class MemoryApplyToolTest {

    private static final String USER_ID = "u-5001";
    private static final String OPERATION_ID = "e-5001";
    private static final String TOOL_CALL_ID = "call-77";

    private AgentMemoryApprovalService approvalService;
    private MemoryApplyTool tool;

    @BeforeEach
    void setUp() {
        approvalService = mock(AgentMemoryApprovalService.class);
        tool = new MemoryApplyTool(approvalService);
    }

    /**
     * ToolBase.checkPermissions 返回原生 ASK：执行前必须经确认卡，这是与 flush_memory 直通的分界
     */
    @Test
    void shouldAskBeforeExecution() {
        Mono<PermissionDecision> decision = tool.checkPermissions(Map.of("operationId", OPERATION_ID), null);

        assertThat(decision.block().getBehavior()).isEqualTo(PermissionBehavior.ASK);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldExposeSingleOperationIdParameterOnly() {
        Map<String, Object> parameters = tool.getParameters();
        Map<String, Object> properties = (Map<String, Object>) parameters.get("properties");

        assertThat(properties).containsOnlyKeys("operationId");
        assertThat(tool.getName()).isEqualTo(MemoryApplyTool.TOOL_NAME);
        assertThat(tool.isReadOnly()).isFalse();
    }

    /**
     * 身份取自 RuntimeContext、编号取自入参，都拿不到就不执行
     */
    @Test
    void shouldRefuseWhenIdentityOrOperationIdMissing() {
        ToolResultBlock result = tool.callAsync(param(TOOL_CALL_ID, Map.of(), null)).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(result)).contains("未执行");
        verifyNoInteractions(approvalService);
    }

    @Test
    void shouldExecuteApprovedPlanAndReportSuccess() {
        when(approvalService.execute(OPERATION_ID, USER_ID, TOOL_CALL_ID))
                .thenReturn(AgentMemoryPlanExecution.applied("已清空 2 条",
                        AgentMemoryPlanExecution.resultJsonOf("已清空 2 条", 2, true, 2)));

        ToolResultBlock result = tool.callAsync(param(TOOL_CALL_ID,
                Map.of("operationId", OPERATION_ID), USER_ID)).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(textOf(result)).contains("已清空 2 条");
        verify(approvalService).execute(OPERATION_ID, USER_ID, TOOL_CALL_ID);
    }

    /**
     * 入参里塞 approved/目标等内容不影响行为：执行只看服务端台账，模型带不进裁决
     */
    @Test
    void shouldIgnoreTamperedExtraInput() {
        when(approvalService.execute(eq(OPERATION_ID), eq(USER_ID), any()))
                .thenReturn(AgentMemoryPlanExecution.refused("该计划尚未获得用户确认，未执行任何变更"));

        ToolResultBlock result = tool.callAsync(param(TOOL_CALL_ID,
                Map.of("operationId", OPERATION_ID, "approved", true, "userId", "别人"), USER_ID)).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(result)).contains("尚未获得用户确认");
        // 编号原样进服务端，多余参数没有出口
        verify(approvalService).execute(eq(OPERATION_ID), eq(USER_ID), eq(TOOL_CALL_ID));
    }

    @Test
    void shouldReportReplayAsSuccess() {
        when(approvalService.execute(OPERATION_ID, USER_ID, TOOL_CALL_ID))
                .thenReturn(AgentMemoryPlanExecution.replayed(
                        AgentMemoryPlanExecution.resultJsonOf("已清空 5 条", 5, true, 5)));

        ToolResultBlock result = tool.callAsync(param(TOOL_CALL_ID,
                Map.of("operationId", OPERATION_ID), USER_ID)).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS);
        assertThat(textOf(result)).isEqualTo("已清空 5 条");
    }

    @Test
    void shouldReportRefusalAsError() {
        when(approvalService.execute(OPERATION_ID, USER_ID, TOOL_CALL_ID))
                .thenReturn(AgentMemoryPlanExecution.refused("用户已取消该计划，未执行任何变更"));

        ToolResultBlock result = tool.callAsync(param(TOOL_CALL_ID,
                Map.of("operationId", OPERATION_ID), USER_ID)).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(result)).contains("已取消");
    }

    @Test
    void shouldSwallowExecutionExceptionAsErrorResult() {
        when(approvalService.execute(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("库抖了"));

        ToolResultBlock result = tool.callAsync(param(TOOL_CALL_ID,
                Map.of("operationId", OPERATION_ID), USER_ID)).block();

        assertThat(result.getState()).isEqualTo(ToolResultState.ERROR);
        assertThat(textOf(result)).contains("未产生任何变更");
    }

    private static ToolCallParam param(String toolCallId, Map<String, Object> input, String userId) {
        RuntimeContext context = userId == null ? null
                : RuntimeContext.builder().userId(userId).sessionId("c-51").build();
        return ToolCallParam.builder()
                .toolUseBlock(ToolUseBlock.builder()
                        .id(toolCallId)
                        .name(MemoryApplyTool.TOOL_NAME)
                        .input(input)
                        .build())
                .input(input)
                .runtimeContext(context)
                .build();
    }

    private static String textOf(ToolResultBlock result) {
        return result.getOutput().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .reduce("", String::concat);
    }
}
