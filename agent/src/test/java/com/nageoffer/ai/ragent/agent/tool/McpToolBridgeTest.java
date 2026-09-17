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

import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.rag.core.mcp.McpCallMeta;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpToolBridgeTest {

    private static final String TOOL_ID = "query_order";

    private static final String USER_ID = "2001";

    private McpToolExecutor executor;
    private McpToolBridge bridge;

    @BeforeEach
    void setUp() {
        executor = mock(McpToolExecutor.class);
        when(executor.getToolDefinition()).thenReturn(Tool.builder()
                .name(TOOL_ID)
                .description("查询订单")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build());
        when(executor.execute(anyMap(), anyMap())).thenReturn(CallToolResult.builder()
                .content(List.of(new TextContent("订单查询结果")))
                .isError(false)
                .build());
        bridge = new McpToolBridge(McpToolBinding.of(TOOL_ID, "订单查询", "查询订单", false, executor));
    }

    /**
     * 身份必须随调用一起过去，否则服务端只能按工具入参认人，谁都能以谁的名义查
     */
    @Test
    void shouldPassLoginUserThroughMeta() {
        call(RuntimeContext.builder().userId(USER_ID).sessionId("3001").build());

        assertThat(capturedMeta()).containsEntry(McpCallMeta.USER_ID, USER_ID);
    }

    /**
     * 取不到身份就发空表，把「没有身份」如实交给服务端去回绝，不在这里编一个出来
     */
    @Test
    void shouldPassEmptyMetaWithoutIdentity() {
        call(RuntimeContext.builder().build());

        assertThat(capturedMeta()).isEmpty();
    }

    /**
     * 单参重载是 default 实现，走到它就说明桥接层没把身份带上
     */
    @Test
    void shouldNeverFallBackToSingleArgumentOverload() {
        call(RuntimeContext.builder().userId(USER_ID).build());

        verify(executor, never()).execute(anyMap());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedMeta() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(executor).execute(any(), captor.capture());
        return captor.getValue();
    }

    private void call(RuntimeContext runtimeContext) {
        bridge.callAsync(ToolCallParam.builder()
                        .toolUseBlock(ToolUseBlock.builder()
                                .id("call-1")
                                .name(TOOL_ID)
                                .input(Map.of())
                                .build())
                        .input(Map.of("orderNo", "88231"))
                        .runtimeContext(runtimeContext)
                        .build())
                .block();
    }
}
