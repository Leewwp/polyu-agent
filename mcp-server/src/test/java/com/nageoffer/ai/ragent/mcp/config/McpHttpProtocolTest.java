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

import com.nageoffer.ai.ragent.mcp.executor.QueryKeyDatesMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.SearchExamPapersMcpExecutor;
import com.nageoffer.ai.ragent.mcp.executor.SearchNewsMcpExecutor;
import com.nageoffer.ai.ragent.mcp.rag.RagPublicApi;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 新 HTTP 协议（streamable）经 AgentScope 内核触发 MCP 调用链端到端验证：
 * 生产侧 AgentMcpClients 用同一条 transport 发现并调用本服务的 /mcp 端点。
 * rag 数据面用进程内桩（RagPublicApi 接口在 executor 构造处注入，HTTP 桩单测见
 * RagPublicApiClientTest）——本测试只验 MCP 协议链，不起真 rag。
 */
@SpringBootTest(classes = McpHttpProtocolTest.Application.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class McpHttpProtocolTest {

    @LocalServerPort
    private int port;

    /**
     * 工具清单门（固定成本判例）：发现面必须恰好是全部已注册工具——加工具必须改这里，
     * 否则「发现但未挂载/意外多注册」都会被本断言拦下。顺序不锚定：spec 注入序随
     * bean 扫描序（文件系统序），锚序会把无关重构变成红测试。
     */
    @Test
    void shouldDiscoverAndCallExamPaperToolOverHttp() {
        try (var client = McpClientBuilder.create("ragent-test")
                .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                .buildSync()) {
            client.initialize().block(Duration.ofSeconds(5));
            assertThat(client.listTools().block(Duration.ofSeconds(5)))
                    .extracting(tool -> tool.name())
                    .containsExactlyInAnyOrder("search_exam_papers", "search_news", "query_key_dates");

            CallToolResult result = client.callTool("search_exam_papers", Map.of("course_code", "AMA1101"))
                    .block(Duration.ofSeconds(5));
            assertThat(result.isError()).isFalse();
            assertThat(((TextContent) result.content().get(0)).text())
                    .contains("filtername=subjectcode&filterquery=AMA1101&filtertype=equals");
        }
    }

    @Test
    void shouldCallNewsSearchToolOverHttp() {
        try (var client = McpClientBuilder.create("ragent-test")
                .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                .buildSync()) {
            client.initialize().block(Duration.ofSeconds(5));

            CallToolResult result = client.callTool("search_news",
                            Map.of("keyword", "奖学金", "date_from", "2026-09-01", "date_to", "2026-09-30"))
                    .block(Duration.ofSeconds(5));
            assertThat(result.isError()).isFalse();
            assertThat(((TextContent) result.content().get(0)).text())
                    .contains("奖学金申请开放")
                    .contains("以原文");

            CallToolResult invalid = client.callTool("search_news", Map.of())
                    .block(Duration.ofSeconds(5));
            assertThat(invalid.isError()).isTrue();
            assertThat(((TextContent) invalid.content().get(0)).text()).contains("至少提供");
        }
    }

    @Test
    void shouldCallKeyDatesToolOverHttp() {
        try (var client = McpClientBuilder.create("ragent-test")
                .streamableHttpTransport("http://127.0.0.1:" + port + "/mcp")
                .buildSync()) {
            client.initialize().block(Duration.ofSeconds(5));

            CallToolResult result = client.callTool("query_key_dates", Map.of("keyword", "缴费"))
                    .block(Duration.ofSeconds(5));
            assertThat(result.isError()).isFalse();
            assertThat(((TextContent) result.content().get(0)).text())
                    .contains("缴费截止")
                    .contains("适用: 全日制本科生");
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

        @Bean
        McpServerFeatures.SyncToolSpecification searchNewsToolSpecification() {
            return new SearchNewsMcpExecutor(stubRag()).searchNewsToolSpecification();
        }

        @Bean
        McpServerFeatures.SyncToolSpecification queryKeyDatesToolSpecification() {
            return new QueryKeyDatesMcpExecutor(stubRag()).queryKeyDatesToolSpecification();
        }

        /**
         * 进程内 rag 桩：只喂确定性数据，验证协议链与出参渲染
         */
        private static RagPublicApi stubRag() {
            return new RagPublicApi() {
                @Override
                public NewsPage searchNews(String keyword, String topic,
                                           java.time.LocalDate dateFrom, java.time.LocalDate dateTo,
                                           int page, int size) {
                    return new NewsPage(List.of(new NewsItem(7L, "https://example.invalid/7",
                            "奖学金申请开放", "Scholarship opens", "奖学金摘要", "summary",
                            "scholarship", "2026-09-28T08:00:00.000+08:00")), 1L, false);
                }

                @Override
                public KeyDateBoard keyDateBoard() {
                    return new KeyDateBoard("2026/27", "2026-10-01T08:00:00", "2026-10-03", false,
                            List.of(new KeyDate("缴费截止", "Fee payment deadline", "2026/27", "S1",
                                    "exact-day", "2026-10-03", null, null, "全日制本科生",
                                    "https://polyu.example/fee", "today", 0)),
                            List.of(), List.of(), List.of(), 0L);
                }
            };
        }
    }
}
