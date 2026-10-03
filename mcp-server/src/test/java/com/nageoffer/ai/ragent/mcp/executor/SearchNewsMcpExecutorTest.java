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

package com.nageoffer.ai.ragent.mcp.executor;

import com.nageoffer.ai.ragent.mcp.rag.RagPublicApi;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchNewsMcpExecutorTest {

    /**
     * 可录制的 rag 桩：捕获一次调用的全部过滤参数，返回预置页
     */
    private static final class RecordingRag implements RagPublicApi {
        RagPublicApi.NewsPage response = new RagPublicApi.NewsPage(List.of(), 0L, false);
        RuntimeException transportFailure;
        String keyword;
        String topic;
        LocalDate dateFrom;
        LocalDate dateTo;
        Integer size;

        @Override
        public NewsPage searchNews(String keyword, String topic, LocalDate dateFrom, LocalDate dateTo,
                                   int page, int size) {
            this.keyword = keyword;
            this.topic = topic;
            this.dateFrom = dateFrom;
            this.dateTo = dateTo;
            this.size = size;
            if (transportFailure != null) {
                throw transportFailure;
            }
            return response;
        }

        @Override
        public KeyDateBoard keyDateBoard() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * 工具 ID 是意图树 mcp_tool_id（261003_01 迁移）与技能 tool_ids 的锚，改一侧断一侧
     */
    @Test
    void toolIdIsTheBindingAnchor() {
        assertEquals("search_news", SearchNewsMcpExecutor.TOOL_NAME);
    }

    @Test
    void shouldExposeReadOnlyToolWithBoundedOptionalParams() {
        Tool tool = new SearchNewsMcpExecutor(new RecordingRag()).searchNewsToolSpecification().tool();

        assertEquals("search_news", tool.name());
        // 过滤维度全可选（至少一个的约束在 handleCall 里给结构化错误，schema 不强制）
        assertEquals(List.of(), tool.inputSchema().required());
        assertEquals(5, tool.inputSchema().properties().size());
        assertTrue(tool.inputSchema().properties().keySet()
                .containsAll(List.of("keyword", "topic", "date_from", "date_to", "limit")));
        assertTrue(tool.annotations().readOnlyHint());
    }

    @Test
    void shouldForwardAllFiltersToPublicApi() {
        RecordingRag rag = new RecordingRag();

        CallToolResult result = executor(rag).handleCall(request(Map.of(
                "keyword", " 奖学金 ",
                "topic", "scholarship",
                "date_from", "2026-09-01",
                "date_to", "2026-09-30",
                "limit", 5)));

        assertFalse(result.isError());
        assertEquals("奖学金", rag.keyword, "关键词要 trim");
        assertEquals("scholarship", rag.topic);
        assertEquals(LocalDate.of(2026, 9, 1), rag.dateFrom);
        assertEquals(LocalDate.of(2026, 9, 30), rag.dateTo);
        assertEquals(5, rag.size);
    }

    /**
     * 无效输入矩阵：空条件/空关键词裸调/负数与零 limit/非法日期/时间窗倒挂——
     * 一律结构化错误（isError=true），不抛裸异常
     */
    @Test
    void shouldRejectInvalidInputsWithStructuredErrors() {
        SearchNewsMcpExecutor executor = executor(new RecordingRag());

        CallToolResult noFilter = executor.handleCall(request(Map.of()));
        assertTrue(noFilter.isError());
        assertTrue(text(noFilter).contains("至少提供"));

        CallToolResult blankKeywordOnly = executor.handleCall(request(Map.of("keyword", "   ")));
        assertTrue(blankKeywordOnly.isError());
        assertTrue(text(blankKeywordOnly).contains("至少提供"));

        CallToolResult negative = executor.handleCall(request(Map.of("keyword", "x", "limit", -3)));
        assertTrue(negative.isError());
        assertTrue(text(negative).contains("limit"));

        CallToolResult zero = executor.handleCall(request(Map.of("keyword", "x", "limit", 0)));
        assertTrue(zero.isError());

        CallToolResult notInt = executor.handleCall(request(Map.of("keyword", "x", "limit", "ten")));
        assertTrue(notInt.isError());

        CallToolResult badDate = executor.handleCall(request(Map.of("date_from", "2026/09/01")));
        assertTrue(badDate.isError());
        assertTrue(text(badDate).contains("YYYY-MM-DD"));

        CallToolResult inverted = executor.handleCall(request(Map.of(
                "date_from", "2026-09-30", "date_to", "2026-09-01")));
        assertTrue(inverted.isError());
        assertTrue(text(inverted).contains("时间窗不合法"));
    }

    /**
     * 上限矩阵：limit>50 钳到 50（防滥用不是防错误），默认 10
     */
    @Test
    void shouldClampLimitToHardMax() {
        RecordingRag rag = new RecordingRag();

        executor(rag).handleCall(request(Map.of("keyword", "x", "limit", 1000)));
        assertEquals(50, rag.size);

        executor(rag).handleCall(request(Map.of("keyword", "x")));
        assertEquals(10, rag.size);
    }

    /**
     * 有界输出：服务端万一回吐超限条数，渲染层仍按 limit 截断并明示差额
     */
    @Test
    void shouldBoundRecordCountEvenIfApiOverreturns() {
        RecordingRag rag = new RecordingRag();
        List<RagPublicApi.NewsItem> oversized = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            oversized.add(item(i, "标题" + i));
        }
        rag.response = new RagPublicApi.NewsPage(oversized, 60L, true);

        CallToolResult result = executor(rag).handleCall(request(Map.of("keyword", "x", "limit", 5)));

        assertFalse(result.isError());
        String body = text(result);
        assertEquals(5, countEntries(body), "渲染条数被 limit 截断");
        assertTrue(body.contains("另有 55 条未显示"));
    }

    /**
     * 有界输出：脏数据（超长标题/摘要/链接）也要被字段封顶挡住，一条脏行不能吃掉整轮上下文
     */
    @Test
    void shouldBoundFieldLengthInOutput() {
        RecordingRag rag = new RecordingRag();
        String giantTitle = "巨".repeat(5000);
        String giantSummary = "摘".repeat(5000);
        rag.response = new RagPublicApi.NewsPage(
                List.of(new RagPublicApi.NewsItem(1L, "https://example.invalid/1", giantTitle, "title en",
                        giantSummary, "summary en", "campus", "2026-09-28T08:00:00.000+08:00")), 1L, false);

        CallToolResult result = executor(rag).handleCall(request(Map.of("keyword", "x")));

        assertFalse(result.isError());
        assertTrue(text(result).length() < 2000, "5000 字脏标题+5000 字脏摘要必须被截到 ~200 字内");
        assertTrue(text(result).contains("…"));
    }

    @Test
    void shouldRenderEmptyResultWithGuidance() {
        RecordingRag rag = new RecordingRag();
        rag.response = new RagPublicApi.NewsPage(List.of(), 0L, false);

        CallToolResult result = executor(rag).handleCall(request(Map.of("keyword", "不存在的关键词")));

        assertFalse(result.isError());
        assertTrue(text(result).contains("没有匹配的已发布资讯"));
    }

    /**
     * AI 摘要声明义务（NewsItemVO 契约）：出口文案必须带「以原文为准」
     */
    @Test
    void shouldCarryAiSummaryDisclaimer() {
        RecordingRag rag = new RecordingRag();
        rag.response = new RagPublicApi.NewsPage(List.of(item(1, "标题")), 1L, false);

        CallToolResult result = executor(rag).handleCall(request(Map.of("keyword", "x")));

        assertTrue(text(result).contains("以原文"));
        assertTrue(text(result).contains("原文:"));
    }

    /**
     * 业务失败（如 rag 报「主题不存在」）：文案可透传给模型
     */
    @Test
    void shouldSurfaceBusinessErrorAsStructuredMessage() {
        RecordingRag rag = new RecordingRag();
        rag.transportFailure = new McpToolException("主题不存在");

        CallToolResult result = executor(rag).handleCall(request(Map.of("topic", "nope")));

        assertTrue(result.isError());
        assertTrue(text(result).contains("主题不存在"));
    }

    /**
     * 传输层失败：原文只进日志，给模型的是固定文案（无连接串/无栈顶）
     */
    @Test
    void shouldHideTransportErrorDetails() {
        RecordingRag rag = new RecordingRag();
        rag.transportFailure = new IllegalStateException("Connection refused: polyu-app:8080");

        CallToolResult result = executor(rag).handleCall(request(Map.of("keyword", "x")));

        assertTrue(result.isError());
        assertTrue(text(result).contains("资讯检索失败"));
        assertFalse(text(result).contains("polyu-app"));
        assertFalse(text(result).contains("Connection refused"));
    }

    private static int countEntries(String body) {
        int count = 0;
        for (String line : body.split("\n")) {
            if (line.matches("\\d+\\. .*")) {
                count++;
            }
        }
        return count;
    }

    private static RagPublicApi.NewsItem item(long id, String title) {
        return new RagPublicApi.NewsItem(id, "https://example.invalid/" + id, title, "title en " + id,
                "摘要" + id, "summary en", "campus", "2026-09-28T08:00:00.000+08:00");
    }

    private static SearchNewsMcpExecutor executor(RagPublicApi rag) {
        return new SearchNewsMcpExecutor(rag);
    }

    private static CallToolRequest request(Map<String, Object> arguments) {
        return new CallToolRequest("search_news", new HashMap<>(arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}
