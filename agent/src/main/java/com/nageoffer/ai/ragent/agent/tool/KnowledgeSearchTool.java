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
import com.nageoffer.ai.ragent.agent.dto.AgentBlockSource;
import com.nageoffer.ai.ragent.agent.trace.AgentToolBodyTracer;
import com.nageoffer.ai.ragent.rag.service.KnowledgeSearchFacade;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库检索工具：RAG 管线在 Agent 模式下的唯一入口，描述由当前 Agent 的提示词槽位提供
 */
@Slf4j
@RequiredArgsConstructor
public class KnowledgeSearchTool implements AgentTool {

    public static final String TOOL_NAME = "search_knowledge";
    public static final String DISPLAY_NAME = "知识库检索";

    private static final String QUERY_PARAM = "query";
    private static final String QUERY_DESCRIPTION = "用于检索知识库的完整独立问题";
    private static final String SEARCH_ERROR_MESSAGE = "知识库检索异常，请稍后重试";

    private final String description;
    private final KnowledgeSearchFacade knowledgeSearchFacade;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        QUERY_PARAM, Map.of(
                                "type", "string",
                                "description", QUERY_DESCRIPTION)),
                "required", List.of(QUERY_PARAM),
                "additionalProperties", false);
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return AgentToolBodyTracer.trace(this, param, () -> Mono.fromCallable(() -> execute(param))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private ToolResultBlock execute(ToolCallParam param) {
        if (param == null) {
            return buildResult(null, "工具调用参数不能为空", true);
        }
        String toolCallId = Optional.ofNullable(param.getToolUseBlock())
                .map(ToolUseBlock::getId)
                .orElse(null);
        Optional<String> query = Optional.ofNullable(param.getInput())
                .map(input -> input.get(QUERY_PARAM))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(String::strip)
                .filter(StrUtil::isNotBlank);
        if (query.isEmpty()) {
            return buildResult(toolCallId, "工具参数 query 不能为空", true);
        }
        String normalizedQuery = query.get();
        try {
            KnowledgeSearchFacade.KnowledgeSearchOutcome outcome =
                    knowledgeSearchFacade.searchWithSources(normalizedQuery);
            if (StrUtil.isBlank(outcome.answer())) {
                log.warn("知识库检索未返回有效内容, toolCallId: {}", toolCallId);
                return buildResult(toolCallId, SEARCH_ERROR_MESSAGE, true);
            }
            // docId 不进模型上下文：来源走旁路暂存，返回值只有答案文本
            if (!outcome.sources().isEmpty()) {
                AgentToolSourceStash.put(toolCallId, outcome.sources().stream()
                        .map(source -> new AgentBlockSource(source.docId(), source.docName(),
                                source.excerpt(), source.sourceType(), source.url()))
                        .toList());
            }
            return buildResult(toolCallId, outcome.answer(), false);
        } catch (Exception e) {
            log.error("知识库检索工具调用异常, toolCallId: {}", toolCallId, e);
            // 工具返回会重新进入模型上下文，不得暴露异常中的内部地址、SQL 或凭据等细节
            return buildResult(toolCallId, SEARCH_ERROR_MESSAGE, true);
        }
    }

    private ToolResultBlock buildResult(String toolCallId, String text, boolean isError) {
        return ToolResultBlock.builder()
                .id(toolCallId)
                .name(TOOL_NAME)
                .output(TextBlock.builder().text(StrUtil.emptyIfNull(text)).build())
                .state(isError ? ToolResultState.ERROR : ToolResultState.SUCCESS)
                .build();
    }
}
