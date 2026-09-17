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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.skill.AgentSkillMaskingMiddleware;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.McpToolBinding;
import com.nageoffer.ai.ragent.agent.trace.AgentToolBodyTracer;
import com.nageoffer.ai.ragent.rag.core.mcp.McpCallMeta;
import com.nageoffer.ai.ragent.rag.core.mcp.McpToolExecutor;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 将 MCP 执行器适配为 AgentScope 工具，继承 ToolBase 以接入权限检查
 */
@Slf4j
public class McpToolBridge extends ToolBase {

    private static final String CALL_FAILED_MESSAGE = "工具调用失败，请稍后重试";

    private final McpToolExecutor executor;

    /**
     * 确认语义由目录算定，这里只消费
     */
    private final boolean needsConfirm;

    public McpToolBridge(McpToolBinding binding) {
        super(ToolBase.builder()
                .name(binding.toolId())
                .description(binding.description())
                .inputSchema(binding.inputSchema())
                .readOnly(binding.readOnly()));
        this.executor = binding.executor();
        this.needsConfirm = binding.needsConfirm();
    }

    /**
     * 需要确认时返回 ask，否则返回 allow
     * 框架只取 behavior，message 既不进确认卡也不进模型上下文，这里的文案仅供排查时读
     */
    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput, PermissionContextState context) {
        if (!needsConfirm) {
            return Mono.just(PermissionDecision.allow("该工具未配置执行前确认"));
        }
        return Mono.just(PermissionDecision.ask("该操作会产生实际业务影响，执行前需要你确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        String maskedBy = maskedBySkill(param);
        if (maskedBy != null) {
            log.info("技能未加载, 拒绝直接调用, toolId: {}, skillCode: {}", getName(), maskedBy);
            markMasked(param, maskedBy);
            return Mono.just(buildResult(toolCallId(param), """
                    这个工具属于技能 %s，手册还没加载，本次调用没有执行。
                    请先调用 load_skill 取 skill_code 为 %s 的手册，按手册里的步骤办。"""
                    .formatted(maskedBy, maskedBy), true));
        }
        return AgentToolBodyTracer.trace(this, param, () -> Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * 只记原因不记起止，必须在 trace helper 之前调用
     */
    private static void markMasked(ToolCallParam param, String maskedBy) {
        AgentToolExecutionFacts facts = AgentToolExecutionFacts
                .from(param == null ? null : param.getRuntimeContext());
        if (facts != null) {
            facts.markShortCircuit(toolCallId(param), AgentToolExecutionFacts.SHORT_CIRCUIT_MASKED, maskedBy);
        }
    }

    /**
     * schema 已遮蔽仍打过来的调用兜底：模型照着历史里的旧调用硬闯时退回让它先取手册
     *
     * @return 该工具所属且未加载的技能标识，未被遮蔽返回 null
     */
    private String maskedBySkill(ToolCallParam param) {
        RuntimeContext runtimeContext = param == null ? null : param.getRuntimeContext();
        Object masked = runtimeContext == null
                ? null
                : runtimeContext.get(AgentSkillMaskingMiddleware.MASKED_TOOLS_ATTRIBUTE);
        return masked instanceof Map<?, ?> map && map.get(getName()) instanceof String skillCode
                ? skillCode
                : null;
    }

    private static String toolCallId(ToolCallParam param) {
        return param == null || param.getToolUseBlock() == null ? null : param.getToolUseBlock().getId();
    }

    /**
     * 身份只认 RuntimeContext：这里已经切到 boundedElastic，ThreadLocal 型的 UserContext 传不过来，
     * 取到的会是 null 且不抛异常，症状是所有业务数据静默挂在空用户上
     */
    private static String userId(ToolCallParam param) {
        RuntimeContext runtimeContext = param == null ? null : param.getRuntimeContext();
        return runtimeContext == null ? null : runtimeContext.getUserId();
    }

    private ToolResultBlock execute(ToolCallParam param) {
        if (param == null) {
            return buildResult(null, "工具调用参数不能为空", true);
        }
        String toolCallId = toolCallId(param);
        Map<String, Object> input = param.getInput() == null
                ? Map.of()
                : new HashMap<>(param.getInput());
        try {
            CallToolResult result = executor.execute(input, McpCallMeta.ofUser(userId(param)));
            if (result == null) {
                log.error("MCP 执行器返回 null, toolId: {}, toolCallId: {}", getName(), toolCallId);
                return buildResult(toolCallId, CALL_FAILED_MESSAGE, true);
            }
            boolean isError = Boolean.TRUE.equals(result.isError());
            String text = extractText(result);
            if (isError && StrUtil.isBlank(text)) {
                text = "工具执行失败，但没有返回错误说明";
            }
            return buildResult(toolCallId, text, isError);
        } catch (Exception e) {
            log.error("MCP 工具调用异常, toolId: {}, toolCallId: {}", getName(), toolCallId, e);
            return buildResult(toolCallId, CALL_FAILED_MESSAGE, true);
        }
    }

    private ToolResultBlock buildResult(String toolCallId, String text, boolean isError) {
        return ToolResultBlock.builder()
                .id(toolCallId)
                .name(getName())
                .output(TextBlock.builder().text(text).build())
                .state(isError ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }

    private String extractText(CallToolResult result) {
        if (result == null || CollUtil.isEmpty(result.content())) {
            return "（工具无返回内容）";
        }
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.joining("\n"));
    }
}
