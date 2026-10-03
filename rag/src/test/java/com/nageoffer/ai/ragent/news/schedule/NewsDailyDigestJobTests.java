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

package com.nageoffer.ai.ragent.news.schedule;

import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.service.NewsDailyDigestService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 日报调度任务测试（#212）：漏跑补齐窗口（近 backfill-days 日、旧→新）、
 * 已存在跳过、单日失败不阻断其余日期；cron 形状（6 段）注解断言。
 */
class NewsDailyDigestJobTests {

    private final com.nageoffer.ai.ragent.news.service.IndexNowService indexNowService =
            org.mockito.Mockito.mock(com.nageoffer.ai.ragent.news.service.IndexNowService.class);

    @Test
    void backfillsMissingDatesOldestFirst() {
        NewsDailyDigestService digestService = mock(NewsDailyDigestService.class);
        when(digestService.generateIfMissing(any())).thenReturn(true);
        NewsDailyDigestJob job = new NewsDailyDigestJob(digestService, new NewsFetchProperties(), indexNowService);
        job.generateDailyDigest();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LocalDate> dates = ArgumentCaptor.forClass(LocalDate.class);
        verify(digestService, times(2)).generateIfMissing(dates.capture());
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Hong_Kong"));
        assertEquals(List.of(today.minusDays(1), today), dates.getAllValues(),
                "默认补齐窗口=当日+昨日，旧→新顺序");
    }

    @Test
    void existingDigestSkippedAndSingleDayFailureIsolated() {
        NewsDailyDigestService digestService = mock(NewsDailyDigestService.class);
        // 昨日已存在（false）；当日生成失败——补齐不中断的验证点：失败后仍无更多调用
        when(digestService.generateIfMissing(any()))
                .thenReturn(false)
                .thenThrow(new RuntimeException("生成失败"));
        NewsDailyDigestJob job = new NewsDailyDigestJob(digestService, new NewsFetchProperties(), indexNowService);
        job.generateDailyDigest();
        verify(digestService, times(2)).generateIfMissing(any());
    }

    @Test
    void skipsWhenAllExist() {
        NewsDailyDigestService digestService = mock(NewsDailyDigestService.class);
        when(digestService.generateIfMissing(any())).thenReturn(false);
        NewsDailyDigestJob job = new NewsDailyDigestJob(digestService, new NewsFetchProperties(), indexNowService);
        job.generateDailyDigest();
        verify(digestService, times(2)).generateIfMissing(any());
    }

    /**
     * #213 IndexNow 提交钩：仅对「新生成」的日期提交（/daily + /daily/{date}），
     * 已存在的日期不提交；钩失败不阻断调度
     */
    @Test
    void indexNowHookFiresOnlyForNewlyBuiltDigests() {
        NewsDailyDigestService digestService = mock(NewsDailyDigestService.class);
        when(digestService.generateIfMissing(any())).thenReturn(false).thenReturn(true);
        NewsDailyDigestJob job = new NewsDailyDigestJob(digestService, new NewsFetchProperties(), indexNowService);
        job.generateDailyDigest();
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Hong_Kong"));
        verify(indexNowService, times(1)).submitSiteUrls(List.of("/daily", "/daily/" + today));
    }

    @Test
    void indexNowHookFailureDoesNotBreakScheduling() {
        NewsDailyDigestService digestService = mock(NewsDailyDigestService.class);
        when(digestService.generateIfMissing(any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("indexnow down"))
                .when(indexNowService).submitSiteUrls(any());
        NewsDailyDigestJob job = new NewsDailyDigestJob(digestService, new NewsFetchProperties(), indexNowService);
        job.generateDailyDigest();
        // 两个日期都生成完（钩失败只 WARN 不中断）
        verify(digestService, times(2)).generateIfMissing(any());
    }

    /**
     * cron 形状判例（NewsFetchJob @Scheduled 六段 cron）：默认 08:40——
     * 08:00 采集轮之后、与 08:30 探活错峰
     */
    @Test
    void scheduledCronIsSixFieldAndStaggeredAt0840() throws Exception {
        var method = NewsDailyDigestJob.class.getMethod("generateDailyDigest");
        var scheduled = method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertEquals("${rag.news.digest-cron:0 40 8 * * *}", scheduled.cron(),
                "外置 rag.news.digest-cron，默认 08:40（六段 cron，错峰 08:00 采集与 08:30 探活）");
    }

    @Test
    void flagAnnotationMatchesNewsFamily() {
        var conditional = NewsDailyDigestJob.class.getAnnotation(
                org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        assertEquals("rag.news.enabled", conditional.name()[0]);
        assertEquals("true", conditional.havingValue());
        assertEquals(false, conditional.matchIfMissing());
    }
}
