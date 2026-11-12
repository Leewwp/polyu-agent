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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * admin 审计查询（#486 验收 8）：user_id/tool_name/decision/时间区间四维过滤+分页各一断言。
 * SQL 断言用 mock NamedParameterJdbcTemplate（CI 稳定）；真库行选择性与排序走可选 PG 临时表（门控跳过）。
 */
class AgentToolCallAuditQueryTest {

    private NamedParameterJdbcTemplate jdbc;
    private AgentToolCallAuditAdminReader reader;

    private void readerReturning(long total) {
        jdbc = mock(NamedParameterJdbcTemplate.class);
        reader = new AgentToolCallAuditAdminReader(jdbc);
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(total);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());
    }

    private AgentToolCallAuditQueryRequest request() {
        AgentToolCallAuditQueryRequest request = new AgentToolCallAuditQueryRequest();
        request.setCurrent(1);
        request.setSize(10);
        return request;
    }

    @SuppressWarnings("unchecked")
    private MapSqlParameterSource capturedParams(String sqlFragment) {
        ArgumentCaptor<MapSqlParameterSource> params = ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(org.mockito.ArgumentMatchers.contains(sqlFragment), params.capture(),
                any(RowMapper.class));
        return params.getValue();
    }

    @Test
    void filtersByUserId() {
        readerReturning(0);
        AgentToolCallAuditQueryRequest request = request();
        request.setUserId("u-1001");

        reader.page(request);

        MapSqlParameterSource params = capturedParams("user_id = :userId");
        assertThat(params.getValue("userId")).isEqualTo("u-1001");
    }

    @Test
    void filtersByToolName() {
        readerReturning(0);
        AgentToolCallAuditQueryRequest request = request();
        request.setToolName("search_knowledge");

        reader.page(request);

        assertThat(capturedParams("tool_name = :toolName").getValue("toolName"))
                .isEqualTo("search_knowledge");
    }

    @Test
    void filtersByDecision() {
        readerReturning(0);
        AgentToolCallAuditQueryRequest request = request();
        request.setDecision("ASK_DENIED");

        reader.page(request);

        assertThat(capturedParams("decision = :decision").getValue("decision")).isEqualTo("ASK_DENIED");
    }

    @Test
    void filtersByTimeRange() {
        readerReturning(0);
        AgentToolCallAuditQueryRequest request = request();
        request.setBeginTime(Date.from(Instant.parse("2026-11-01T00:00:00Z")));
        request.setEndTime(Date.from(Instant.parse("2026-11-12T00:00:00Z")));

        reader.page(request);

        MapSqlParameterSource lower = capturedParams("create_time >= :beginTime");
        assertThat(lower.getValue("beginTime")).isEqualTo(Timestamp.from(Instant.parse("2026-11-01T00:00:00Z")));
        MapSqlParameterSource upper = capturedParams("create_time < :endTime");
        assertThat(upper.getValue("endTime")).isEqualTo(Timestamp.from(Instant.parse("2026-11-12T00:00:00Z")));
    }

    @Test
    void paginatesByCurrentAndSize() {
        readerReturning(23);
        AgentToolCallAuditQueryRequest request = request();
        request.setCurrent(3);
        request.setSize(10);

        var page = reader.page(request);

        MapSqlParameterSource params = capturedParams("LIMIT :limit OFFSET :offset");
        assertThat(params.getValue("limit")).isEqualTo(10L);
        assertThat(params.getValue("offset")).isEqualTo(20L);
        // Page 装配：total=23、size=10 → 3 页，前端契约字段齐
        assertThat(page.getTotal()).isEqualTo(23);
        assertThat(page.getPages()).isEqualTo(3);
        assertThat(page.getCurrent()).isEqualTo(3);
    }

    @Test
    void realPostgresFiltersSortsAndPaginates() throws Exception {
        String url = System.getProperty("ragent.audit.test.jdbc-url");
        assumeTrue(url != null, "Set ragent.audit.test.jdbc-url to run PostgreSQL integration test");
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try {
                var single = new org.springframework.jdbc.core.JdbcTemplate(
                        new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
                single.execute("""
                        CREATE TEMP TABLE t_agent_tool_call (
                            id VARCHAR(20) PRIMARY KEY,
                            conversation_id VARCHAR(20) NOT NULL,
                            user_id VARCHAR(20) NOT NULL,
                            role VARCHAR(16),
                            message_id VARCHAR(20) NOT NULL,
                            block_index SMALLINT NOT NULL,
                            run_id VARCHAR(64),
                            tool_name VARCHAR(128) NOT NULL,
                            tool_call_id VARCHAR(128),
                            batch_id VARCHAR(64),
                            decision VARCHAR(16) NOT NULL,
                            status VARCHAR(16) NOT NULL,
                            input_digest VARCHAR(64),
                            input_excerpt VARCHAR(256),
                            duration_ms BIGINT,
                            create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                        )""");
                Timestamp t1 = Timestamp.from(Instant.parse("2026-11-01T00:00:00Z"));
                Timestamp t2 = Timestamp.from(Instant.parse("2026-11-05T00:00:00Z"));
                Timestamp t3 = Timestamp.from(Instant.parse("2026-11-10T00:00:00Z"));
                single.update("INSERT INTO t_agent_tool_call (id, conversation_id, user_id, role, message_id, "
                                + "block_index, tool_name, decision, status, create_time) VALUES "
                                + "('1','c1','u-1','user','m1',0,'search_knowledge','ALLOW','done',?),"
                                + "('2','c1','u-1','user','m1',1,'search_knowledge','ERROR','failed',?),"
                                + "('3','c2','u-2','admin','m2',0,'flush_memory','ALLOW','done',?)",
                        t1, t2, t3);

                var reader = new AgentToolCallAuditAdminReader(new NamedParameterJdbcTemplate(single));
                AgentToolCallAuditQueryRequest request = new AgentToolCallAuditQueryRequest();
                request.setCurrent(1);
                request.setSize(10);
                request.setUserId("u-1");
                request.setToolName("search_knowledge");
                request.setDecision("ALLOW");

                var page = reader.page(request);

                assertThat(page.getTotal()).isEqualTo(1);
                assertThat(page.getRecords()).singleElement().satisfies(row -> {
                    assertThat(row.id()).isEqualTo("1");
                    assertThat(row.decision()).isEqualTo("ALLOW");
                    assertThat(row.role()).isEqualTo("user");
                });
            } finally {
                connection.rollback();
            }
        }
    }

    // 端点落在管理面清单的断言由 system 模块 AdminPathPatternsTest 承担（防清单漂移惯例）
}
