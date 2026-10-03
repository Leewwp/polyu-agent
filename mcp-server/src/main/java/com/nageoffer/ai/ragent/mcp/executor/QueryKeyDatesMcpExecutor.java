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

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.integer;
import static com.nageoffer.ai.ragent.mcp.executor.McpToolSchema.string;

/**
 * 校历关键日期查询工具（issue #214，依赖 #193 已上线的公开查询口）：deadline、
 * 学期起止、考试期、缴费截止等关键日期。数据访问复用 /public/calendar/key-dates
 * 看板（无参数整板），分段选择与关键词过滤在本工具侧做——不另造查询口、不落库。
 */
@Slf4j
@Component
public class QueryKeyDatesMcpExecutor {

    /**
     * 工具 ID 三处逐字符一致：本常量、意图树 MCP 节点 mcp_tool_id、技能表 tool_ids
     */
    public static final String TOOL_NAME = "query_key_dates";

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 50;

    /** 与 #193 KeyDateBoardVO 的四段对齐：值即字段名，避免再加一层映射表 */
    static final String SEGMENT_CURRENT = "current_and_upcoming";
    static final String SEGMENT_RECENT_PAST = "recent_past";
    static final String SEGMENT_ARCHIVED = "archived";
    static final String SEGMENT_UNDATED = "undated";
    static final List<String> SEGMENTS = List.of(SEGMENT_CURRENT, SEGMENT_RECENT_PAST,
            SEGMENT_ARCHIVED, SEGMENT_UNDATED);

    private static final Map<String, String> SEGMENT_LABELS = Map.of(
            SEGMENT_CURRENT, "进行中+即将到来",
            SEGMENT_RECENT_PAST, "近期已过期",
            SEGMENT_ARCHIVED, "往期归档",
            SEGMENT_UNDATED, "未定具体日期");

    private static final int TITLE_BOUND = 120;
    private static final int URL_BOUND = 200;

    private final RagPublicApi ragPublicApi;

    public QueryKeyDatesMcpExecutor(RagPublicApi ragPublicApi) {
        this.ragPublicApi = ragPublicApi;
    }

    @Bean
    public McpServerFeatures.SyncToolSpecification queryKeyDatesToolSpecification() {
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(buildTool())
                .callHandler((exchange, request) -> handleCall(request))
                .build();
    }

    private Tool buildTool() {
        JsonSchema inputSchema = McpToolSchema.object()
                .optional(string("keyword", "标题过滤关键词（中英不区分大小写子串），如「缴费」「add/drop」「exam」；查特定事项时传入")
                        .title("关键词"))
                .optional(string("segment", "取哪一段看板：current_and_upcoming=进行中+即将到来（默认，临近度排序）、"
                        + "recent_past=近期已过期、archived=往期归档、undated=未定具体日期的模糊窗口")
                        .title("分段")
                        .options(SEGMENTS)
                        .defaultTo(SEGMENT_CURRENT))
                .optional(integer("limit", "返回条数上限，1-50，默认 10")
                        .title("条数上限")
                        .defaultTo(DEFAULT_LIMIT))
                .build();

        return Tool.builder()
                .name(TOOL_NAME)
                .description("查询香港理工大学校历关键日期（deadline）：学期起止、Add/Drop、考试期、"
                        + "成绩发布、缴费截止等，按临近度排序。用户问「XX 截止日期是什么时候」「下学期几号开学」"
                        + "「最近有什么 deadline」时调用")
                .inputSchema(inputSchema)
                .annotations(McpToolAnnotations.READ_ONLY)
                .build();
    }

    CallToolResult handleCall(CallToolRequest request) {
        long startMs = System.currentTimeMillis();
        try {
            Map<String, Object> args = McpToolResults.args(request);
            String keyword = StrUtil.trimToNull(MapUtil.getStr(args, "keyword"));
            String segment = segment(args);
            int limit = SearchNewsMcpExecutor.limit(args);

            RagPublicApi.KeyDateBoard board = ragPublicApi.keyDateBoard();

            String result = render(keyword, segment, limit, board);
            log.info("MCP 工具调用完成, toolId={}, segment={}, keyword={}, elapsed={}ms",
                    TOOL_NAME, segment, keyword, System.currentTimeMillis() - startMs);
            return McpToolResults.success(result);
        } catch (Exception e) {
            log.error("MCP 工具调用失败, toolId={}, elapsed={}ms",
                    TOOL_NAME, System.currentTimeMillis() - startMs, e);
            return McpToolResults.failure("校历查询", e);
        }
    }

    private String render(String keyword, String segment, int limit, RagPublicApi.KeyDateBoard board) {
        List<RagPublicApi.KeyDate> pool = poolOf(segment, board);
        if (keyword != null) {
            pool = pool.stream().filter(item -> titleMatches(item, keyword)).toList();
        }
        List<RagPublicApi.KeyDate> shown = pool.stream().limit(limit).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("校历关键日期（").append(SEGMENT_LABELS.get(segment))
                .append("；学年覆盖 ").append(StrUtil.blankToDefault(board.coverageAcademicYear(), "未知"))
                .append("；分类锚点 ").append(StrUtil.blankToDefault(board.today(), "未知")).append("）：\n");
        // 源三态合同：退化/隔离时看板按最后完整版本呈现，必须原样告知，不得冒充最新
        if (Boolean.TRUE.equals(board.anySourceAbnormal())) {
            sb.append("注意：部分数据源当前异常，以下内容按最后完整同步版本呈现（截至 ")
                    .append(StrUtil.blankToDefault(board.lastFullSyncAt(), "未知时间")).append("）。\n");
        }
        if (shown.isEmpty()) {
            sb.append("没有匹配的关键日期记录。可去掉关键词重试，或换一个分段（")
                    .append(String.join(" / ", SEGMENTS)).append("）。");
            return sb.toString();
        }
        int index = 1;
        for (RagPublicApi.KeyDate item : shown) {
            String title = StrUtil.blankToDefault(item.titleZh(), item.titleEn());
            sb.append(index++).append(". ");
            if (StrUtil.isNotBlank(item.term())) {
                sb.append("[").append(McpToolResults.bound(item.term(), 10)).append("] ");
            }
            sb.append(McpToolResults.bound(title, TITLE_BOUND)).append("\n");
            sb.append("   日期: ").append(dateText(item)).append("\n");
            // #193 合同 §2：官方人群限制原文不得省略
            if (StrUtil.isNotBlank(item.audienceText())) {
                sb.append("   适用: ").append(McpToolResults.bound(item.audienceText(), 160)).append("\n");
            }
            if (StrUtil.isNotBlank(item.sourceUrl())) {
                sb.append("   官方来源: ").append(McpToolResults.bound(item.sourceUrl(), URL_BOUND)).append("\n");
            }
        }
        if (pool.size() > shown.size()) {
            sb.append("本段共 ").append(pool.size()).append(" 条匹配，显示前 ").append(shown.size())
                    .append(" 条；需要更多时请用更具体的关键词。\n");
        }
        if (SEGMENT_ARCHIVED.equals(segment) && board.archivedTotal() != null
                && board.archivedTotal() > pool.size()) {
            sb.append("往期归档全量 ").append(board.archivedTotal()).append(" 条（看板已封顶），仅统计未全列。\n");
        }
        sb.append("注：日期以官方来源为准，模糊窗口（如 Late October）不代换成具体日。");
        return sb.toString();
    }

    private static List<RagPublicApi.KeyDate> poolOf(String segment, RagPublicApi.KeyDateBoard board) {
        return switch (segment) {
            case SEGMENT_RECENT_PAST -> board.safeSegment(board.recentPast());
            case SEGMENT_ARCHIVED -> board.safeSegment(board.archived());
            case SEGMENT_UNDATED -> board.safeSegment(board.undated());
            default -> board.safeSegment(board.currentAndUpcoming());
        };
    }

    /**
     * 模糊窗口不伪造具体日：fuzzy 行只透出原文窗口（fuzzyHint），不拼 dateStart
     */
    private static String dateText(RagPublicApi.KeyDate item) {
        String days = daysText(item);
        if (StrUtil.isNotBlank(item.dateStart())) {
            String range = StrUtil.isNotBlank(item.dateEnd())
                    ? item.dateStart() + " ~ " + item.dateEnd()
                    : item.dateStart();
            return range + (days == null ? "" : "（" + days + "）");
        }
        if (StrUtil.isNotBlank(item.fuzzyHint())) {
            return McpToolResults.bound(item.fuzzyHint(), 80) + "（未定具体日期）";
        }
        return "见官方来源" + (days == null ? "" : "（" + days + "）");
    }

    private static String daysText(RagPublicApi.KeyDate item) {
        Integer daysUntil = item.daysUntil();
        if (daysUntil == null) {
            return null;
        }
        if (daysUntil == 0) {
            return "今天";
        }
        return daysUntil > 0 ? daysUntil + " 天后" : "已过 " + Math.abs(daysUntil) + " 天";
    }

    private static boolean titleMatches(RagPublicApi.KeyDate item, String keyword) {
        return containsIgnoreCase(item.titleZh(), keyword) || containsIgnoreCase(item.titleEn(), keyword);
    }

    private static boolean containsIgnoreCase(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String segment(Map<String, Object> args) {
        String value = StrUtil.trimToNull(MapUtil.getStr(args, "segment"));
        if (value == null) {
            return SEGMENT_CURRENT;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!SEGMENTS.contains(normalized)) {
            throw new McpToolException("segment 取值必须是 " + String.join(" / ", SEGMENTS)
                    + " 之一（收到 " + McpToolResults.bound(value, 20) + "）");
        }
        return normalized;
    }
}
