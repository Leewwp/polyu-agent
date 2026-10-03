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
import com.nageoffer.ai.ragent.mcp.rag.RagPublicApi;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 资讯检索工具（issue #214，父票 #182 r3 §MCP）：按关键词 × 主题 × 时间窗检索
 * PolyU 资讯流。数据访问全走 rag 公开查询面（/public/news/mcp-search，SaToken 白名单
 * 免登录）——mcp-server 无数据库层（#164 裁剪），可见性（只读已发布、下架/隐藏/
 * 未过门隔离）由公开面统一保证，本工具不绕过。
 */
@Slf4j
@Component
public class SearchNewsMcpExecutor {

    /**
     * 工具 ID 三处逐字符一致：本常量、意图树 MCP 节点 mcp_tool_id、技能表 tool_ids
     */
    public static final String TOOL_NAME = "search_news";

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    private static final int TITLE_BOUND = 120;
    private static final int SUMMARY_BOUND = 100;
    private static final int URL_BOUND = 200;

    private final RagPublicApi ragPublicApi;

    public SearchNewsMcpExecutor(RagPublicApi ragPublicApi) {
        this.ragPublicApi = ragPublicApi;
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification searchNewsToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .optional(string("keyword", "检索关键词，匹配资讯标题与摘要（中英双语）；用户给出具体话题词（如「奖学金」「交换生」）时传入")
                        .title("关键词"))
                .optional(string("topic", "资讯主题 slug（如 admission/scholarship），来自主题目录；用户指明想看哪类主题时传入，与关键词可叠加")
                        .title("主题"))
                .optional(string("date_from", "发布时间窗起始日（含当日），格式 YYYY-MM-DD；查某个时间段内的资讯时传入")
                        .title("起始日期"))
                .optional(string("date_to", "发布时间窗结束日（含当日），格式 YYYY-MM-DD，不得早于 date_from")
                        .title("结束日期"))
                .optional(integer("limit", "返回条数上限，1-50，默认 10")
                        .title("条数上限")
                        .defaultTo(DEFAULT_LIMIT))
                .build();

        return Tool.builder()
                .name(TOOL_NAME)
                .description("检索香港理工大学校园资讯（奖学金、招生、科研、活动、就业等已发布新闻），"
                        + "支持关键词 × 主题 × 时间窗组合过滤，按发布时间倒序返回。用户问「最近有什么新闻/资讯」"
                        + "「有没有 XX 相关的消息」「过去一周有什么新通知」时调用；至少提供 keyword/topic/"
                        + "date_from/date_to 之一")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String keyword = StrUtil.trimToNull(MapUtil.getStr(args, "keyword"));
            String topic = StrUtil.trimToNull(MapUtil.getStr(args, "topic"));
            LocalDate dateFrom = parseDate("date_from", MapUtil.getStr(args, "date_from"));
            LocalDate dateTo = parseDate("date_to", MapUtil.getStr(args, "date_to"));
            if (dateFrom != null && dateTo != null && dateFrom.isAfter(dateTo)) {
                return McpToolResults.error("时间窗不合法：date_from 晚于 date_to，请确认日期后重试");
            }
            if (keyword == null && topic == null && dateFrom == null && dateTo == null) {
                return McpToolResults.error("至少提供 keyword、topic、date_from、date_to 之一，"
                        + "不要无条件拉取全量资讯；用户没有给出任何范围时先问一句想查什么");
            }
            int limit = limit(args);

            RagPublicApi.NewsPage page = ragPublicApi.searchNews(keyword, topic, dateFrom, dateTo, 1, limit);

            String result = render(limit, page);
            log.info("MCP 工具调用完成, toolId={}, keyword={}, topic={}, window={}~{}, elapsed={}ms",
                    TOOL_NAME, keyword, topic, dateFrom, dateTo, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_NAME, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("资讯检索", e);
        }
    }

    /**
     * 有界渲染：条数以 limit 截断（服务端 size 钳制之外的二次防御），单字段长度封顶；
     * 命中总数与截断差额明示，让模型知道还有余量但不去硬翻页
     */
    private String render(int limit, RagPublicApi.NewsPage page) {
        List<RagPublicApi.NewsItem> records = page.safeRecords();
        List<RagPublicApi.NewsItem> shown = records.stream().limit(limit).toList();
        long total = page.total() == null ? shown.size() : page.total();

        StringBuilder sb = new StringBuilder();
        if (shown.isEmpty()) {
            sb.append("没有匹配的已发布资讯。可尝试更换关键词、改用主题过滤或放宽时间窗。");
            return sb.toString();
        }
        sb.append("资讯检索结果（命中 ").append(total).append(" 条，按发布时间倒序显示前 ")
                .append(shown.size()).append(" 条）：\n");
        int index = 1;
        for (RagPublicApi.NewsItem item : shown) {
            String title = StrUtil.blankToDefault(item.titleZh(), item.titleEn());
            sb.append(index++).append(". [").append(StrUtil.blankToDefault(datePart(item.publishTime()), "未知日期"))
                    .append("]");
            if (StrUtil.isNotBlank(item.category())) {
                sb.append("[").append(McpToolResults.bound(item.category(), 20)).append("]");
            }
            sb.append(" ").append(McpToolResults.bound(title, TITLE_BOUND)).append("\n");
            String summary = StrUtil.blankToDefault(item.summaryZh(), item.summaryEn());
            if (StrUtil.isNotBlank(summary)) {
                sb.append("   摘要: ").append(McpToolResults.bound(summary, SUMMARY_BOUND)).append("\n");
            }
            if (StrUtil.isNotBlank(item.url())) {
                sb.append("   原文: ").append(McpToolResults.bound(item.url(), URL_BOUND)).append("\n");
            }
        }
        if (total > shown.size()) {
            sb.append("另有 ").append(total - shown.size())
                    .append(" 条未显示；需要更多时请缩小范围（加关键词/主题/时间窗）而不是翻页。\n");
        }
        sb.append("注：摘要由 AI 生成，内容以原文链接为准。");
        return sb.toString();
    }

    private static String datePart(String publishTime) {
        if (StrUtil.isBlank(publishTime) || publishTime.length() < 10) {
            return null;
        }
        return publishTime.substring(0, 10);
    }

    /**
     * limit 边界：缺省 10；&lt;1 或非整数是无效输入（结构化错误，不静默取默认——
     * 那会把模型的笔误变成一次看似成功的全量拉取）；&gt;50 钳到 50（上限是防滥用不是防错误）
     */
    static int limit(Map<String, Object> args) {
        Object raw = args.get("limit");
        if (raw == null || StrUtil.isBlank(String.valueOf(raw))) {
            return DEFAULT_LIMIT;
        }
        Integer value = raw instanceof Number number ? number.intValue() : parseInt(String.valueOf(raw));
        if (value == null) {
            throw new McpToolException("limit 必须是 1-" + MAX_LIMIT + " 的整数");
        }
        if (value < 1) {
            throw new McpToolException("limit 不能小于 1（收到 " + value + "），合法范围 1-" + MAX_LIMIT);
        }
        return Math.min(value, MAX_LIMIT);
    }

    private static Integer parseInt(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static LocalDate parseDate(String name, String raw) {
        String value = StrUtil.trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new McpToolException(name + " 格式必须是 YYYY-MM-DD（收到 " + McpToolResults.bound(value, 20) + "）");
        }
    }
}
