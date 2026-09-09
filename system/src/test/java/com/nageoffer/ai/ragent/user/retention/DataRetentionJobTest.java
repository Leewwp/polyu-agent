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

package com.nageoffer.ai.ragent.user.retention;

import com.nageoffer.ai.ragent.user.service.impl.AccountDeletionCascade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 保留期清理任务单元测试（U6）：注入固定/拨动 Clock 验证各类清理的时间旅行口径（cutoff=now-保留天数）、
 * SQL 语句面与级联复用。cutoff SQL 的行选择性由本地库 psql 抽样核验承担（29/31 天影子行）。
 */
class DataRetentionJobTest {

    /** 2026-09-10 00:00 UTC 基准钟：cutoff 全部可由它减整天数推出 */
    private static final Instant NOW = Instant.parse("2026-09-10T00:00:00Z");

    private static final long DAY_SECONDS = 24L * 3600;

    private JdbcTemplate jdbcTemplate;
    private AccountDeletionCascade deletionCascade;
    private DataRetentionProperties properties;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        deletionCascade = mock(AccountDeletionCascade.class);
        properties = new DataRetentionProperties();
    }

    private DataRetentionJob jobWithClock(Instant instant) {
        return new DataRetentionJob(jdbcTemplate, deletionCascade, properties,
                Clock.fixed(instant, ZoneId.systemDefault()));
    }

    @Test
    void disabledSwitchSkipsAllCleanup() {
        properties.setEnabled(false);

        jobWithClock(NOW).sweep();

        verifyNoInteractions(jdbcTemplate, deletionCascade);
    }

    @Test
    @SuppressWarnings("unchecked")
    void purgesSoftDeletedAccountsWhoseGraceExpired() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new DataRetentionJob.SoftDeletedAccount("100", "a@example.com"),
                        new DataRetentionJob.SoftDeletedAccount("101", "b@example.com")));

        jobWithClock(NOW).sweep();

        // 查询面：限定软删态（deleted=1 且 delete_time 非空）+ 批上限；cutoff=now-30 天（冷静期）
        verify(jdbcTemplate).query(
                org.mockito.AdditionalMatchers.and(contains("deleted = 1"), contains("LIMIT")),
                any(RowMapper.class), eq(Timestamp.from(NOW.minusSeconds(30 * DAY_SECONDS))));
        verify(deletionCascade).purge("100", "a@example.com");
        verify(deletionCascade).purge("101", "b@example.com");
    }

    @Test
    @SuppressWarnings("unchecked")
    void purgesGuestsByCreateTimeWithoutEmail() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of("900", "901"));

        jobWithClock(NOW).sweep();

        verify(jdbcTemplate).query(contains("role = 'guest'"), any(RowMapper.class),
                eq(Timestamp.from(NOW.minusSeconds(30 * DAY_SECONDS))));
        verify(deletionCascade).purge("900", null);
        verify(deletionCascade).purge("901", null);
    }

    @Test
    void deletesExpiredSharesByExpireTime() {
        jobWithClock(NOW).sweep();

        verify(jdbcTemplate).update(contains("DELETE FROM t_answer_share"), eq(Timestamp.from(NOW)));
    }

    @Test
    void deletesFeedbackOlderThan400Days() {
        jobWithClock(NOW).sweep();

        verify(jdbcTemplate).update(contains("DELETE FROM t_message_feedback"),
                eq(Timestamp.from(NOW.minusSeconds(400 * DAY_SECONDS))));
    }

    @Test
    void deletesExpiredTombstonesByExpireTime() {
        jobWithClock(NOW).sweep();

        verify(jdbcTemplate).update(contains("DELETE FROM t_user_email_tombstone"),
                eq(Timestamp.from(NOW)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void accountPurgeFailureDoesNotStopRowDeletions() {
        // 首个 query（软删查询）返回一行，其级联 purge 抛异常；第二个 query（guest）返回空
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new DataRetentionJob.SoftDeletedAccount("100", "a@example.com")),
                        List.of());
        doThrow(new IllegalStateException("boom")).when(deletionCascade).purge(anyString(), any());

        jobWithClock(NOW).sweep();

        verify(jdbcTemplate).update(contains("DELETE FROM t_answer_share"), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("DELETE FROM t_message_feedback"), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("DELETE FROM t_user_email_tombstone"), any(Timestamp.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void timeTravelClockShiftsEveryCutoff() {
        // guest 保留期调成 45 天与冷静期 30 天区分锚值；时钟前拨 31 天后，各类 cutoff 必须随拨动平移
        properties.setGuestRetentionDays(45);
        Instant travelled = NOW.plusSeconds(31 * DAY_SECONDS);

        jobWithClock(travelled).sweep();

        Timestamp shiftedGuestAnchor = Timestamp.from(travelled.minusSeconds(45 * DAY_SECONDS));
        ArgumentCaptor<Object> queryAnchorCaptor = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).query(anyString(), any(RowMapper.class),
                queryAnchorCaptor.capture());
        assertTrue(queryAnchorCaptor.getAllValues().contains(shiftedGuestAnchor),
                "拨钟后 guest cutoff 应为拨后 now-45d，实际：" + queryAnchorCaptor.getAllValues());
        verify(jdbcTemplate).update(contains("DELETE FROM t_message_feedback"),
                eq(Timestamp.from(travelled.minusSeconds(400 * DAY_SECONDS))));
        verify(jdbcTemplate).update(contains("DELETE FROM t_answer_share"), eq(Timestamp.from(travelled)));
    }
}
