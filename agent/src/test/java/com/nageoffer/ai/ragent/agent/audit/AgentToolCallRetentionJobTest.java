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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 保留 90 天（#486 验收 7）：now−91d 删、now−89d 留；单类失败不影响其他清理类
 * （独立 @Scheduled 类 + 内部捕获，参照 DataRetentionJob 语义）。真库行选择性沿用
 * AgentDashboardReaderTest 的可选 PostgreSQL 临时表模式（系统属性门控，CI 缺省跳过）。
 */
class AgentToolCallRetentionJobTest {

    /** 2026-11-12 00:00 UTC 基准钟：cutoff 全部由它减整天数推出 */
    private static final Instant NOW = Instant.parse("2026-11-12T00:00:00Z");

    @Test
    void sweepsRowsOlderThan90DaysOnly() throws Exception {
        String url = System.getProperty("ragent.audit.test.jdbc-url");
        assumeTrue(url != null, "Set ragent.audit.test.jdbc-url to run PostgreSQL integration test");
        try (var connection = DriverManager.getConnection(url)) {
            connection.setAutoCommit(false);
            try {
                JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                jdbc.execute("""
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
                Timestamp minus91d = Timestamp.from(NOW.minusSeconds(91 * 24 * 3600));
                Timestamp minus89d = Timestamp.from(NOW.minusSeconds(89 * 24 * 3600));
                jdbc.update("INSERT INTO t_agent_tool_call (id, conversation_id, user_id, message_id, "
                                + "block_index, tool_name, decision, status, create_time) VALUES "
                                + "('1','c','u','m',0,'search_knowledge','ALLOW','done',?),"
                                + "('2','c','u','m',1,'search_knowledge','ALLOW','done',?)",
                        minus91d, minus89d);

                new AgentToolCallRetentionJob(jdbc, Clock.fixed(NOW, ZoneId.systemDefault())).sweep();

                var survivors = jdbc.queryForList(
                        "SELECT id FROM t_agent_tool_call ORDER BY id", String.class);
                // 91 天前的删、89 天前的留
                assertThat(survivors).containsExactly("2");
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void cutoffIsExactly90DaysBeforeNow() {
        var jdbc = mock(JdbcTemplate.class);
        Clock clock = Clock.fixed(NOW, ZoneId.systemDefault());

        new AgentToolCallRetentionJob(jdbc, clock).sweep();

        verify(jdbc).update(contains("DELETE FROM t_agent_tool_call"),
                eq(Timestamp.from(NOW.minusSeconds(90 * 24 * 3600))));
    }

    @Test
    void sweepFailureIsContainedInsideTheJob() {
        var jdbc = mock(JdbcTemplate.class);
        doThrow(new IllegalStateException("db down")).when(jdbc).update(contains("DELETE"), any(Timestamp.class));
        AgentToolCallRetentionJob job = new AgentToolCallRetentionJob(jdbc, Clock.fixed(NOW, ZoneId.systemDefault()));

        // 异常不出 job：调度线程与其余清理类（DataRetentionJob 等）不受影响
        assertThat(job).isNotNull();
        job.sweep();
        verify(jdbc).update(contains("DELETE FROM t_agent_tool_call"), any(Timestamp.class));
    }
}
