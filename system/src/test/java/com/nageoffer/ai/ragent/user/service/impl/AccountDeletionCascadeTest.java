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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 硬删级联单元测试（U2）：语句面覆盖（两类对话表族全删、反馈匿名化、分享撤销、
 * 邮箱墓碑 upsert、用户行删除）。数据形态的实证由本地 e2e 冒烟对库核验承担。
 */
class AccountDeletionCascadeTest {

    private JdbcTemplate jdbcTemplate;
    private AccountDeletionCascade cascade;
    private MockedStatic<StpUtil> stpUtil;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        cascade = new AccountDeletionCascade(jdbcTemplate);
        stpUtil = mockStatic(StpUtil.class);
    }

    @AfterEach
    void tearDown() {
        stpUtil.close();
    }

    @Test
    void purgeTouchesEveryCascadeStatement() {
        cascade.purge("100", "u2@example.com");

        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).update("DELETE FROM t_conversation WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_message WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_conversation WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_message WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_context_compaction WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_memory WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_memory_extraction WHERE user_id = ?", "100");
        // #104 级联补洞：三张漏清表 + agent 会话分享撤销（隐私缺陷）
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_memory_control WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_agent_state WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update("DELETE FROM t_conversation_summary WHERE user_id = ?", "100");
        order.verify(jdbcTemplate).update(containsSql("UPDATE t_message_feedback"), eq("100"));
        order.verify(jdbcTemplate).update(containsSql("UPDATE t_answer_share"), eq("100"));
        order.verify(jdbcTemplate).update(containsSql("UPDATE t_agent_conversation_share"), eq("100"));
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

    private static String containsSql(String fragment) {
        return org.mockito.ArgumentMatchers.contains(fragment);
    }
}
