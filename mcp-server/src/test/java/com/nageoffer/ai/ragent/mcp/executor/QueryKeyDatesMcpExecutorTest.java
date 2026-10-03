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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryKeyDatesMcpExecutorTest {

    /**
     * 工具 ID 是意图树 mcp_tool_id（261003_01 迁移）与技能 tool_ids 的锚，改一侧断一侧
     */
    @Test
    void toolIdIsTheBindingAnchor() {
        assertEquals("query_key_dates", QueryKeyDatesMcpExecutor.TOOL_NAME);
    }

    @Test
    void shouldExposeReadOnlyToolWithSegmentEnumAndBoundedLimit() {
        Tool tool = new QueryKeyDatesMcpExecutor(recordingBoard()).queryKeyDatesToolSpecification().tool();

        assertEquals("query_key_dates", tool.name());
        assertEquals(List.of(), tool.inputSchema().required());
        assertTrue(tool.inputSchema().properties().keySet()
                .containsAll(List.of("keyword", "segment", "limit")));
        @SuppressWarnings("unchecked")
        Map<String, Object> segment = (Map<String, Object>) tool.inputSchema().properties().get("segment");
        assertEquals(QueryKeyDatesMcpExecutor.SEGMENTS, segment.get("enum"));
        assertTrue(tool.annotations().readOnlyHint());
    }

    @Test
    void shouldDefaultToCurrentAndUpcomingSegment() {
        RagPublicApi rag = recordingBoard();

        CallToolResult result = executor(rag).handleCall(request(Map.of()));

        assertFalse(result.isError());
        String body = text(result);
        assertTrue(body.contains("进行中+即将到来"));
        assertTrue(body.contains("今日缴费截止"), "默认段取 currentAndUpcoming");
        assertFalse(body.contains("往期条目"), "其他段不串台");
    }

    /**
     * 分段矩阵：每个合法 segment 取对应段；未知取值结构化报错
     */
    @Test
    void shouldSelectRequestedSegmentAndRejectUnknown() {
        RagPublicApi rag = recordingBoard();

        CallToolResult archived = executor(rag).handleCall(request(Map.of("segment", "archived")));
        assertTrue(text(archived).contains("往期条目"));

        CallToolResult recent = executor(rag).handleCall(request(Map.of("segment", "recent_past")));
        assertTrue(text(recent).contains("上周已过期"));

        CallToolResult undated = executor(rag).handleCall(request(Map.of("segment", "undated")));
        assertTrue(text(undated).contains("Late October 2026"));

        CallToolResult bogus = executor(rag).handleCall(request(Map.of("segment", "tomorrow")));
        assertTrue(bogus.isError());
        assertTrue(text(bogus).contains("segment 取值必须是"));
    }

    /**
     * 关键词过滤：标题（中/英、大小写不敏感）命中才保留
     */
    @Test
    void shouldFilterByKeywordAcrossBilingualTitles() {
        RagPublicApi rag = recordingBoard();

        CallToolResult zh = executor(rag).handleCall(request(Map.of("keyword", "缴费")));
        assertFalse(zh.isError());
        assertTrue(text(zh).contains("今日缴费截止"));
        assertFalse(text(zh).contains("Add/Drop 截止"));

        CallToolResult en = executor(rag).handleCall(request(Map.of("keyword", "ADD/DROP")));
        assertFalse(en.isError());
        assertTrue(text(en).contains("Add/Drop 截止"));

        CallToolResult none = executor(rag).handleCall(request(Map.of("keyword", "不存在的事项")));
        assertFalse(none.isError());
        assertTrue(text(none).contains("没有匹配的关键日期记录"));
    }

    /**
     * 有界输出：limit 截断 + 明示差额；非法 limit（0/负数/非整数）结构化报错
     */
    @Test
    void shouldBoundOutputAndRejectInvalidLimit() {
        RagPublicApi rag = recordingBoard();

        CallToolResult bounded = executor(rag).handleCall(request(Map.of("limit", 1)));
        assertFalse(bounded.isError());
        String body = text(bounded);
        assertEquals(1, countEntries(body));
        assertTrue(body.contains("显示前 1 条"), "截断差额要明示");

        CallToolResult negative = executor(rag).handleCall(request(Map.of("limit", -2)));
        assertTrue(negative.isError());

        CallToolResult notInt = executor(rag).handleCall(request(Map.of("limit", "many")));
        assertTrue(notInt.isError());
    }

    /**
     * 源三态合同：anySourceAbnormal=true 必须原样告知（按最后完整版本呈现），不得冒充最新
     */
    @Test
    void shouldSurfaceSourceAbnormalWarning() {
        RagPublicApi rag = recordingBoard();
        ((RecordingBoard) rag).setAbnormal(true);

        CallToolResult result = executor(rag).handleCall(request(Map.of()));

        assertTrue(text(result).contains("部分数据源当前异常"));
        assertTrue(text(result).contains("2026-10-01T08:00:00"));
    }

    /**
     * #193 合同 §2：audienceText（官方人群限制原文）不得省略；daysUntil 人话化
     */
    @Test
    void shouldCarryAudienceTextAndFriendlyDays() {
        RagPublicApi rag = recordingBoard();

        String current = text(executor(rag).handleCall(request(Map.of())));
        assertTrue(current.contains("适用: 全日制本科生"), "人群限制原文必须透传");
        assertTrue(current.contains("今天）"), "daysUntil=0 → 今天");
        assertTrue(current.contains("3 天后）"), "daysUntil=3 → N 天后");

        String recent = text(executor(rag).handleCall(request(Map.of("segment", "recent_past"))));
        assertTrue(recent.contains("已过 5 天）"), "recentPast 段 daysUntil<0 → 已过 N 天");
    }

    /**
     * 模糊窗口不伪造具体日：fuzzy 行透出原文窗口并标注「未定具体日期」
     */
    @Test
    void shouldNotFabricateDatesForFuzzyEntries() {
        RagPublicApi rag = recordingBoard();

        CallToolResult result = executor(rag).handleCall(request(Map.of("segment", "undated")));

        String body = text(result);
        assertTrue(body.contains("Late October 2026（未定具体日期）"));
        assertFalse(body.contains("日期: 2026-"), "模糊段不得出现编造的具体日期");
    }

    @Test
    void shouldSurfaceBusinessErrorAsStructuredMessage() {
        RagPublicApi rag = new RecordingBoard() {
            @Override
            public KeyDateBoard keyDateBoard() {
                throw new McpToolException("查询的服务当前未开放（功能开关关闭或未部署），请告知用户该功能暂不可用");
            }
        };

        CallToolResult result = executor(rag).handleCall(request(Map.of()));

        assertTrue(result.isError());
        assertTrue(text(result).contains("校历查询失败"));
        assertTrue(text(result).contains("未开放"));
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

    private static RagPublicApi.KeyDateBoard board() {
        return new RagPublicApi.KeyDateBoard(
                "2026/27", "2026-10-01T08:00:00", "2026-10-03", false,
                List.of(
                        new RagPublicApi.KeyDate("今日缴费截止", "Fee payment deadline today", "2026/27", "S1",
                                "exact-day", "2026-10-03", null, null, "全日制本科生",
                                "https://www.polyu.edu.hk/ar/example/fee", "today", 0),
                        new RagPublicApi.KeyDate("Add/Drop 截止", "Add/Drop deadline", "2026/27", "S1",
                                "exact-day", "2026-10-06", null, null, null,
                                "https://www.polyu.edu.hk/ar/example/adddrop", "upcoming", 3)),
                List.of(new RagPublicApi.KeyDate("上周已过期事项", "Expired last week", "2026/27", "S1",
                        "exact-day", "2026-09-28", null, null, null, null, "recent", -5)),
                List.of(new RagPublicApi.KeyDate("往期条目", "Archived entry", "2025/26", "S2",
                        "exact-day", "2026-05-30", null, null, null, null, "archived", null)),
                List.of(new RagPublicApi.KeyDate("毕业事宜模糊窗", "Graduation fuzzy window", "2026/27", "S2",
                        "fuzzy", null, null, "Late October 2026", null, null, "undated", null)),
                12L);
    }

    /**
     * 可变形看板桩（源三态测试需要中途翻转 anySourceAbnormal）
     */
    private static class RecordingBoard implements RagPublicApi {
        private RagPublicApi.KeyDateBoard board = board();

        void setAbnormal(boolean abnormal) {
            RagPublicApi.KeyDateBoard old = board;
            board = new RagPublicApi.KeyDateBoard(old.coverageAcademicYear(), old.lastFullSyncAt(),
                    old.today(), abnormal, old.currentAndUpcoming(), old.recentPast(), old.archived(),
                    old.undated(), old.archivedTotal());
        }

        @Override
        public NewsPage searchNews(String keyword, String topic, LocalDate dateFrom, LocalDate dateTo,
                                   int page, int size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public KeyDateBoard keyDateBoard() {
            return board;
        }
    }

    private static RecordingBoard recordingBoard() {
        return new RecordingBoard();
    }

    private static QueryKeyDatesMcpExecutor executor(RagPublicApi rag) {
        return new QueryKeyDatesMcpExecutor(rag);
    }

    private static CallToolRequest request(Map<String, Object> arguments) {
        return new CallToolRequest("query_key_dates", new HashMap<>(arguments));
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}
