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

package com.nageoffer.ai.ragent.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.share.ShareSnapshotService;
import com.nageoffer.ai.ragent.user.schema.PgSchemaFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 硬删级联单元测试（U2）：语句面覆盖（两类对话表族全删、记忆游标与工具调用审计随删、反馈匿名化、
 * 分享撤销、邮箱墓碑 upsert、用户行删除），以及覆盖断言——现行 schema 里每张带 user_id 列的表
 * 都出现在级联发出的 SQL 里。对库的数据形态实证由可选真库集成测试
 * {@link AccountDeletionCascadeIntegrationTest} 承担。
 */
class AccountDeletionCascadeTest {

    private JdbcTemplate jdbcTemplate;
    private ShareSnapshotService shareSnapshotService;
    private AccountDeletionCascade cascade;
    private MockedStatic<StpUtil> stpUtil;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        shareSnapshotService = mock(ShareSnapshotService.class);
        cascade = new AccountDeletionCascade(jdbcTemplate, shareSnapshotService);
        stpUtil = mockStatic(StpUtil.class);
    }

    @AfterEach
    void tearDown() {
        stpUtil.close();
    }

    @Test
    void purgeTouchesEveryCascadeStatement() {
        cascade.purge("100", "u2@example.com");

        InOrder order = inOrder(jdbcTemplate, shareSnapshotService);
        order.verify(jdbcTemplate).update("DELETE FROM t_conversation WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_message WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_conversation WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_message WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_context_compaction WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_memory WHERE user_id = ?", "100");
        // 记忆游标与工具调用审计同样按 user_id 关联，随账号删除
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_memory_cursor WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_tool_call WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_state WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_conversation_summary WHERE user_id = ?", "100");
        // #154 级联补洞：trace 两表随账号硬删（node 经 run 关联先删，防孤儿 node）
        order.verify(jdbcTemplate).update(containsSql("DELETE FROM t_rag_trace_node"), eq("100"));
        order.verify(jdbcTemplate).update("DELETE FROM t_rag_trace_run WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update(containsSql("UPDATE t_message_feedback"), eq("100"));
        // 分享撤销（issue #124 起两粒度统一走 ShareSnapshotService，软撤销行保留）
        order.verify(shareSnapshotService).revokeOwnedBy("100");
        order.verify(jdbcTemplate).update(containsSql("INSERT INTO t_user_email_tombstone"),
                anyString(), anyString(), eq("100"), org.mockito.ArgumentMatchers.any());
        order.verify(jdbcTemplate).update("DELETE FROM t_user WHERE id = ?", "100");
        stpUtil.verify(() -> StpUtil.logout("100"));
    }

    @Test
    void purgeStoresSha256EmailDigestAsTombstone() throws Exception {
        cascade.purge("100", "u2@example.com");

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate).update(containsSql("INSERT INTO t_user_email_tombstone"),
                anyString(), captor.capture(), eq("100"), org.mockito.ArgumentMatchers.any());
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("u2@example.com".getBytes()));
        assertEquals(expected, captor.getValue());
    }

    @Test
    void purgeWithoutEmailSkipsTombstone() {
        cascade.purge("100", null);

        verify(jdbcTemplate, never()).update(containsSql("t_user_email_tombstone"), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(jdbcTemplate).update("DELETE FROM t_user WHERE id = ?", "100");
    }

    @Test
    void purgeAnonymizesFeedbackToSentinelUser() {
        cascade.purge("100", null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .update(sqlCaptor.capture(), org.mockito.ArgumentMatchers.any(Object[].class));
        List<String> captured = sqlCaptor.getAllValues();
        assertTrue(captured.stream().anyMatch(sql -> sql.contains("t_message_feedback") && sql.contains("user_id = '0'")),
                "反馈匿名化必须指向哨兵 user_id=0，实际 SQL：" + captured);
    }

    @Test
    void purgeTouchesEveryTableWithUserIdColumnInCurrentSchema() throws IOException {
        Map<String, List<String>> schema = PgSchemaFile.parseTables(PgSchemaFile.locate());
        // 解析下限：解析失配（比如建表换了写法）时先在这里红，覆盖断言不许落到空集合上空过
        assertThat(schema).as("schema_pg.sql 解析出的表数").hasSizeGreaterThanOrEqualTo(56);
        List<String> userIdTables = schema.entrySet().stream()
                .filter(table -> table.getValue().contains("user_id"))
                .map(Map.Entry::getKey)
                .toList();
        assertThat(userIdTables).as("带 user_id 列的表数").hasSizeGreaterThanOrEqualTo(13);

        cascade.purge("100", "u2@example.com");

        List<String> untouched = userIdTables.stream()
                .filter(table -> issuedSql().stream().noneMatch(sql -> mentionsTable(sql, table)))
                .toList();
        assertThat(untouched)
                .as("现行 schema 里每张带 user_id 列的表都必须出现在级联发出的 SQL 里，未触达的表：")
                .isEmpty();
    }

    /**
     * 级联发给 JdbcTemplate 的全部 SQL：取该 mock 上所有调用的 String 实参，不绑定具体重载——
     * 经 update / execute / batchUpdate 任一重载发出的 SQL 都在捕获范围内
     */
    private List<String> issuedSql() {
        return mockingDetails(jdbcTemplate).getInvocations().stream()
                .flatMap(invocation -> Arrays.stream(invocation.getArguments()))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    /**
     * 表名以整词出现才算数：t_conversation 是 t_agent_conversation 的子串、t_agent_memory 是
     * t_agent_memory_cursor 的子串，裸 contains 会让缺语句的表借同后缀的表过关
     */
    private static boolean mentionsTable(String sql, String table) {
        return Arrays.stream(sql.split("[^A-Za-z0-9_]+")).anyMatch(table::equals);
    }

    private static String containsSql(String fragment) {
        return org.mockito.ArgumentMatchers.contains(fragment);
    }
}
