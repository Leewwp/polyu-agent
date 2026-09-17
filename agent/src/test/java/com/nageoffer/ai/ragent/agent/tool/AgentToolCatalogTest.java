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

import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.Toolkit;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolCatalogTest {

    @Test
    void shouldRegisterKnowledgeAndIntentTreeConfiguredMcpToolsOnly() {
        IntentNodeRegistry intentNodeRegistry = mock(IntentNodeRegistry.class);
        McpToolRegistry mcpToolRegistry = mock(McpToolRegistry.class);
        when(intentNodeRegistry.listMcpToolNodes()).thenReturn(List.of(
                mcpNode("sales", "销售查询", "查询实时销售数据", "sales_query"),
                mcpNode("missing", "缺失工具", "当前没有执行器", "missing_query")));
        when(mcpToolRegistry.listAllExecutors()).thenReturn(List.of(
                executor("sales_query", "MCP 服务端描述"),
                executor("unconfigured_query", "未配置到意图树")));
        AgentPromptResolver agentPromptResolver = mock(AgentPromptResolver.class);
        when(agentPromptResolver.resolve(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION))
                .thenReturn("当前 Agent 的知识库工具描述");

        AgentToolCatalog catalog = new AgentToolCatalog(
                mock(KnowledgeSearchFacade.class),
                intentNodeRegistry,
                mcpToolRegistry,
                agentPromptResolver,
                memoryProperties(false),
                mock(AgentMemoryPipeline.class),
                mock(AgentSkillRegistry.class));

        AgentToolCatalog.ResolvedCatalog resolved = catalog.resolve();
        Toolkit toolkit = catalog.buildToolkit(resolved);

        assertThat(toolkit.getToolNames())
                .containsExactlyInAnyOrder(KnowledgeSearchTool.TOOL_NAME, "sales_query");
        assertThat(toolkit.getTool(KnowledgeSearchTool.TOOL_NAME).getDescription())
                .isEqualTo("当前 Agent 的知识库工具描述");
        assertThat(toolkit.getTool("sales_query").getDescription()).isEqualTo("查询实时销售数据");
        assertThat(resolved.displayNameOf("sales_query")).isEqualTo("销售查询");
        assertThat(catalog.mcpToolCount()).isEqualTo(1);
        assertThat(resolved.fingerprint().knowledgeToolDescription())
                .isEqualTo("当前 Agent 的知识库工具描述");
        assertThat(resolved.fingerprint().mcpTools())
                .singleElement()
                .satisfies(tool -> assertThat(tool.toolId()).isEqualTo("sales_query"));
    }

    @Test
    void shouldTreatMcpToolWithoutReadOnlyHintAsWritable() {
        Toolkit toolkit = buildToolkitFor(
                executor("no_annotation_query", "无 annotation", null),
                executor("blank_hint_query", "有 annotation 但未声明 readOnlyHint",
                        new ToolAnnotations(null, null, null, null, null, null)));

        assertThat(toolkit.getTool("no_annotation_query").isReadOnly()).isFalse();
        assertThat(toolkit.getTool("blank_hint_query").isReadOnly()).isFalse();
        // 知识库工具的只读是它自己声明的，不随 MCP 透传变化
        // 注意这里只验 getter：它不是 ToolBase，声明不会进入 ReAct 的权限决策，别当成一道执行期保护
        assertThat(toolkit.getTool(KnowledgeSearchTool.TOOL_NAME).isReadOnly()).isTrue();
    }

    @Test
    void shouldPassThroughMcpReadOnlyHint() {
        Toolkit toolkit = buildToolkitFor(
                executor("read_query", "只读工具", readOnlyHint(true)),
                executor("write_query", "写工具", readOnlyHint(false)));

        assertThat(toolkit.getTool("read_query").isReadOnly()).isTrue();
        assertThat(toolkit.getTool("write_query").isReadOnly()).isFalse();
    }

    /**
     * 工具进指纹是重建时机的前提：开关翻面不改指纹，实例就会一直挂着旧目录
     */
    @Test
    void shouldRegisterMemoryFlushToolAndCountItIntoFingerprint() {
        AgentToolCatalog catalog = catalogWithMemory(true, "需要记住或忘掉用户信息时调用");
        AgentToolCatalog.ResolvedCatalog resolved = catalog.resolve();
        Toolkit toolkit = catalog.buildToolkit(resolved);

        assertThat(toolkit.getToolNames())
                .containsExactlyInAnyOrder(KnowledgeSearchTool.TOOL_NAME, MemoryFlushTool.TOOL_NAME);
        assertThat(toolkit.getTool(MemoryFlushTool.TOOL_NAME).getDescription())
                .isEqualTo("需要记住或忘掉用户信息时调用");
        // 无参：给了参数就等于把内容写入权交给模型
        assertThat(toolkit.getTool(MemoryFlushTool.TOOL_NAME).getParameters())
                .containsEntry("properties", Map.of());
        assertThat(toolkit.getTool(MemoryFlushTool.TOOL_NAME).isReadOnly()).isFalse();
        assertThat(resolved.displayNameOf(MemoryFlushTool.TOOL_NAME)).isEqualTo(MemoryFlushTool.DISPLAY_NAME);
        assertThat(resolved.fingerprint().memoryToolDescription()).isEqualTo("需要记住或忘掉用户信息时调用");
        assertThat(resolved.fingerprint())
                .isNotEqualTo(catalogWithMemory(false, "需要记住或忘掉用户信息时调用").resolve().fingerprint());
    }

    /**
     * 槽位缺失只卸掉这把工具，不该把整个对话一起带走
     */
    @Test
    void shouldSkipMemoryFlushToolWhenSlotBlank() {
        AgentToolCatalog catalog = catalogWithMemory(true, "  ");
        AgentToolCatalog.ResolvedCatalog resolved = catalog.resolve();

        assertThat(catalog.buildToolkit(resolved).getToolNames())
                .containsExactly(KnowledgeSearchTool.TOOL_NAME);
        assertThat(resolved.fingerprint().memoryToolDescription()).isNull();
        assertThat(resolved.displayNameOf(MemoryFlushTool.TOOL_NAME)).isEqualTo(MemoryFlushTool.TOOL_NAME);
    }

    /**
     * 确认开关是人工勾的，会漏；工具作者写死的 readOnlyHint=false 不会
     */
    @Test
    void shouldAskConfirmForDeclaredWriteToolWhenIntentNodeMisses() {
        Toolkit toolkit = buildToolkitFor(
                executor("write_query", "写工具", readOnlyHint(false)),
                executor("read_query", "只读工具", readOnlyHint(true)),
                executor("plain_query", "没声明的工具", null));

        // 三个节点都没勾确认：自报的写工具照样拦下，没声明的仍听意图树，否则查询工具会集体开始弹卡片
        assertThat(behaviorOf(toolkit, "write_query")).isEqualTo(PermissionBehavior.ASK);
        assertThat(behaviorOf(toolkit, "read_query")).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(behaviorOf(toolkit, "plain_query")).isEqualTo(PermissionBehavior.ALLOW);
    }

    /**
     * 确认卡的字段顺序跟 schema 走而不是跟模型输出走，两次调用才能按同一顺序比出差异项
     */
    @Test
    void shouldTakeConfirmFieldLabelsFromSchemaTitle() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("leaveType", Map.of("type", "string", "title", "假期类型"));
        properties.put("reason", Map.of("type", "string"));
        Tool tool = Tool.builder()
                .name("leave_submit")
                .description("提交请假申请")
                .inputSchema(new JsonSchema("object", properties, List.of(), null, null, null))
                .build();

        AgentToolCatalog.ResolvedCatalog resolved = new AgentToolCatalog.ResolvedCatalog(
                "知识库工具描述", null,
                List.of(new McpToolBinding("leave_submit", "请假申请", "提交请假申请", true, executor(tool))),
                List.of(), List.of());

        // 没写 title 的字段回落原名，总比在授权界面上凭空少一项强
        assertThat(resolved.fieldLabelsOf("leave_submit"))
                .containsExactly(entry("leaveType", "假期类型"), entry("reason", "reason"));
        assertThat(resolved.fieldLabelsOf("search_knowledge")).isEmpty();
    }

    private PermissionBehavior behaviorOf(Toolkit toolkit, String toolId) {
        ToolBase tool = (ToolBase) toolkit.getTool(toolId);
        return tool.checkPermissions(Map.of(), null).block().getBehavior();
    }

    private Toolkit buildToolkitFor(McpToolExecutor... executors) {
        List<McpToolExecutor> executorList = List.of(executors);
        IntentNodeRegistry intentNodeRegistry = mock(IntentNodeRegistry.class);
        when(intentNodeRegistry.listMcpToolNodes()).thenReturn(executorList.stream()
                .map(executor -> mcpNode(
                        executor.getToolId(), executor.getToolId(), "意图树描述", executor.getToolId()))
                .toList());
        McpToolRegistry mcpToolRegistry = mock(McpToolRegistry.class);
        when(mcpToolRegistry.listAllExecutors()).thenReturn(executorList);
        AgentPromptResolver agentPromptResolver = mock(AgentPromptResolver.class);
        when(agentPromptResolver.resolve(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION))
                .thenReturn("当前 Agent 的知识库工具描述");

        AgentToolCatalog catalog = new AgentToolCatalog(
                mock(KnowledgeSearchFacade.class),
                intentNodeRegistry,
                mcpToolRegistry,
                agentPromptResolver,
                memoryProperties(false),
                mock(AgentMemoryPipeline.class),
                mock(AgentSkillRegistry.class));
        return catalog.buildToolkit(catalog.resolve());
    }

    /**
     * 长期记忆挂载与卸载都由开关和槽位决定，两者都要能改变指纹
     */
    private AgentToolCatalog catalogWithMemory(boolean longTermEnabled, String slotContent) {
        IntentNodeRegistry intentNodeRegistry = mock(IntentNodeRegistry.class);
        when(intentNodeRegistry.listMcpToolNodes()).thenReturn(List.of());
        McpToolRegistry mcpToolRegistry = mock(McpToolRegistry.class);
        when(mcpToolRegistry.listAllExecutors()).thenReturn(List.of());
        AgentPromptResolver agentPromptResolver = mock(AgentPromptResolver.class);
        when(agentPromptResolver.resolve(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION))
                .thenReturn("当前 Agent 的知识库工具描述");
        when(agentPromptResolver.resolve(AgentPromptSlot.AGENT_MEMORY_TOOL_DESCRIPTION))
                .thenReturn(slotContent);

        return new AgentToolCatalog(
                mock(KnowledgeSearchFacade.class),
                intentNodeRegistry,
                mcpToolRegistry,
                agentPromptResolver,
                memoryProperties(longTermEnabled),
                mock(AgentMemoryPipeline.class),
                mock(AgentSkillRegistry.class));
    }

    private AgentMemoryProperties memoryProperties(boolean longTermEnabled) {
        AgentMemoryProperties properties = new AgentMemoryProperties();
        properties.setLongTermEnabled(longTermEnabled);
        return properties;
    }

    private ToolAnnotations readOnlyHint(boolean readOnly) {
        return new ToolAnnotations(null, readOnly, null, null, null, null);
    }

    private IntentNode mcpNode(String id, String name, String description, String toolId) {
        return IntentNode.builder()
                .id(id)
                .name(name)
                .description(description)
                .kind(IntentKind.MCP)
                .mcpToolId(toolId)
                .build();
    }

    private McpToolExecutor executor(String toolId, String description) {
        return executor(toolId, description, null);
    }

    private McpToolExecutor executor(String toolId, String description, ToolAnnotations annotations) {
        JsonSchema schema = new JsonSchema("object", Map.of(), List.of(), false, null, null);
        return executor(Tool.builder()
                .name(toolId)
                .description(description)
                .inputSchema(schema)
                .annotations(annotations)
                .build());
    }

    private McpToolExecutor executor(Tool tool) {
        return new McpToolExecutor() {
            @Override
            public Tool getToolDefinition() {
                return tool;
            }

            @Override
            public CallToolResult execute(Map<String, Object> parameters, Map<String, Object> meta) {
                return null;
            }
        };
    }
}
