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

package com.nageoffer.ai.ragent.rag.core.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class McpClientToolExecutorTest {

    private static final String TOOL_ID = "query_order";

    private static final String PHONE = "13800001111";

    private McpSyncClient mcpClient;
    private McpClientToolExecutor executor;

    private Logger executorLogger;
    private ListAppender<ILoggingEvent> appender;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        mcpClient = mock(McpSyncClient.class);
        when(mcpClient.callTool(any())).thenReturn(CallToolResult.builder()
                .content(List.of(new TextContent("订单查询结果")))
                .isError(false)
                .build());
        executor = new McpClientToolExecutor(mcpClient, Tool.builder()
                .name(TOOL_ID)
                .description("查询订单")
                .inputSchema(new JsonSchema("object", Map.of(), List.of(), null, null, null))
                .build());

        executorLogger = (Logger) LoggerFactory.getLogger(McpClientToolExecutor.class);
        originalLevel = executorLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        executorLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        executorLogger.detachAppender(appender);
        executorLogger.setLevel(originalLevel);
    }

    @Test
    void shouldSendMetaAlongWithArguments() {
        executor.execute(Map.of("orderNo", "88231"), McpCallMeta.ofUser("2001"));

        CallToolRequest request = captureRequest();
        assertThat(request.arguments()).containsEntry("orderNo", "88231");
        assertThat(request.meta()).containsEntry(McpCallMeta.USER_ID_KEY, "2001");
    }

    /**
     * 没身份可带时请求体要和加透传之前一模一样，不发空的 _meta
     */
    @Test
    void shouldOmitMetaWhenNothingToCarry() {
        executor.execute(Map.of("orderNo", "88231"), Map.of());

        assertThat(captureRequest().meta()).isNull();
    }

    /**
     * 单参重载留给不需要身份的老调用方，它不能凭空造出一个 _meta
     */
    @Test
    void shouldOmitMetaOnSingleArgumentOverload() {
        executor.execute(Map.of("orderNo", "88231"));

        assertThat(captureRequest().meta()).isNull();
    }

    @Test
    void shouldWrapRemoteFailureAsErrorResult() {
        when(mcpClient.callTool(any())).thenThrow(new IllegalStateException("连接被拒绝"));

        CallToolResult result = executor.execute(Map.of(), McpCallMeta.ofUser("2001"));

        assertThat(result.isError()).isTrue();
    }

    /**
     * 入参的值不许进 INFO：手机号、地址这些服务端是逐个打码才回的，日志里原样打出来等于打码白做
     * 形状仍要留住，否则线上出问题连「带了哪些参数、谁在调」都答不上来
     */
    @Test
    void shouldKeepParameterValuesOutOfInfoLog() {
        executorLogger.setLevel(Level.INFO);

        executor.execute(Map.of("receiverPhone", PHONE), McpCallMeta.ofUser("2001"));

        assertThat(renderedLog()).contains("receiverPhone").contains("2001").doesNotContain(PHONE);
    }

    /**
     * 值只是降到 DEBUG，不是删了——查问题时还得看得见
     */
    @Test
    void shouldStillCarryParameterValuesAtDebugLevel() {
        executorLogger.setLevel(Level.DEBUG);

        executor.execute(Map.of("receiverPhone", PHONE), McpCallMeta.ofUser("2001"));

        assertThat(renderedLog()).contains(PHONE);
    }

    private String renderedLog() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    private CallToolRequest captureRequest() {
        ArgumentCaptor<CallToolRequest> captor = ArgumentCaptor.forClass(CallToolRequest.class);
        verify(mcpClient).callTool(captor.capture());
        return captor.getValue();
    }
}
