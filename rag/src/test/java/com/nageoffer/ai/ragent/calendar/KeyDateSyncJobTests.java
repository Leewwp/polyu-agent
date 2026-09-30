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

package com.nageoffer.ai.ragent.calendar;

import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateSourceDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateSourceMapper;
import com.nageoffer.ai.ragent.calendar.schedule.KeyDateSyncJob;
import com.nageoffer.ai.ragent.calendar.sync.KeyDateSyncService;
import com.nageoffer.ai.ragent.news.fetch.NewsHttpFetchClient;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调度分流测试：隔离期探测不发布 / 退化走零写路径 / 完整走原子发布 /
 * verifier 零发布 / 抓取失败按退化处理 / 人工停用源整轮跳过（合同§6）。
 * HTML 用真实快照 fixture（解析面已在别处对账），这里只测 job 分流。
 */
class KeyDateSyncJobTests {

    private static final String CAC_HTML = readSnap("cal-academic-calendar");
    private static final String CET_HTML = readSnap("cal-exam-timetable");

    private static String readSnap(String key) {
        try (java.io.InputStream in = KeyDateSyncJobTests.class.getResourceAsStream(
                "/fixtures/calendar/snapshots/" + key + ".html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyDateSourceDO source(String key, String role, String autoState) {
        return KeyDateSourceDO.builder()
                .sourceKey(key)
                .sourceUrl(com.nageoffer.ai.ragent.calendar.parse.CalendarLexicon.SOURCE_URLS.get(key))
                .role(role)
                .enabled("1")
                .autoState(autoState)
                .degradedStreak(0)
                .probeOkStreak(0)
                .build();
    }

    @Test
    void completeWriterPublishesAtomically() {
        KeyDateSourceMapper sourceMapper = mock(KeyDateSourceMapper.class);
        KeyDateSyncService syncService = mock(KeyDateSyncService.class);
        NewsHttpFetchClient fetchClient = mock(NewsHttpFetchClient.class);
        when(fetchClient.get(anyString())).thenReturn(CAC_HTML.getBytes(StandardCharsets.UTF_8));
        KeyDateSyncJob job = new KeyDateSyncJob(sourceMapper, syncService, fetchClient);

        job.syncSource(source("cal-academic-calendar", "writer", "active"));
        verify(syncService, times(1)).applyComplete(any(), eq("2026/27"), anyList());
        verify(syncService, never()).applyDegraded(any(), anyList());
    }

    @Test
    void isolatedSourceProbesWithoutPublishing() {
        KeyDateSourceMapper sourceMapper = mock(KeyDateSourceMapper.class);
        KeyDateSyncService syncService = mock(KeyDateSyncService.class);
        NewsHttpFetchClient fetchClient = mock(NewsHttpFetchClient.class);
        when(fetchClient.get(anyString())).thenReturn(CAC_HTML.getBytes(StandardCharsets.UTF_8));
        KeyDateSyncJob job = new KeyDateSyncJob(sourceMapper, syncService, fetchClient);

        job.syncSource(source("cal-academic-calendar", "writer", "auto_isolated"));
        verify(syncService).recordProbe(any(), eq(false));
        verify(syncService, never()).applyComplete(any(), anyString(), anyList());
        verify(syncService, never()).applyDegraded(any(), anyList());
    }

    @Test
    void verifierNeverPublishes() {
        KeyDateSourceMapper sourceMapper = mock(KeyDateSourceMapper.class);
        KeyDateSyncService syncService = mock(KeyDateSyncService.class);
        NewsHttpFetchClient fetchClient = mock(NewsHttpFetchClient.class);
        when(fetchClient.get(anyString())).thenReturn(CET_HTML.getBytes(StandardCharsets.UTF_8));
        KeyDateSyncJob job = new KeyDateSyncJob(sourceMapper, syncService, fetchClient);

        job.syncSource(source("cal-exam-timetable", "verifier", "active"));
        verify(syncService, never()).applyComplete(any(), anyString(), anyList());
        verify(syncService).recordDiscrepancies(any(), anyList());
        verify(syncService).recordProbe(any(), eq(false));
    }

    @Test
    void fetchFailureCountsAsDegradedRound() {
        KeyDateSourceMapper sourceMapper = mock(KeyDateSourceMapper.class);
        KeyDateSyncService syncService = mock(KeyDateSyncService.class);
        NewsHttpFetchClient fetchClient = mock(NewsHttpFetchClient.class);
        when(sourceMapper.selectList(any())).thenReturn(java.util.List.of(
                source("cal-academic-calendar", "writer", "active")));
        when(fetchClient.get(anyString())).thenThrow(
                new com.nageoffer.ai.ragent.news.fetch.NewsFetchException("HTTP 503", true));
        KeyDateSyncJob job = new KeyDateSyncJob(sourceMapper, syncService, fetchClient);

        job.syncAllSources();
        verify(syncService).applyDegraded(any(), anyList());
        verify(syncService, never()).applyComplete(any(), anyString(), anyList());
    }

    @Test
    void syncRoundSkipsDisabledSourcesAndIsolatesPerSourceFailure() {
        KeyDateSourceMapper sourceMapper = mock(KeyDateSourceMapper.class);
        KeyDateSyncService syncService = mock(KeyDateSyncService.class);
        NewsHttpFetchClient fetchClient = mock(NewsHttpFetchClient.class);
        // enabled=0 人工停用源在 selectList(enabled=1) 面就被排除——job 只取启用源；
        // 第二源抓取失败不影响第一源已发布（逐源隔离）
        when(sourceMapper.selectList(any())).thenReturn(java.util.List.of(
                source("cal-academic-calendar", "writer", "active"),
                source("cal-assessment-results", "writer", "active")));
        when(fetchClient.get(anyString()))
                .thenReturn(CAC_HTML.getBytes(StandardCharsets.UTF_8))
                .thenThrow(new com.nageoffer.ai.ragent.news.fetch.NewsFetchException("IO: reset", true));
        KeyDateSyncJob job = new KeyDateSyncJob(sourceMapper, syncService, fetchClient);

        job.syncAllSources();
        verify(syncService, times(1)).applyComplete(any(), eq("2026/27"), anyList());
        verify(syncService, times(1)).applyDegraded(any(), anyList());
    }

    /**
     * #195 审核修正 P3：catch 面内退化记账自身失败（如 DB 抖动）不得逃逸中断
     * 本轮其余源——记账异常只记日志，后续源照常同步
     */
    @Test
    void degradedBookkeepingFailureDoesNotBreakRemainingSources() {
        KeyDateSourceMapper sourceMapper = mock(KeyDateSourceMapper.class);
        KeyDateSyncService syncService = mock(KeyDateSyncService.class);
        NewsHttpFetchClient fetchClient = mock(NewsHttpFetchClient.class);
        when(sourceMapper.selectList(any())).thenReturn(java.util.List.of(
                source("cal-assessment-results", "writer", "active"),
                source("cal-academic-calendar", "writer", "active")));
        when(fetchClient.get(anyString()))
                .thenThrow(new com.nageoffer.ai.ragent.news.fetch.NewsFetchException("HTTP 503", true))
                .thenReturn(CAC_HTML.getBytes(StandardCharsets.UTF_8));
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(syncService).applyDegraded(any(), anyList());
        KeyDateSyncJob job = new KeyDateSyncJob(sourceMapper, syncService, fetchClient);

        job.syncAllSources();
        // 第一源退化记账抛错被吞（不逃逸）；第二源仍走完发布——本轮未被中断
        verify(syncService).applyDegraded(any(), anyList());
        verify(syncService, times(1)).applyComplete(any(), eq("2026/27"), anyList());
    }
}
