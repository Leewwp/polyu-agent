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

import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.mcp.config.McpToolAnnotations;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 图书馆往年试卷检索工具（issue #164）。
 * <p>
 * 试卷库 exam.lib.polyu.edu.hk 是 DSpace：按课程代码精确过滤的检索页是公开深链
 * （列表浏览无需登录），试卷正文层才是 NetID 登录墙。因此本工具是确定性的深链构造器——
 * 只返回检索页链接与获取指引，不发起任何网络请求、不抓取试卷内容（版权边界）。
 */
@Slf4j
@Component
public class SearchExamPapersMcpExecutor {

    /**
     * 工具 ID 三处逐字符一致：本常量、意图树 MCP 节点 mcp_tool_id、技能表 tool_ids
     */
    public static final String TOOL_NAME = "search_exam_papers";

    private static final String SEARCH_PAGE_URL = "https://exam.lib.polyu.edu.hk/simple-search?query=";

    @Bean
    public McpServerFeatures.SyncToolSpecification searchExamPapersToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .required(string("course_code", "PolyU 课程代码，如 AMA1101、COMP3330；含后缀的完整科目代码原样传入")
                        .title("课程代码"))
                .build();

        return Tool.builder()
                .name(TOOL_NAME)
                .description("检索香港理工大学图书馆往年试卷库（exam.lib.polyu.edu.hk），返回按课程代码精确过滤的"
                        + "试卷检索页链接；用户询问某门课程的往年试卷、过往试卷、past paper 时调用")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String courseCode = StrUtil.trim(MapUtil.getStr(args, "course_code"));
            if (StrUtil.isBlank(courseCode)) {
                return McpToolResults.error("课程代码不能为空，请先向用户确认课程代码（如 AMA1101）再调用");
            }

            String encoded = URLEncoder.encode(courseCode, StandardCharsets.UTF_8);
            String link = SEARCH_PAGE_URL + encoded
                    + "&filtername=subjectcode&filterquery=" + encoded + "&filtertype=equals";

            String result = String.format("""
                    试卷检索链接（PolyU 图书馆试卷库，按课程代码 %s 精确过滤）:
                    %s

                    使用说明:
                    1. 打开链接即是该课程的往年试卷列表，浏览列表无需登录
                    2. 下载试卷正文需要 PolyU NetID 登录；本工具只提供检索链接，不代取试卷内容
                    3. 列表为空时可能是课程代码写法不同（如含科目后缀），建议向用户确认完整科目代码后重试""",
                    courseCode, link);

            log.info("MCP 工具调用完成, toolId={}, courseCode={}, elapsed={}ms",
                    TOOL_NAME, courseCode, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_NAME, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("试卷检索", e);
        }
    }
}
