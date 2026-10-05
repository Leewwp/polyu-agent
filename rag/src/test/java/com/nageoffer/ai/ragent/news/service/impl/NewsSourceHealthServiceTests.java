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

package com.nageoffer.ai.ragent.news.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceHealthEventDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceHealthEventMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchOutcome;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsSourceFetcher;
import com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer;
import com.nageoffer.ai.ragent.news.fetch.RawNewsItem;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.nageoffer.ai.ragent.news.fetch.PublishTimePrecision;

/**
 * 源健康服务测试（#186，父票 #181 §2）：defer 零计数豁免、停用原因三分
 * （manual/auto/policy 互不误复活）、失败滞回自动隔离、日级探活复归
 * （两次有效完整成功+≤48h 窗口+结构失配不可复归+defer 不计尝试+日级节拍）、
 * 复归入统一门（探活候选即弃不入库）。Mapper 全 mock（字段级断言：set 列名
 * 与参数值精确核对，禁整 map containsValue 计数）；真库 SQL 口径归
 * NewsSourceGovernancePgIt。
 */
class NewsSourceHealthServiceTests {

    private static final long HOUR = 3600L * 1000;
    private static final long DAY = 24L * HOUR;

    private NewsSourceMapper sourceMapper;
    private NewsSourceHealthEventMapper eventMapper;
    private NewsItemMapper itemMapper;
    private NewsFetchProperties properties;
    private StubFetcher fetcher;
    private NewsFetchService fetchService;
    private NewsSourceHealthService service;
    private final AtomicReference<Date> nowRef = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        // 纯 Mockito 环境无 MyBatis-Plus 运行时，lambda wrapper 需手工初始化表元数据
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsItemDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsSourceDO.class);
        TableInfoHelper.initTableInfo(assistant, NewsSourceHealthEventDO.class);
        sourceMapper = mock(NewsSourceMapper.class);
        eventMapper = mock(NewsSourceHealthEventMapper.class);
        itemMapper = mock(NewsItemMapper.class);
        properties = new NewsFetchProperties();
        fetcher = new StubFetcher();
        nowRef.set(new Date(1_800_000_000_000L)); // 固定基准时刻（2026-09 后）
        fetchService = new NewsFetchService(List.of(fetcher), sourceMapper, itemMapper,
                properties, nowRef::get);
        service = new NewsSourceHealthService(sourceMapper, eventMapper, fetchService,
                properties, nowRef::get);
    }

    private NewsSourceDO source(long id, String key) {
        return NewsSourceDO.builder()
                .id(id).sourceKey(key).platform("official")
                .fetchEndpoint("https://example.com/list-" + key)
                .fetchStrategy("HTML_LIST").enabled(true).consecutiveFailures(0)
                .build();
    }

    private NewsSourceDO isolatedSource(long id, String key, String reason) {
        NewsSourceDO source = source(id, key);
        source.setEnabled(false);
        source.setDisabledReason(reason);
        return source;
    }

    private NewsFetchService.SourceFetchResult result(NewsSourceDO source, NewsFetchOutcome outcome,
                                                       String detail) {
        return new NewsFetchService.SourceFetchResult(source, List.of(), outcome, detail);
    }

    private List<LambdaUpdateWrapper<NewsSourceDO>> capturedSourceUpdates() {
        ArgumentCaptor<LambdaUpdateWrapper<NewsSourceDO>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(sourceMapper, org.mockito.Mockito.atLeast(0)).update(any(), captor.capture());
        return captor.getAllValues();
    }

    private List<NewsSourceHealthEventDO> capturedEvents() {
        ArgumentCaptor<NewsSourceHealthEventDO> captor = ArgumentCaptor.forClass(NewsSourceHealthEventDO.class);
        verify(eventMapper, org.mockito.Mockito.atLeast(0)).insert(captor.capture());
        return captor.getAllValues();
    }

    private static String sqlSetOf(LambdaUpdateWrapper<NewsSourceDO> wrapper) {
        return String.valueOf(wrapper.getSqlSet());
    }

    // ================== #186 范围 1：defer 零计数豁免 ==================

    @Test
    void deferOutcomeTouchesNeitherHysteresisNorEnabledSwitch() {
        NewsSourceDO source = source(1, "stub-source");
        source.setConsecutiveFailures(2); // 已两连败，defer 不得误伤也不得清零

        service.recordFetchOutcome(source, result(source, NewsFetchOutcome.DEFER, "Crawl-delay 超单次等待上限"));

        assertEquals(2, source.getConsecutiveFailures(), "defer 不清零滞回（零计数=不增不清零）");
        assertTrue(source.getEnabled(), "defer 不禁源");
        assertEquals(NewsFetchOutcome.DEFER.code(), source.getLastOutcome(), "仅落 last_outcome 留痕");
        assertEquals(1, capturedSourceUpdates().size(), "只发一次最小更新");
        String sqlSet = sqlSetOf(capturedSourceUpdates().get(0));
        assertTrue(sqlSet.contains("last_outcome"), "defer 更新含 last_outcome，实际=" + sqlSet);
        assertFalse(sqlSet.contains("consecutive_failures"), "defer 不得触碰滞回计数，实际=" + sqlSet);
        assertFalse(sqlSet.contains("enabled"), "defer 不得触碰源开关，实际=" + sqlSet);
        assertTrue(capturedEvents().isEmpty(), "defer 零计数：不落任何健康事件");
    }

    // ================== #186 范围 2：失败滞回自动隔离（reason=auto） ==================

    @Test
    void threeConsecutiveFailuresIsolateSourceWithAutoReasonAndEvent() {
        NewsSourceDO source = source(1, "stub-source");

        for (int round = 1; round <= 2; round++) {
            service.recordFetchOutcome(source, result(source, NewsFetchOutcome.NETWORK_FAILURE, "HTTP 500"));
            assertEquals(round, source.getConsecutiveFailures(), "第 " + round + " 败计数");
            assertTrue(source.getEnabled(), "未达阈值不禁");
        }

        service.recordFetchOutcome(source, result(source, NewsFetchOutcome.STRUCTURE_MISMATCH, "RSS 解析零条目"));

        assertEquals(3, source.getConsecutiveFailures());
        assertFalse(source.getEnabled(), "连续 3 败自动隔离");
        assertEquals(NewsSourceDO.DISABLED_REASON_AUTO, source.getDisabledReason(), "隔离原因=auto（探活对象）");
        assertNotNull(source.getIsolatedTime(), "隔离时刻可查");
        String isolateSqlSet = sqlSetOf(capturedSourceUpdates().get(2));
        assertTrue(isolateSqlSet.contains("isolated_time") && isolateSqlSet.contains("disabled_reason"),
                "第三败写隔离列，实际=" + isolateSqlSet);
        assertEquals(1, capturedEvents().size(), "隔离事件落流水");
        NewsSourceHealthEventDO event = capturedEvents().get(0);
        assertEquals(NewsSourceHealthEventDO.TYPE_ISOLATED, event.getEventType());
        assertEquals(NewsFetchOutcome.STRUCTURE_MISMATCH.code(), event.getOutcome(), "事件携带触发轮结果（判定依据）");
        assertTrue(event.getDetail().contains("3"), "判定依据含失败计数：" + event.getDetail());
    }

    @Test
    void validSuccessResetsFailuresAndValidEmptyCountsAsHealthy() {
        NewsSourceDO source = source(1, "empty-ok");
        source.setConsecutiveFailures(2);
        properties.getAllowEmptySources().add("empty-ok");

        service.recordFetchOutcome(source, result(source, NewsFetchOutcome.VALID_EMPTY, "解析有效但零条目"));

        assertEquals(0, source.getConsecutiveFailures(), "有效空同为有效成功：清零滞回（允许空源健康）");
        assertEquals(NewsFetchOutcome.VALID_EMPTY.code(), source.getLastOutcome());
        assertTrue(sqlSetOf(capturedSourceUpdates().get(0)).contains("consecutive_failures"));

        source.setConsecutiveFailures(2);
        service.recordFetchOutcome(source, result(source, NewsFetchOutcome.VALID_WITH_CONTENT, "解析 3 条"));
        assertEquals(0, source.getConsecutiveFailures(), "有效有内容清零滞回");
    }

    // ================== #186 范围 2：策略禁止立即转停（reason=policy，无三败滞回） ==================

    @Test
    void policyForbiddenDisablesImmediatelyWithPolicyReasonAndNoHysteresis() {
        NewsSourceDO source = source(1, "stub-source");

        service.recordFetchOutcome(source, result(source, NewsFetchOutcome.POLICY_FORBIDDEN,
                "robots.txt Disallow: /media/"));

        assertFalse(source.getEnabled(), "策略禁止立即转停（robots 拒绝不是源故障，无三败滞回）");
        assertEquals(NewsSourceDO.DISABLED_REASON_POLICY, source.getDisabledReason(), "停用原因=policy");
        assertEquals(0, source.getConsecutiveFailures(), "不经滞回计数");
        assertEquals(NewsFetchOutcome.POLICY_FORBIDDEN.code(), source.getLastOutcome());
        String sqlSet = sqlSetOf(capturedSourceUpdates().get(0));
        assertTrue(sqlSet.contains("disabled_reason") && sqlSet.contains("enabled"), "转停写开关+原因，实际=" + sqlSet);
        assertEquals(1, capturedEvents().size());
        assertEquals(NewsSourceHealthEventDO.TYPE_POLICY_DISABLED, capturedEvents().get(0).getEventType());
        assertEquals(NewsFetchOutcome.POLICY_FORBIDDEN.code(), capturedEvents().get(0).getOutcome());
    }

    // ================== #186 范围 4：日级探活复归（两次有效完整成功） ==================

    @Test
    void probeSweepRecoversAutoIsolatedSourceAfterTwoValidSuccessesAcrossDays() {
        NewsSourceDO source = isolatedSource(1, "stub-source", NewsSourceDO.DISABLED_REASON_AUTO);
        when(sourceMapper.selectList(any())).thenReturn(List.of(source));
        fetcher.items = List.of(rawItem("stub-source", "probe-1"), rawItem("stub-source", "probe-2"));

        // 第 1 日探活：一次有效完整成功——streak=1，未复归
        NewsSourceHealthService.ProbeSweepResult day1 = service.probeSweep();

        assertEquals(1, day1.probed());
        assertEquals(0, day1.recovered(), "一次成功未达阈值（两次有效完整成功）");
        assertFalse(source.getEnabled(), "未复归：仍禁用");
        assertEquals(1, source.getProbeSuccesses(), "连续成功记 1");
        assertNotNull(source.getProbeTime());
        assertEquals(1, capturedEvents().stream()
                .filter(e -> NewsSourceHealthEventDO.TYPE_PROBE_PASS.equals(e.getEventType())).count(),
                "probe_pass 事件留痕");

        // 第 2 日（+25h，窗口内）探活：第二次有效完整成功 → 复归
        nowRef.set(new Date(nowRef.get().getTime() + 25 * HOUR));
        NewsSourceHealthService.ProbeSweepResult day2 = service.probeSweep();

        assertEquals(1, day2.probed());
        assertEquals(1, day2.recovered(), "两次有效完整成功（间隔 ≤48h）自动复归");
        assertTrue(source.getEnabled(), "复归置 enabled=true");
        assertNull(source.getDisabledReason(), "复归清停用原因");
        assertEquals(0, source.getConsecutiveFailures());
        assertEquals(2, source.getProbeSuccesses());
        assertNotNull(source.getRecoveredTime(), "复归时刻可查");
        NewsSourceHealthEventDO recoveredEvent = capturedEvents().stream()
                .filter(e -> NewsSourceHealthEventDO.TYPE_RECOVERED.equals(e.getEventType()))
                .findFirst().orElseThrow();
        assertEquals(NewsFetchOutcome.VALID_WITH_CONTENT.code(), recoveredEvent.getOutcome());
        assertTrue(recoveredEvent.getDetail().contains("2"), "复归依据含连续成功数：" + recoveredEvent.getDetail());
        // 复归入统一门（#186 范围 5）：探活候选即弃——两次探活共 4 条候选，零入库
        verify(itemMapper, never()).insert(any(NewsItemDO.class));
    }

    @Test
    void probeSweepOnlyQueriesAutoIsolatedSourcesAndSkipsAlreadyProbedToday() {
        // 三类停用互不误复活：探活查询限定 enabled=false AND disabled_reason='auto'——
        // 人工停用/策略禁止行由查询形状天然排除，不进探活
        NewsSourceDO autoSource = isolatedSource(1, "auto-source", NewsSourceDO.DISABLED_REASON_AUTO);
        when(sourceMapper.selectList(any())).thenReturn(List.of(autoSource));
        fetcher.items = List.of(rawItem("auto-source", "probe"));

        service.probeSweep();

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<NewsSourceDO>> query =
                org.mockito.ArgumentCaptor.forClass(
                        (Class) com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(sourceMapper).selectList(query.capture());
        String sqlSegment = String.valueOf(query.getValue().getSqlSegment());
        assertTrue(sqlSegment.contains("enabled") && sqlSegment.contains("disabled_reason"),
                "探活对象=enabled=false AND disabled_reason=auto，实际=" + sqlSegment);
        assertTrue(query.getValue().getParamNameValuePairs().containsValue(false)
                        && query.getValue().getParamNameValuePairs().containsValue(NewsSourceDO.DISABLED_REASON_AUTO),
                "查询参数含开关 false 与原因 auto");

        // 日级节拍：同日第二轮探活（probe_time 已是今天）跳过——fetch 不再发生
        org.mockito.Mockito.clearInvocations(sourceMapper);
        when(sourceMapper.selectList(any())).thenReturn(List.of(autoSource));
        NewsSourceHealthService.ProbeSweepResult secondSweep = service.probeSweep();

        assertEquals(0, secondSweep.probed(), "同日已探过不再探（每源每 HKT 日至多一次）");
        assertEquals(0, secondSweep.recovered());
        verify(sourceMapper, org.mockito.Mockito.never()).update(any(), any());
    }

    @Test
    void probeSuccessBeyond48hWindowRestartsStreakInsteadOfRecovering() {
        NewsSourceDO source = isolatedSource(1, "stub-source", NewsSourceDO.DISABLED_REASON_AUTO);
        when(sourceMapper.selectList(any())).thenReturn(List.of(source));
        fetcher.items = List.of(rawItem("stub-source", "probe"));

        service.probeSweep(); // 第 1 日成功：streak=1

        // +49h（超 48h 窗口）：视为不连续，streak 重起——两次成功不满足「持续成功」
        nowRef.set(new Date(nowRef.get().getTime() + 49 * HOUR));
        NewsSourceHealthService.ProbeSweepResult stale = service.probeSweep();

        assertEquals(0, stale.recovered(), "超窗成功不算连续——不凭相隔 49h 的两次成功复归");
        assertEquals(1, source.getProbeSuccesses(), "streak 重起为 1");
        assertFalse(source.getEnabled());

        // 窗口内第三次（+25h）成功：与第二次连续 → 复归（可达且持续成功 ≤48h）
        nowRef.set(new Date(nowRef.get().getTime() + 25 * HOUR));
        NewsSourceHealthService.ProbeSweepResult recovered = service.probeSweep();
        assertEquals(1, recovered.recovered(), "窗口内连续两次达成后复归");
        assertTrue(source.getEnabled());
    }

    @Test
    void probeStructureMismatchResetsStreakAndSingleSuccessCannotRecover() {
        NewsSourceDO source = isolatedSource(1, "stub-source", NewsSourceDO.DISABLED_REASON_AUTO);
        when(sourceMapper.selectList(any())).thenReturn(List.of(source));
        fetcher.items = List.of(rawItem("stub-source", "probe"));

        service.probeSweep(); // 第 1 日有效成功：streak=1
        assertEquals(1, source.getProbeSuccesses());

        // 第 2 日结构失配：HTTP 通但解析失配——不是有效成功（HTTP 200 不是恢复充分条件），
        // 且清零 streak
        nowRef.set(new Date(nowRef.get().getTime() + 25 * HOUR));
        fetcher.failure = new com.nageoffer.ai.ragent.news.fetch.NewsFetchStructureException(
                "RSS 解析零条目（结构变更嫌疑，fail-closed）");
        NewsSourceHealthService.ProbeSweepResult mismatch = service.probeSweep();

        assertEquals(0, mismatch.recovered(), "解析失配不可凭一条成功复归（也不是有效成功）");
        assertEquals(0, source.getProbeSuccesses(), "失配清零连续成功");
        assertFalse(source.getEnabled());
        assertEquals(1, capturedEvents().stream()
                .filter(e -> NewsSourceHealthEventDO.TYPE_PROBE_FAIL.equals(e.getEventType())).count(),
                "probe_fail 事件留痕");

        // 第 3 日再次成功：streak 从 0 重起到 1——单独一条成功不构成复归
        nowRef.set(new Date(nowRef.get().getTime() + 25 * HOUR));
        fetcher.failure = null;
        NewsSourceHealthService.ProbeSweepResult single = service.probeSweep();

        assertEquals(0, single.recovered(), "失配后的单条成功仍未达两次阈值");
        assertEquals(1, source.getProbeSuccesses());
        assertFalse(source.getEnabled());
    }

    @Test
    void probeDeferConsumesNoAttemptAndKeepsSourceProbeEligible() {
        NewsSourceDO source = isolatedSource(1, "stub-source", NewsSourceDO.DISABLED_REASON_AUTO);
        when(sourceMapper.selectList(any())).thenReturn(List.of(source));
        fetcher.failure = new com.nageoffer.ai.ragent.news.fetch.NewsFetchDeferredException(
                "Crawl-delay 超单次等待上限");

        NewsSourceHealthService.ProbeSweepResult deferred = service.probeSweep();

        assertEquals(1, deferred.probed(), "defer 也算一次探活动作（本轮探过）");
        assertEquals(0, deferred.recovered());
        assertEquals(NewsFetchOutcome.DEFER.code(), source.getLastOutcome(), "defer 结果留痕");
        assertNull(source.getProbeTime(), "defer 不计尝试：probe_time 不推进（streak 不变、失败不清零）");
        assertEquals(NewsSourceDO.DISABLED_REASON_AUTO, source.getDisabledReason(), "保持探活资格");
        assertTrue(capturedEvents().isEmpty(), "defer 不落事件（既非失败也非成功）");

        // 同日下一轮（probe_time 未推进=今天没探成）：可立即补探——不因 defer 锁死当日
        fetcher.failure = null;
        fetcher.items = List.of(rawItem("stub-source", "probe"));
        NewsSourceHealthService.ProbeSweepResult retry = service.probeSweep();
        assertEquals(1, retry.probed(), "defer 后同日可补探（未消耗日级名额）");
        assertEquals(1, source.getProbeSuccesses(), "补探成功计入 streak");
    }

    @Test
    void probePolicyForbiddenConvertsReasonToPolicyAndExitsProbePool() {
        NewsSourceDO source = isolatedSource(1, "stub-source", NewsSourceDO.DISABLED_REASON_AUTO);
        when(sourceMapper.selectList(any())).thenReturn(List.of(source));
        fetcher.failure = new com.nageoffer.ai.ragent.news.fetch.NewsFetchPolicyException(
                "robots.txt Disallow: /media/");

        NewsSourceHealthService.ProbeSweepResult sweep = service.probeSweep();

        assertEquals(0, sweep.recovered());
        assertEquals(NewsSourceDO.DISABLED_REASON_POLICY, source.getDisabledReason(),
                "探活中 robots 已 Disallow：转 policy 停用（不因可达自动解禁，复归=人工）");
        assertFalse(source.getEnabled());
        assertEquals(1, capturedEvents().stream()
                .filter(e -> NewsSourceHealthEventDO.TYPE_POLICY_DISABLED.equals(e.getEventType())).count());
    }

    @Test
    void probeNetworkFailureCountsAsProbeFailWithoutRecovery() {
        NewsSourceDO source = isolatedSource(1, "stub-source", NewsSourceDO.DISABLED_REASON_AUTO);
        when(sourceMapper.selectList(any())).thenReturn(List.of(source));
        fetcher.items = List.of(rawItem("stub-source", "probe"));

        service.probeSweep(); // streak=1
        nowRef.set(new Date(nowRef.get().getTime() + 25 * HOUR));
        fetcher.failure = new com.nageoffer.ai.ragent.news.fetch.NewsFetchException("HTTP 503", true);

        NewsSourceHealthService.ProbeSweepResult sweep = service.probeSweep();

        assertEquals(0, sweep.recovered(), "网络失败不是有效成功：不满足两次有效完整成功");
        assertEquals(0, source.getProbeSuccesses(), "探活失败清零 streak");
        assertEquals(NewsSourceDO.DISABLED_REASON_AUTO, source.getDisabledReason(), "保持 auto 探活资格（明日再探）");
    }

    private static RawNewsItem rawItem(String key, String slug) {
        String url = "https://example.com/news/" + slug;
        return new RawNewsItem(url, NewsUrlNormalizer.urlHash(url),
                "title-" + slug, null, "en", new Date(), null, key, PublishTimePrecision.DATETIME);
    }

    /**
     * 可编程假抓取器（探活走 NewsFetchService 同一条纪律路径，绕开 HTTP 层）
     */
    private static final class StubFetcher implements NewsSourceFetcher {

        private List<RawNewsItem> items = List.of();
        private RuntimeException failure;

        @Override
        public String supportedStrategy() {
            return "HTML_LIST";
        }

        @Override
        public List<RawNewsItem> fetch(NewsSourceDO source) {
            if (failure != null) {
                throw failure;
            }
            return items;
        }
    }
}
