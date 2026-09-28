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

package com.nageoffer.ai.ragent.mcp.config;

import com.nageoffer.ai.ragent.mcp.executor.SearchExamPapersMcpExecutor;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新 HTTP 协议（streamable）经 AgentScope 内核触发 MCP 调用链端到端验证：
 * 生产侧 AgentMcpClients 用同一条 transport 发现并调用本服务的 /mcp 端点
 */
@SpringBootTest(classes = McpHttpProtocolTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class McpHttpProtocolTest {

    @LocalServerPort
    private int port;

    @Test
    void shouldDiscoverAndCallExamPaperToolOverHttp() {
        try (var client = McpClientBuilder.create("ragent-test")
                .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                .buildSync()) {
            client.initialize().block(Duration.ofSeconds(5));
            assertThat(client.listTools().block(Duration.ofSeconds(5)))
                    .extracting(tool -> tool.name())
                    .containsExactly("search_exam_papers");

            CallToolResult result = client.callTool("search_exam_papers", Map.of("course_code", "AMA1101"))
                    .block(Duration.ofSeconds(5));
            assertThat(result.isError()).isFalse();
            assertThat(((TextContent) result.content().get(0)).text())
                    .contains("filtername=subjectcode&filterquery=AMA1101&filtertype=equals");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(McpServerConfig.class)
    static class Application {

        @Bean
        McpServerFeatures.SyncToolSpecification searchExamPapersToolSpecification() {
            return new SearchExamPapersMcpExecutor().searchExamPapersToolSpecification();
        }
    }
}
