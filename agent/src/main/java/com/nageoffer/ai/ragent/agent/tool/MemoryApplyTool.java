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
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryApprovalService;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPlanExecution;
import com.nageoffer.ai.ragent.agent.trace.AgentToolBodyTracer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionDecision;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tool.ToolCallParam;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 记忆变更计划的执行工具：继承 ToolBase 走 checkPermissions 产生原生 ASK，
 * 与 flush_memory（AgentTool 直通）分工——那个只准备计划，这个只在用户确认后执行
 * 入参只有 operationId：目标、内容、用户身份全部来自服务端台账，模型改不了执行什么，也带不进「已批准」
 */
@Slf4j
public class MemoryApplyTool extends ToolBase {

    public static final String TOOL_NAME = "apply_memory_change";
    public static final String DISPLAY_NAME = "记忆变更确认";

    private static final String OPERATION_ID_PARAM = "operationId";

    private static final Map<String, Object> PARAMETERS = Map.of(
            "type", "object",
            "properties", Map.of(OPERATION_ID_PARAM, Map.of(
                    "type", "string",
                    "description", "待确认的记忆变更计划编号，只能原样使用 flush_memory 结果里给出的编号")),
            "required", List.of(OPERATION_ID_PARAM),
            "additionalProperties", false);

    private static final String DESCRIPTION = """
            提交一份已生成的长期记忆变更计划，交用户在确认卡上裁决。调用本工具不代表变更已生效：\
            只有用户在确认卡上同意后本工具才会执行计划，拒绝或超时都不会执行。\
            仅当 flush_memory 的返回里给出了待确认的计划编号（operationId）时才调用本工具，\
            参数只填该编号本身，不要编造、改写或复用旧编号。\
            执行结果出来之前，不要对用户宣称已经删除或清空。""";

    private final AgentMemoryApprovalService approvalService;

    public MemoryApplyTool(AgentMemoryApprovalService approvalService) {
        super(ToolBase.builder()
                .name(TOOL_NAME)
                .description(DESCRIPTION)
                .inputSchema(new LinkedHashMap<>(PARAMETERS))
                // 写操作：执行会改变生效记忆，必须走确认
                .readOnly(false)
                // 计划执行在用户级串行（控制行锁），并发调用只会互等
                .concurrencySafe(false));
        this.approvalService = approvalService;
    }

    @Override
    public Mono<PermissionDecision> checkPermissions(Map<String, Object> toolInput,
                                                     PermissionContextState context) {
        return Mono.just(PermissionDecision.ask("这份记忆变更计划执行前需要你确认"));
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return AgentToolBodyTracer.trace(this, param, () -> Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        String toolCallId = Optional.ofNullable(param)
                .map(ToolCallParam::getToolUseBlock)
                .map(block -> block.getId())
                .orElse(null);
        if (param == null) {
            return buildResult(null, "工具调用参数不能为空", true);
        }
        String operationId = param.getInput() == null ? null
                : StrUtil.toStringOrNull(param.getInput().get(OPERATION_ID_PARAM));
        RuntimeContext runtimeContext = param.getRuntimeContext();
        String userId = Optional.ofNullable(runtimeContext)
                .map(RuntimeContext::getUserId)
                .orElse(null);
        if (StrUtil.isBlank(operationId) || StrUtil.isBlank(userId)) {
            log.warn("记忆变更执行工具缺计划编号或身份, 本次不执行");
            return buildResult(toolCallId, "缺少计划编号或会话身份，本次未执行任何记忆变更", true);
        }
        try {
            AgentMemoryPlanExecution execution = approvalService.execute(operationId, userId, toolCallId);
            log.info("记忆变更执行工具调用完成, userId: {}, operationId: {}, 结局: {}",
                    userId, operationId, execution.outcome());
            // REFUSED 一律 ERROR：没执行过的事不能以成功口吻回报给模型
            return buildResult(toolCallId, execution.detail(), !execution.executed());
        } catch (Exception e) {
            log.error("记忆变更执行工具调用异常, userId: {}, operationId: {}", userId, operationId, e);
            return buildResult(toolCallId, "记忆变更执行异常，本次未产生任何变更，可让用户重新发起", true);
        }
    }

    private ToolResultBlock buildResult(String toolCallId, String text, boolean isError) {
        return ToolResultBlock.builder()
                .id(toolCallId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(text).build())
                .state(isError ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }
}
