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

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchExamPapersMcpExecutorTest {

    /**
     * 工具 ID 是意图树 mcp_tool_id 与技能 tool_ids 的锚，改一侧断一侧
     */
    @Test
    void toolIdIsTheBindingAnchor() {
        assertEquals("search_exam_papers", SearchExamPapersMcpExecutor.TOOL_NAME);
    }

    @Test
    void shouldExposeReadOnlyToolWithRequiredCourseCode() {
        Tool tool = new SearchExamPapersMcpExecutor().searchExamPapersToolSpecification().tool();

        assertEquals("search_exam_papers", tool.name());
        assertEquals(java.util.List.of("course_code"), tool.inputSchema().required());
        assertTrue(tool.inputSchema().properties().containsKey("course_code"));
        assertTrue(tool.annotations().readOnlyHint());
    }

    @Test
    void shouldBuildSubjectCodeDeepLink() {
        CallToolResult result = executor().handleCall(request(Map.of("course_code", "AMA1101")));

        assertFalse(result.isError());
        String text = text(result);
        // query 与 filterquery 都携带课程代码（DSpace 深链是 query+filter 双写，缺一不构成精确过滤）
        assertTrue(text.contains("query=AMA1101"));
        assertTrue(text.contains("filtername=subjectcode&filterquery=AMA1101&filtertype=equals"));
        // 登录墙边界必须交代给用户，不能让模型自行脑补「已帮你下载」
        assertTrue(text.contains("NetID"));
    }

    @Test
    void shouldTrimCourseCode() {
        CallToolResult padded = executor().handleCall(request(Map.of("course_code", "  AMA1101  ")));

        assertFalse(padded.isError());
        assertTrue(text(padded).contains("query=AMA1101"));
    }

    @Test
    void shouldUrlEncodeExoticInput() {
        CallToolResult encoded = executor().handleCall(request(Map.of("course_code", "AMA 1101")));

        assertFalse(encoded.isError());
        // 空格进 URL 必须转义，否则深链在 filterquery 处被截断成另一个参数
        assertTrue(text(encoded).contains("query=AMA+1101"));
    }

    @Test
    void shouldRejectBlankCourseCode() {
        CallToolResult blank = executor().handleCall(request(Map.of("course_code", "   ")));

        assertTrue(blank.isError());
        assertTrue(text(blank).contains("课程代码不能为空"));
    }

    private static SearchExamPapersMcpExecutor executor() {
        return new SearchExamPapersMcpExecutor();
    }

    private static CallToolRequest request(Map<String, Object> arguments) {
        return new CallToolRequest("search_exam_papers", arguments);
    }

    private static String text(CallToolResult result) {
        return ((TextContent) result.content().get(0)).text();
    }
}
