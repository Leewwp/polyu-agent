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

package com.nageoffer.ai.ragent.news.retain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保留期清理测试：DELETE 走 WHERE id IN (SELECT … LIMIT n) 注意点、
 * 批上限常量在 SQL、cutoff=retention-days（90 天）前的发布时间。
 */
class NewsRetentionJobTests {

    private JdbcTemplate jdbcTemplate;
    private NewsRetentionJob job;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneId.of("UTC"));
        job = new NewsRetentionJob(jdbcTemplate, clock, 90);
    }

    @Test
    void purgeDeletesBySubqueryWithBatchCapAndCutoff() {
        when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(3);

        job.purgeExpiredItems();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbcTemplate).update(sql.capture(), cutoff.capture());
        assertTrue(sql.getValue().contains("DELETE FROM t_news_item WHERE id IN"),
                "PG 不支持 DELETE LIMIT，必须走 IN 子查询");
        assertTrue(sql.getValue().contains("SELECT id FROM t_news_item WHERE publish_time < ?"));
        assertTrue(sql.getValue().contains("LIMIT " + NewsRetentionJob.DELETE_BATCH));
        assertEquals(Timestamp.from(Instant.parse("2026-06-13T00:00:00Z")), cutoff.getValue(),
                "cutoff = now - 90 天");
    }

    @Test
    void purgeWithNoExpiredRowsIsSilentSuccess() {
        when(jdbcTemplate.update(anyString(), any(Timestamp.class))).thenReturn(0);

        job.purgeExpiredItems();

        verify(jdbcTemplate).update(anyString(), any(Timestamp.class));
    }
}
