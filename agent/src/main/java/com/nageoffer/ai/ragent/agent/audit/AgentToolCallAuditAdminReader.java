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

package com.nageoffer.ai.ragent.agent.audit;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * 工具调用审计管理端读模型（#486 最简化）：四维过滤+分页，聚合在 PG 内完成。
 * 返回 Page 以对齐 BizChangeLogController 的前端分页契约（records/total/current/pages）。
 */
@Repository
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentToolCallAuditAdminReader {

    /**
     * 可空过滤参一律 CAST 定型：PG 对未定类型的 null 参数（? IS NULL）报 could not determine data type
     */
    private static final String BASE_WHERE = """
            WHERE (CAST(:userId AS varchar) IS NULL OR user_id = :userId)
              AND (CAST(:toolName AS varchar) IS NULL OR tool_name = :toolName)
              AND (CAST(:decision AS varchar) IS NULL OR decision = :decision)
              AND (CAST(:beginTime AS timestamp) IS NULL OR create_time >= :beginTime)
              AND (CAST(:endTime AS timestamp) IS NULL OR create_time < :endTime)
            """;

    private static final String SELECT_SQL = "SELECT id, conversation_id, user_id, role, message_id, block_index, "
            + "run_id, tool_name, tool_call_id, batch_id, decision, status, input_digest, input_excerpt, "
            + "duration_ms, create_time FROM t_agent_tool_call " + BASE_WHERE
            + " ORDER BY create_time DESC, id DESC LIMIT :limit OFFSET :offset";

    private static final String COUNT_SQL = "SELECT count(*) FROM t_agent_tool_call " + BASE_WHERE;

    private final NamedParameterJdbcTemplate jdbc;

    public Page<AgentToolCallAuditVO> page(AgentToolCallAuditQueryRequest request) {
        MapSqlParameterSource params = paramsOf(request);
        Long total = jdbc.queryForObject(COUNT_SQL, params, Long.class);
        List<AgentToolCallAuditVO> records = jdbc.query(SELECT_SQL, params, (rs, rowNum) -> new AgentToolCallAuditVO(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getString("user_id"),
                rs.getString("role"),
                rs.getString("message_id"),
                rs.getInt("block_index"),
                rs.getString("run_id"),
                rs.getString("tool_name"),
                rs.getString("tool_call_id"),
                rs.getString("batch_id"),
                rs.getString("decision"),
                rs.getString("status"),
                rs.getString("input_digest"),
                rs.getString("input_excerpt"),
                rs.getObject("duration_ms") == null ? null : rs.getLong("duration_ms"),
                rs.getTimestamp("create_time")));

        Page<AgentToolCallAuditVO> page = new Page<>(request.getCurrent(), request.getSize());
        page.setTotal(total == null ? 0 : total);
        page.setRecords(records);
        return page;
    }

    private MapSqlParameterSource paramsOf(AgentToolCallAuditQueryRequest request) {
        long size = Math.min(Math.max(request.getSize(), 1), 100);
        long current = Math.max(request.getCurrent(), 1);
        return new MapSqlParameterSource()
                .addValue("userId", StrUtil.blankToDefault(request.getUserId(), null))
                .addValue("toolName", StrUtil.blankToDefault(request.getToolName(), null))
                .addValue("decision", decisionOf(request.getDecision()))
                .addValue("beginTime", request.getBeginTime() == null ? null
                        : new Timestamp(request.getBeginTime().getTime()))
                .addValue("endTime", request.getEndTime() == null ? null
                        : new Timestamp(request.getEndTime().getTime()))
                .addValue("limit", size)
                .addValue("offset", (current - 1) * size);
    }

    /**
     * decision 收敛到七值词汇（大小写不敏感），非词汇值视为不过滤
     */
    private String decisionOf(String decision) {
        if (StrUtil.isBlank(decision)) {
            return null;
        }
        try {
            return AgentToolCallDecision.valueOf(decision.trim().toUpperCase()).name();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
