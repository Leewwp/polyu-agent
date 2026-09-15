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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.skill.SkillLoadTool;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNodeRegistry;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkill;
import com.nageoffer.ai.ragent.rag.core.skill.AgentSkillRegistry;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.tool.Toolkit;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主 Agent 工具目录：固定注册 search_knowledge，并按意图树配置挂载当前可用的 MCP 工具
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentToolCatalog {

    private final KnowledgeSearchFacade knowledgeSearchFacade;
    private final AgentConversationService conversationService;
    private final IntentNodeRegistry intentNodeRegistry;
    private final McpToolRegistry mcpToolRegistry;
    private final AgentPromptResolver agentPromptResolver;
    private final AgentMemoryProperties memoryProperties;
    private final AgentMemoryPipeline memoryPipeline;
    private final AgentSkillRegistry skillRegistry;

    /**
     * 解析当前可用工具并生成快照，同一请求内指纹与 Toolkit 都从此快照派生
     */
    public ResolvedCatalog resolve() {
        List<String> unavailableToolIds = new ArrayList<>();
        List<McpToolBinding> bindings = resolveMcpToolBindings(unavailableToolIds);
        return new ResolvedCatalog(resolveKnowledgeToolDescription(), resolveMemoryToolDescription(),
                bindings, unavailableToolIds, skillRegistry.listEnabled());
    }

    /**
     * 根据快照构建 Toolkit
     * 由技能解锁的工具照常注册，遮蔽交给 AgentSkillMaskingMiddleware 按会话逐轮判定
     */
    public Toolkit buildToolkit(ResolvedCatalog catalog) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(new KnowledgeSearchTool(
                catalog.knowledgeToolDescription, knowledgeSearchFacade, conversationService));
        if (catalog.memoryToolDescription != null) {
            toolkit.registerAgentTool(new MemoryFlushTool(catalog.memoryToolDescription, memoryPipeline));
        } else if (memoryProperties.isLongTermEnabled()) {
            log.warn("AGENT_MEMORY_TOOL_DESCRIPTION 提示词为空, 本次不挂载 {}", MemoryFlushTool.TOOL_NAME);
        }
        if (catalog.hasSkills) {
            toolkit.registerAgentTool(new SkillLoadTool(skillRegistry, catalog.displayNames));
        }
        catalog.bindings.forEach(binding -> toolkit.registerAgentTool(new McpToolBridge(binding)));
        // 不可用工具只在构建 Toolkit 时报一次，避免每次解析都刷日志
        catalog.unavailableToolIds.forEach(toolId ->
                log.warn("意图树配置的 MCP 工具当前不可用, toolId: {}", toolId));
        return toolkit;
    }

    /**
     * 当前可用的 MCP 工具数量，用于 meta 探活接口
     */
    public int mcpToolCount() {
        return resolveMcpToolBindings(new ArrayList<>()).size();
    }

    private String resolveKnowledgeToolDescription() {
        String description = agentPromptResolver.resolve(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION);
        if (StrUtil.isBlank(description)) {
            throw new IllegalStateException("KNOWLEDGE_TOOL_DESCRIPTION 提示词不允许为空");
        }
        return description;
    }

    /**
     * 长期记忆关闭或提示词为空时返回 null
     */
    private String resolveMemoryToolDescription() {
        if (!memoryProperties.isLongTermEnabled()) {
            return null;
        }
        String description = agentPromptResolver.resolve(AgentPromptSlot.AGENT_MEMORY_TOOL_DESCRIPTION);
        return StrUtil.isBlank(description) ? null : description;
    }

    /**
     * 意图树配置与 MCP 注册表取交集，有配置但无执行器的记入 unavailableToolIds
     */
    private List<McpToolBinding> resolveMcpToolBindings(List<String> unavailableToolIds) {
        Map<String, List<IntentNode>> nodesByToolId = intentNodeRegistry.listMcpToolNodes().stream()
                .collect(Collectors.groupingBy(
                        node -> node.getMcpToolId().trim(),
                        LinkedHashMap::new,
                        Collectors.toList()));

        Map<String, McpToolExecutor> executors = mcpToolRegistry.listAllExecutors().stream()
                .collect(Collectors.toMap(
                        McpToolExecutor::getToolId,
                        Function.identity(),
                        (left, right) -> right));

        List<McpToolBinding> bindings = new ArrayList<>();
        nodesByToolId.forEach((toolId, nodes) -> {
            McpToolExecutor executor = executors.get(toolId);
            if (executor == null) {
                unavailableToolIds.add(toolId);
                return;
            }
            bindings.add(toBinding(toolId, nodes, executor));
        });
        return bindings;
    }

    private McpToolBinding toBinding(String toolId, List<IntentNode> nodes, McpToolExecutor executor) {
        String displayName = nodes.stream()
                .map(IntentNode::getName)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(toolId);
        String description = nodes.stream()
                .map(IntentNode::getDescription)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.joining("\n"));
        // 同一工具挂在多个意图下，任一节点勾选即需确认
        boolean requireConfirm = nodes.stream().anyMatch(IntentNode::isRequireConfirm);
        return new McpToolBinding(toolId, displayName, description, requireConfirm, executor);
    }

    public record McpToolBinding(
            String toolId,
            String displayName,
            String description,
            boolean requireConfirm,
            McpToolExecutor executor) {
    }

    /**
     * 工具目录快照，展示名、入参标签和指纹在构造时一次算好
     */
    public static final class ResolvedCatalog {

        private final String knowledgeToolDescription;

        /**
         * null 表示本次不挂载记忆整理工具
         */
        private final String memoryToolDescription;

        private final List<McpToolBinding> bindings;
        private final List<String> unavailableToolIds;

        /**
         * 有启用技能时才挂 load_skill，没配技能的接入方看不到这个工具
         */
        private final boolean hasSkills;

        private final Map<String, String> displayNames;
        private final Map<String, Map<String, String>> fieldLabels;
        private final ToolCatalogFingerprint fingerprint;

        public ResolvedCatalog(
                String knowledgeToolDescription,
                String memoryToolDescription,
                List<McpToolBinding> bindings,
                List<String> unavailableToolIds,
                List<AgentSkill> skills) {
            this.knowledgeToolDescription = knowledgeToolDescription;
            this.memoryToolDescription = memoryToolDescription;
            this.bindings = List.copyOf(bindings);
            this.unavailableToolIds = List.copyOf(unavailableToolIds);
            this.hasSkills = !skills.isEmpty();

            Map<String, String> names = new LinkedHashMap<>();
            names.put(KnowledgeSearchTool.TOOL_NAME, KnowledgeSearchTool.DISPLAY_NAME);
            if (memoryToolDescription != null) {
                names.put(MemoryFlushTool.TOOL_NAME, MemoryFlushTool.DISPLAY_NAME);
            }
            if (hasSkills) {
                names.put(SkillLoadTool.TOOL_NAME, SkillLoadTool.DISPLAY_NAME);
            }
            Map<String, Map<String, String>> labels = new LinkedHashMap<>();
            this.bindings.forEach(binding -> {
                names.put(binding.toolId(), binding.displayName());
                labels.put(binding.toolId(), fieldLabels(binding.executor().getToolDefinition()));
            });
            this.displayNames = Map.copyOf(names);
            this.fieldLabels = Collections.unmodifiableMap(labels);
            this.fingerprint = new ToolCatalogFingerprint(knowledgeToolDescription, memoryToolDescription,
                    this.bindings.stream()
                            .map(binding -> new McpToolFingerprint(
                                    binding.toolId(),
                                    binding.displayName(),
                                    binding.description(),
                                    binding.requireConfirm(),
                                    binding.executor().getToolDefinition()))
                            .toList(),
                    skills.stream()
                            .map(skill -> new SkillFingerprint(
                                    skill.skillCode(), skill.name(), skill.description(), skill.toolIds()))
                            .toList());
        }

        public ToolCatalogFingerprint fingerprint() {
            return fingerprint;
        }

        /**
         * 工具展示名，未收录的返回原始名
         */
        public String displayNameOf(String toolName) {
            return displayNames.getOrDefault(toolName, toolName);
        }

        /**
         * 确认卡的入参标签（schema 声明序），未收录的返回空 Map
         */
        public Map<String, String> fieldLabelsOf(String toolName) {
            return fieldLabels.getOrDefault(toolName, Map.of());
        }

        /**
         * 从 schema 提取字段标签，用 LinkedHashMap 保持声明序
         */
        private static Map<String, String> fieldLabels(Tool tool) {
            JsonSchema schema = tool == null ? null : tool.inputSchema();
            if (schema == null || schema.properties() == null) {
                return Map.of();
            }
            Map<String, String> labels = new LinkedHashMap<>();
            schema.properties().forEach((field, spec) -> labels.put(field, titleOf(spec, field)));
            return Collections.unmodifiableMap(labels);
        }

        private static String titleOf(Object spec, String field) {
            return spec instanceof Map<?, ?> node && node.get("title") instanceof String title
                    && StrUtil.isNotBlank(title) ? title : field;
        }
    }

    public record ToolCatalogFingerprint(
            String knowledgeToolDescription,
            String memoryToolDescription,
            List<McpToolFingerprint> mcpTools,
            List<SkillFingerprint> skills) {

        public ToolCatalogFingerprint {
            mcpTools = List.copyOf(mcpTools);
            skills = List.copyOf(skills);
        }
    }

    /**
     * 技能指纹不含正文：正文由 load_skill 现取，改手册不必重建 Agent
     */
    public record SkillFingerprint(
            String skillCode,
            String name,
            String description,
            List<String> toolIds) {

        public SkillFingerprint {
            toolIds = List.copyOf(toolIds);
        }
    }

    public record McpToolFingerprint(
            String toolId,
            String displayName,
            String description,
            boolean requireConfirm,
            Tool definition) {
    }
}
