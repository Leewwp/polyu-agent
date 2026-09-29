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

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.model.LlmAttemptScope;
import com.nageoffer.ai.ragent.infra.model.LlmBudgetExhaustedException;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.news.dao.entity.NewsLlmReceiptDO;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资讯 LLM 预算护栏+付费回执测试（#184 定点修正轮：维护者六点修正+验收反例 B01–B07）
 *
 * <p>账本断言走 {@link FakeNewsLlmReceiptStore}——按<b>字段名</b>断言
 * attempts/retries/costEstimate/statDate/statMonth/status（修正点6：不用整 map
 * containsValue，id 不再可能冒充计数），且 update 真实反映持久值（重读/聚合从
 * 持久态续算）。逐次发出经 LlmAttemptScope 模拟（executor 侧逐次回调+健康豁免
 * 由 infra-ai ModelRoutingExecutorTest 承担，保留不删）。
 *
 * <p>反例映射：B01 月余额单次边界 / B02 跨午夜与跨月入账 / B03 同指纹耗尽零发出
 * / B04 计价闭合拒绝（本文件预算侧）+完整 prompt 准入（NewsEnrichServiceTests）/
 * B05 并发与进程恢复不超额+响应保存后业务失败不重付 / B06 解析无效生命周期
 * （截断不发布见 NewsEnrichServiceTests）/ B07 字段级账本断言+PG 日期映射
 * （NewsLlmReceiptPgDateBindingIt 本地实证）。
 */
class NewsLlmBudgetServiceTests {

    private static final ModelTarget QWEN_FLASH = new ModelTarget("qwen-flash",
            new AIModelProperties.ModelCandidate(), new AIModelProperties.ProviderConfig(), 30_000L);

    private static final ModelTarget QWEN_PLUS = new ModelTarget("qwen-plus",
            new AIModelProperties.ModelCandidate(), new AIModelProperties.ProviderConfig(), 30_000L);

    /**
     * 现行链 [qwen-flash, qwen-plus] 的单次成本上界：
     * (4000+2000)×0.8+1024×2 per 1M = 0.006848（qwen-plus 最贵）
     */
    private static final double UPPER_BOUND_PLUS = 0.006848D;

    /**
     * flash 单候选链上界：(6000×0.5+1024×2)/1e6 = 0.005048
     */
    private static final double UPPER_BOUND_FLASH = 0.005048D;

    private FakeNewsLlmReceiptStore store;
    private LLMService llmService;
    private ModelSelector modelSelector;
    private NewsFetchProperties properties;

    @BeforeEach
    void setUp() {
        store = new FakeNewsLlmReceiptStore();
        llmService = mock(LLMService.class);
        modelSelector = mock(ModelSelector.class);
        properties = new NewsFetchProperties();
        when(modelSelector.selectChatCandidates(anyBoolean(), any()))
                .thenReturn(List.of(QWEN_FLASH, QWEN_PLUS));
    }

    private NewsLlmBudgetService serviceAt(LocalDate hktDate) {
        return service(() -> at(hktDate, 10, 0));
    }

    private NewsLlmBudgetService service(Supplier<Date> clock) {
        return new NewsLlmBudgetService(store.mapper, llmService, modelSelector, properties, clock);
    }

    private static Date at(LocalDate day, int hour, int minute) {
        return Date.from(day.atTime(hour, minute).atZone(NewsLlmBudgetService.HKT_ZONE).toInstant());
    }

    private ChatRequest request(String prompt) {
        return ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.2D)
                .topP(0.3D)
                .thinking(false)
                .maxTokens(1024)
                .build();
    }

    /**
     * 模拟一次逻辑调用内路由 fallback：主选一次+fallback 一次，共两次真实发出
     */
    private void stubChatWithFallbackAttempts() {
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            LlmAttemptScope.notifyBeforeAttempt(QWEN_PLUS);
            return "{\"ok\":true}";
        });
    }

    private void stubChatSingleAttemptSuccess() {
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "{\"ok\":true}";
        });
    }

    /**
     * 预置一笔既有账本行（其它指纹的历史花销，构造日/月基数）
     */
    private NewsLlmReceiptDO seedSpend(String fingerprint, LocalDate day, double costYuan) {
        return store.seed(NewsLlmReceiptDO.builder()
                .requestFingerprint(fingerprint)
                .statDate(day)
                .statMonth(NewsLlmBudgetService.monthKey(Date.from(day.atStartOfDay(NewsLlmBudgetService.HKT_ZONE).toInstant())))
                .attempts(1)
                .retries(0)
                .costEstimate(BigDecimal.valueOf(costYuan))
                .status(NewsLlmBudgetService.STATUS_SUCCESS)
                .build());
    }

    private String fingerprintOf(NewsLlmBudgetService service, ChatRequest request) {
        return service.fingerprintOf(request, Tier.FAST);
    }

    private static NewsLlmReceiptDO rowOf(FakeNewsLlmReceiptStore store, LocalDate day) {
        return store.rows().stream()
                .filter(row -> day.equals(row.getStatDate()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺 " + day + " 的周期行，实际=" + store.rows()));
    }

    private static NewsLlmReceiptDO rowOf(FakeNewsLlmReceiptStore store, String fingerprint) {
        return store.rows().stream()
                .filter(row -> fingerprint.equals(row.getRequestFingerprint()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺指纹 " + fingerprint + " 的周期行，实际=" + store.rows()));
    }

    private static int totalAttempts(FakeNewsLlmReceiptStore store, String... fingerprints) {
        return store.rows().stream()
                .filter(row -> java.util.Arrays.asList(fingerprints).contains(row.getRequestFingerprint()))
                .mapToInt(row -> row.getAttempts() == null ? 0 : row.getAttempts())
                .sum();
    }

    // ==================== 基础硬门+B07 字段级账本断言 ====================

    @Test
    void fallbackAttemptsCountedTowardBudgetAndLedgerFields() {
        stubChatWithFallbackAttempts();

        NewsLlmBudgetService.LlmCall call = serviceAt(LocalDate.of(2026, 9, 29)).call(request("正文A"), Tier.FAST);

        assertEquals("{\"ok\":true}", call.content());
        assertFalse(call.reused());
        verify(llmService, times(1)).chat(any(ChatRequest.class), any(Tier.class));
        // 字段级断言（修正点6）：单周期行 attempts=2（主选+fallback 各计一次）、成本=2×上界
        NewsLlmReceiptDO row = rowOf(store, LocalDate.of(2026, 9, 29));
        assertEquals(2, row.getAttempts(), "fallback 真实发出计入 attempts");
        assertEquals(0, row.getCostEstimate().compareTo(BigDecimal.valueOf(2 * UPPER_BOUND_PLUS)),
                "行成本=attempts×单次上界，实际=" + row.getCostEstimate());
        assertEquals(NewsLlmBudgetService.STATUS_SUCCESS, row.getStatus());
        assertEquals("{\"ok\":true}", row.getResponseText());
        assertEquals("qwen-plus", row.getServedModelId(), "实际服务模型=最后成功的 fallback 候选");
        assertEquals(LocalDate.of(2026, 9, 29), row.getStatDate());
        assertEquals("2026-09", row.getStatMonth());
    }

    @Test
    void flashOnlyChainBoundsRowCostAtFlashPrice() {
        when(modelSelector.selectChatCandidates(anyBoolean(), any())).thenReturn(List.of(QWEN_FLASH));
        stubChatSingleAttemptSuccess();

        serviceAt(LocalDate.of(2026, 9, 29)).call(request("flash 链"), Tier.FAST);

        NewsLlmReceiptDO row = rowOf(store, LocalDate.of(2026, 9, 29));
        assertEquals(0, row.getCostEstimate().compareTo(BigDecimal.valueOf(UPPER_BOUND_FLASH)),
                "flash 单候选链行成本按 flash 上界 0.005048，实际=" + row.getCostEstimate());
    }

    @Test
    void dailyBudgetExhaustedDegradesBeforeAnyAttempt() {
        // 其它指纹当日已花 ¥1.0：下一次发出 1.0+0.006848 > ¥1 → 否决（write-ahead 未发生）
        seedSpend("fp-other-daily", LocalDate.of(2026, 9, 29), 1.0D);
        stubChatSingleAttemptSuccess();
        NewsLlmBudgetService service = serviceAt(LocalDate.of(2026, 9, 29));
        ChatRequest request = request("正文B");

        assertThrows(LlmBudgetExhaustedException.class, () -> service.call(request, Tier.FAST));

        NewsLlmReceiptDO row = rowOf(store, fingerprintOf(service, request));
        // 被否决的发出不记账：本指纹行 attempts 保持 0、成本 0、落 DEGRADED 降级事件
        assertEquals(0, row.getAttempts());
        assertEquals(0, row.getCostEstimate().compareTo(BigDecimal.ZERO));
        assertEquals(NewsLlmBudgetService.STATUS_DEGRADED, row.getStatus());
        assertNull(row.getResponseText(), "被否决请求不得产生成功回执");
    }

    @Test
    void monthlyBudgetBlocksWhenDailyStillFine() {
        // 其它指纹本月（昨日）已花 ¥10，当日基数 0 → 月额度否决、日额度不越界
        seedSpend("fp-other-month", LocalDate.of(2026, 9, 28), 10.0D);
        stubChatSingleAttemptSuccess();
        NewsLlmBudgetService service = serviceAt(LocalDate.of(2026, 9, 29));
        ChatRequest request = request("正文C");

        assertThrows(LlmBudgetExhaustedException.class, () -> service.call(request, Tier.FAST));

        NewsLlmReceiptDO row = rowOf(store, fingerprintOf(service, request));
        assertEquals(NewsLlmBudgetService.STATUS_DEGRADED, row.getStatus());
        assertEquals(0, row.getAttempts(), "月额度否决同样零发出");
    }

    // ==================== B01：月余额只够一次，fallback 第二次零发出 ====================

    @Test
    void b01MonthlyBalanceForOneAttemptVetoesSecondFallbackAttempt() {
        // 月余额=10−1.5×上界：第一次发出 9.996576 ≤10 放行；第二次 10.003424 >10 否决
        seedSpend("fp-b01-seed", LocalDate.of(2026, 9, 28), 10.0D - 1.5D * UPPER_BOUND_PLUS);
        stubChatWithFallbackAttempts();

        LlmBudgetExhaustedException vetoed = assertThrows(LlmBudgetExhaustedException.class,
                () -> serviceAt(LocalDate.of(2026, 9, 29)).call(request("B01 月度边界"), Tier.FAST));

        assertTrue(vetoed.getMessage().contains("月"), "否决诊断含月口径：" + vetoed.getMessage());
        NewsLlmReceiptDO row = rowOf(store, LocalDate.of(2026, 9, 29));
        // 修正点1：同一次逻辑调用内 fallback 的第二次发出看到第一次已记账的累计值——被否决
        assertEquals(1, row.getAttempts(), "第二次发出被月额度否决：attempts 只到 1");
        assertEquals(0, row.getCostEstimate().compareTo(BigDecimal.valueOf(UPPER_BOUND_PLUS)),
                "行成本只含一次发出");
        assertEquals(NewsLlmBudgetService.STATUS_DEGRADED, row.getStatus());
        verify(llmService, times(1)).chat(any(ChatRequest.class), any(Tier.class));
    }

    // ==================== B02：跨午夜/跨月按真实发出时刻入账 ====================

    @Test
    void b02FallbackAcrossMidnightOpensNewDayRowAndKeepsOldRow() {
        AtomicReference<Date> clock = new AtomicReference<>(at(LocalDate.of(2026, 9, 29), 23, 59));
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);   // primary 23:59 发出
            clock.set(at(LocalDate.of(2026, 9, 30), 0, 0));     // primary 耗时跨 HKT 午夜
            LlmAttemptScope.notifyBeforeAttempt(QWEN_PLUS);     // fallback 00:00 发出
            return "{\"ok\":true}";
        });

        NewsLlmBudgetService.LlmCall call = service(clock::get).call(request("跨午夜请求"), Tier.FAST);

        assertEquals("{\"ok\":true}", call.content());
        // 旧行（9-29）：primary 的 write-ahead 原地保留，周期键不搬移
        NewsLlmReceiptDO day1 = rowOf(store, LocalDate.of(2026, 9, 29));
        assertEquals(1, day1.getAttempts());
        assertEquals(0, day1.getCostEstimate().compareTo(BigDecimal.valueOf(UPPER_BOUND_PLUS)));
        assertEquals(LocalDate.of(2026, 9, 29), day1.getStatDate());
        assertEquals("2026-09", day1.getStatMonth());
        // 新行（9-30）：fallback 按 00:00 真实时刻入账，成功回执落新行
        NewsLlmReceiptDO day2 = rowOf(store, LocalDate.of(2026, 9, 30));
        assertEquals(1, day2.getAttempts());
        assertEquals(NewsLlmBudgetService.STATUS_SUCCESS, day2.getStatus());
        assertEquals("qwen-plus", day2.getServedModelId());
        assertEquals(2, store.rows().size(), "跨午夜=两条周期行，各计入各的发出");
    }

    @Test
    void b02MonthEndFallbackOpensNewMonthRow() {
        AtomicReference<Date> clock = new AtomicReference<>(at(LocalDate.of(2026, 9, 30), 23, 59));
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            clock.set(at(LocalDate.of(2026, 10, 1), 0, 0));     // 月末跨月
            LlmAttemptScope.notifyBeforeAttempt(QWEN_PLUS);
            return "{\"ok\":true}";
        });

        service(clock::get).call(request("跨月请求"), Tier.FAST);

        NewsLlmReceiptDO sep = rowOf(store, LocalDate.of(2026, 9, 30));
        assertEquals("2026-09", sep.getStatMonth(), "9 月发出留在 9 月行");
        NewsLlmReceiptDO oct = rowOf(store, LocalDate.of(2026, 10, 1));
        assertEquals("2026-10", oct.getStatMonth(), "10 月发出入 10 月新行");
        assertEquals(NewsLlmBudgetService.STATUS_SUCCESS, oct.getStatus());
        // 10 月账本从零续算：本指纹 10 月行成本=1×上界（不带 9 月历史）
        assertEquals(0, oct.getCostEstimate().compareTo(BigDecimal.valueOf(UPPER_BOUND_PLUS)));
    }

    // ==================== B03：同指纹重试生命周期 ====================

    @Test
    void b03ExhaustedFingerprintIssuesZeroNewRequestsAcrossInstances() {
        NewsLlmBudgetService dayOne = serviceAt(LocalDate.of(2026, 9, 29));
        ChatRequest request = request("B03 耗尽请求");
        String fingerprint = fingerprintOf(dayOne, request);
        store.seed(NewsLlmReceiptDO.builder()
                .requestFingerprint(fingerprint)
                .statDate(LocalDate.of(2026, 9, 28))
                .statMonth("2026-09")
                .attempts(3).retries(2)
                .status(NewsLlmBudgetService.STATUS_FAILED)
                .build());
        stubChatSingleAttemptSuccess();

        // 下一轮调度（同实例）：历史 retries=2/2 已耗尽 → 零发出
        IllegalStateException sameInstance = assertThrows(IllegalStateException.class,
                () -> dayOne.call(request, Tier.FAST));
        assertTrue(sameInstance.getMessage().contains("零新增请求"), sameInstance.getMessage());

        // 新实例（重启）：持久口径续算，不赠送新「首次」
        NewsLlmBudgetService restarted = serviceAt(LocalDate.of(2026, 9, 29));
        assertThrows(IllegalStateException.class, () -> restarted.call(request, Tier.FAST));

        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
        assertEquals(1, store.rows().size(), "零发出=不建新周期行");
    }

    @Test
    void b03UnexhaustedFingerprintContinuesFromPersistedRetries() {
        NewsLlmBudgetService service = serviceAt(LocalDate.of(2026, 9, 29));
        ChatRequest request = request("B03 续算请求");
        String fingerprint = fingerprintOf(service, request);
        // 初次已消耗（attempts=1）且未签发任何重试（retries=0）
        store.seed(NewsLlmReceiptDO.builder()
                .requestFingerprint(fingerprint)
                .statDate(LocalDate.of(2026, 9, 29))
                .statMonth("2026-09")
                .attempts(1).retries(0)
                .status(NewsLlmBudgetService.STATUS_PENDING)
                .build());
        AtomicInteger chatCalls = new AtomicInteger();
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            chatCalls.incrementAndGet();
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            throw new IllegalStateException("still down");
        });

        assertThrows(IllegalStateException.class, () -> service.call(request, Tier.FAST));

        // 生命周期=1 初次+2 重试：初次已耗，本轮只发 2 次重试后耗尽
        assertEquals(2, chatCalls.get(), "剩余重试预算=2-0=2 次逻辑调用");
        NewsLlmReceiptDO row = rowOf(store, LocalDate.of(2026, 9, 29));
        assertEquals(3, row.getAttempts(), "1 历史+2 本轮");
        assertEquals(2, row.getRetries(), "重试签发计数累计到 2");
        assertEquals(NewsLlmBudgetService.STATUS_FAILED, row.getStatus());
    }

    @Test
    void b03StoredResponseStillReusableAfterRetryExhaustion() {
        NewsLlmBudgetService service = serviceAt(LocalDate.of(2026, 9, 29));
        ChatRequest request = request("B03 复用请求");
        store.seed(NewsLlmReceiptDO.builder()
                .requestFingerprint(fingerprintOf(service, request))
                .statDate(LocalDate.of(2026, 9, 28))
                .statMonth("2026-09")
                .attempts(3).retries(2)
                .status(NewsLlmBudgetService.STATUS_SUCCESS)
                .responseText("{\"cached\":true}")
                .build());
        stubChatSingleAttemptSuccess();

        NewsLlmBudgetService.LlmCall call = service.call(request, Tier.FAST);

        assertEquals("{\"cached\":true}", call.content());
        assertTrue(call.reused(), "重试耗尽不阻断已存成功响应的免费复用");
        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
    }

    @Test
    void retriesCountedOnlyWhenActuallyIssued() {
        // 修正点3 细则：失败的重试若从未真实发出（beforeAttempt 未回调），不得算成已重试
        when(llmService.chat(any(ChatRequest.class), any(Tier.class)))
                .thenThrow(new IllegalStateException("no candidates (pre-send)"));

        assertThrows(IllegalStateException.class,
                () -> serviceAt(LocalDate.of(2026, 9, 29)).call(request("未发出失败请求"), Tier.FAST));

        NewsLlmReceiptDO row = rowOf(store, LocalDate.of(2026, 9, 29));
        assertEquals(0, row.getAttempts(), "无真实发出=attempts 保持 0");
        assertEquals(0, row.getRetries(), "未实际执行的重试不计数");
    }

    @Test
    void fallbackRoutesRetryCountedPerLogicalCallNotPerAttempt() {
        // 修正点3「fallback 各自另计 attempt」：走 fallback 的重试每次逻辑调用只计 1 次 retries，
        // 不随 fallback 换候选重复计数
        NewsLlmBudgetService service = serviceAt(LocalDate.of(2026, 9, 29));
        ChatRequest request = request("fallback 重试计数");
        store.seed(NewsLlmReceiptDO.builder()
                .requestFingerprint(fingerprintOf(service, request))
                .statDate(LocalDate.of(2026, 9, 29))
                .statMonth("2026-09")
                .attempts(1).retries(0)
                .status(NewsLlmBudgetService.STATUS_PENDING)
                .build());
        AtomicInteger chatCalls = new AtomicInteger();
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            chatCalls.incrementAndGet();
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            LlmAttemptScope.notifyBeforeAttempt(QWEN_PLUS);
            throw new IllegalStateException("both candidates down");
        });

        assertThrows(IllegalStateException.class, () -> service.call(request, Tier.FAST));

        // 初次已耗 → 本轮 2 次重试逻辑调用（各经 flash+fallback 两次 attempt）后耗尽
        assertEquals(2, chatCalls.get());
        NewsLlmReceiptDO row = rowOf(store, LocalDate.of(2026, 9, 29));
        assertEquals(5, row.getAttempts(), "1 历史+2 逻辑调用×2 attempt（fallback 另计 attempt）");
        assertEquals(2, row.getRetries(), "retries 按逻辑调用计 2 次，不随 fallback attempt 翻倍");
        assertEquals(NewsLlmBudgetService.STATUS_FAILED, row.getStatus());
    }

    // ==================== B04（预算侧）：计价闭合拒绝付费准入 ====================

    @Test
    void b04UnpricedCandidateRejectsPaidAdmissionWithZeroSends() {
        when(modelSelector.selectChatCandidates(anyBoolean(), any()))
                .thenReturn(List.of(QWEN_FLASH, new ModelTarget("mystery-model",
                        new AIModelProperties.ModelCandidate(), new AIModelProperties.ProviderConfig(), 30_000L)));
        stubChatSingleAttemptSuccess();
        NewsLlmBudgetService service = serviceAt(LocalDate.of(2026, 9, 29));
        ChatRequest request = request("未知价模型");

        LlmBudgetExhaustedException refused = assertThrows(LlmBudgetExhaustedException.class,
                () -> service.call(request, Tier.FAST));

        assertTrue(refused.getMessage().contains("计价未闭合"), refused.getMessage());
        assertTrue(refused.getMessage().contains("mystery-model"), "诊断指名未配价候选");
        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
        NewsLlmReceiptDO eventRow = rowOf(store, fingerprintOf(service, request));
        assertEquals(NewsLlmBudgetService.STATUS_DEGRADED, eventRow.getStatus(), "拒绝事件行 admin 可查");
        assertEquals(0, eventRow.getAttempts(), "拒绝付费准入=零记账");
    }

    @Test
    void b04InvalidPriceConfigRejectsPaidAdmission() {
        properties.getBudgetModelPrices().put("qwen-plus", new NewsFetchProperties.ModelPrice(-0.1D, 2.0D));
        stubChatSingleAttemptSuccess();

        LlmBudgetExhaustedException refused = assertThrows(LlmBudgetExhaustedException.class,
                () -> serviceAt(LocalDate.of(2026, 9, 29)).call(request("非法单价"), Tier.FAST));

        assertTrue(refused.getMessage().contains("单价非法"), refused.getMessage());
        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
    }

    @Test
    void b04ThinkingModeMismatchRejectsPaidAdmission() {
        ChatRequest thinkingRequest = ChatRequest.builder()
                .messages(List.of(ChatMessage.user("思考模式请求")))
                .temperature(0.2D).topP(0.3D).thinking(true).maxTokens(1024)
                .build();
        stubChatSingleAttemptSuccess();

        LlmBudgetExhaustedException refused = assertThrows(LlmBudgetExhaustedException.class,
                () -> serviceAt(LocalDate.of(2026, 9, 29)).call(thinkingRequest, Tier.FAST));

        assertTrue(refused.getMessage().contains("思考模式"),
                "单价表仅覆盖非思考档，模式不匹配拒绝：" + refused.getMessage());
        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
    }

    @Test
    void b04EmptyCandidateChainRejectsPaidAdmission() {
        when(modelSelector.selectChatCandidates(anyBoolean(), any())).thenReturn(List.of());

        LlmBudgetExhaustedException refused = assertThrows(LlmBudgetExhaustedException.class,
                () -> serviceAt(LocalDate.of(2026, 9, 29)).call(request("空链请求"), Tier.FAST));

        assertTrue(refused.getMessage().contains("候选链为空"), refused.getMessage());
        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
    }

    // ==================== B05：并发/进程恢复不超额+业务失败不重付 ====================

    @Test
    void b05ConcurrentAdmissionDoesNotOverspendMonthlyBudget() throws InterruptedException {
        // 月余额只够一次发出：两并发（不同指纹）恰好一胜一否决（串行准入+重读持久值）
        seedSpend("fp-b05-seed", LocalDate.of(2026, 9, 28), 10.0D - 1.5D * UPPER_BOUND_PLUS);
        NewsLlmBudgetService service = serviceAt(LocalDate.of(2026, 9, 29));
        stubChatSingleAttemptSuccess();

        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger vetoes = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        ChatRequest requestA = request("并发请求甲");
        ChatRequest requestB = request("并发请求乙");
        String fingerprintA = fingerprintOf(service, requestA);
        String fingerprintB = fingerprintOf(service, requestB);
        Runnable callerA = admitOnce(service, requestA, start, successes, vetoes);
        Runnable callerB = admitOnce(service, requestB, start, successes, vetoes);
        Thread threadA = new Thread(callerA, "news-budget-admission-a");
        Thread threadB = new Thread(callerB, "news-budget-admission-b");
        threadA.start();
        threadB.start();
        start.countDown();
        threadA.join();
        threadB.join();

        assertEquals(1, successes.get(), "恰好一个请求获得付费准入");
        assertEquals(1, vetoes.get(), "另一个被月额度否决");
        assertEquals(1, totalAttempts(store, fingerprintA, fingerprintB), "合计只发出一次（并发不超额）");
        assertTrue(store.rows().stream().anyMatch(row -> NewsLlmBudgetService.STATUS_DEGRADED.equals(row.getStatus())
                        && row.getAttempts() == 0),
                "被否决侧落 DEGRADED 且零记账");
    }

    private static Runnable admitOnce(NewsLlmBudgetService service, ChatRequest request,
                                      CountDownLatch start, AtomicInteger successes, AtomicInteger vetoes) {
        return () -> {
            try {
                start.await();
                service.call(request, Tier.FAST);
                successes.incrementAndGet();
            } catch (LlmBudgetExhaustedException e) {
                vetoes.incrementAndGet();
            } catch (Exception e) {
                throw new AssertionError("非预期异常：" + e.getMessage());
            }
        };
    }

    @Test
    void b05RestartKeepsBudgetEnforcementFromPersistedTotals() {
        // 进程 A：日余额=上界×1（其它指纹今日已花 1−上界）→ 恰好放行一次
        seedSpend("fp-b05-restart", LocalDate.of(2026, 9, 29), 1.0D - UPPER_BOUND_PLUS);
        stubChatSingleAttemptSuccess();
        NewsLlmBudgetService processA = serviceAt(LocalDate.of(2026, 9, 29));
        assertEquals("{\"ok\":true}", processA.call(request("进程A请求"), Tier.FAST).content());

        // 进程 B（新实例=重启，无内存态）：日聚合从持久行续算已满 → 否决
        NewsLlmBudgetService processB = serviceAt(LocalDate.of(2026, 9, 29));
        assertThrows(LlmBudgetExhaustedException.class,
                () -> processB.call(request("进程B请求"), Tier.FAST));
    }

    @Test
    void b05ResponsePersistedBeforeBusinessFailureIsNotPaidTwice() {
        NewsLlmBudgetService service = serviceAt(LocalDate.of(2026, 9, 29));
        ChatRequest request = request("B05 业务失败不重付");
        stubChatSingleAttemptSuccess();

        NewsLlmBudgetService.LlmCall first = service.call(request, Tier.FAST);
        assertEquals("{\"ok\":true}", first.content());
        // 调用方业务侧失败（解析/落库）后同指纹重跑
        NewsLlmBudgetService.LlmCall second = service.call(request, Tier.FAST);

        assertTrue(second.reused(), "已存成功响应直接复用，不重复付费");
        verify(llmService, times(1)).chat(any(ChatRequest.class), any(Tier.class));
        NewsLlmReceiptDO row = rowOf(store, LocalDate.of(2026, 9, 29));
        assertEquals(1, row.getAttempts(), "复用不新增发出");
    }

    // ==================== B06（预算侧）：解析无效生命周期不无限重试 ====================

    @Test
    void b06InvalidResponseLifecycleIsBoundedAndNeverRepublishes() {
        NewsLlmBudgetService service = serviceAt(LocalDate.of(2026, 9, 29));
        ChatRequest request = request("B06 无效响应生命周期");
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "{\"title_zh\":\"标题\",\"summary_zh\":\"摘要中被 max_tokens 截断";
        });

        // 第 1 轮：新鲜响应（截断 JSON）→ 解析失败回报 → FAILED 保留一次复用
        NewsLlmBudgetService.LlmCall first = service.call(request, Tier.FAST);
        assertFalse(first.reused());
        first.reportInvalid();
        NewsLlmReceiptDO row = rowOf(store, LocalDate.of(2026, 9, 29));
        assertEquals(NewsLlmBudgetService.STATUS_FAILED, row.getStatus());
        assertNotNull(row.getResponseText(), "新鲜响应保留一次复用（已付费不浪费）");

        // 第 2 轮：免费复用仍无效 → 清除并隔离（POISONED）
        NewsLlmBudgetService.LlmCall second = service.call(request, Tier.FAST);
        assertTrue(second.reused());
        second.reportInvalid();
        assertEquals(NewsLlmBudgetService.STATUS_POISONED, row.getStatus());
        assertNull(row.getResponseText());

        // 第 3 轮：隔离后拒绝复读——不无限重试、不重复付费
        assertThrows(IllegalStateException.class, () -> service.call(request, Tier.FAST));
        verify(llmService, times(1)).chat(any(ChatRequest.class), any(Tier.class));
        assertEquals(1, rowOf(store, LocalDate.of(2026, 9, 29)).getAttempts(), "整个生命周期只真实发出一次");
    }

    // ==================== 指纹与诊断（保留硬门） ====================

    @Test
    void fingerprintCoversPromptModelAndEffectiveParams() {
        NewsLlmBudgetService service = serviceAt(LocalDate.of(2026, 9, 29));
        ChatRequest base = request("提示词X");
        String baseline = fingerprintOf(service, base);
        assertEquals(baseline, fingerprintOf(service, request("提示词X")), "同请求同指纹");
        assertNotEquals(baseline, fingerprintOf(service, request("提示词Y")),
                "提示词变化换指纹（动态主题词表在提示词内）");
        assertNotEquals(baseline,
                service.fingerprintOf(ChatRequest.builder()
                        .messages(base.getMessages()).temperature(0.9D).topP(0.3D).maxTokens(1024).build(), Tier.FAST),
                "temperature 变化换指纹");
        assertNotEquals(baseline,
                service.fingerprintOf(ChatRequest.builder()
                        .messages(base.getMessages()).temperature(0.2D).topP(0.3D).maxTokens(512).build(), Tier.FAST),
                "maxTokens 变化换指纹");
        when(modelSelector.selectChatCandidates(anyBoolean(), any())).thenReturn(List.of(QWEN_PLUS));
        assertNotEquals(baseline, fingerprintOf(service, request("提示词X")), "主选模型变化换指纹");
    }

    @Test
    void budgetExhaustedExceptionCarriesDualCaliberDiagnostics() {
        seedSpend("fp-diag", LocalDate.of(2026, 9, 29), 1.0D);
        stubChatSingleAttemptSuccess();

        LlmBudgetExhaustedException caught = assertThrows(LlmBudgetExhaustedException.class,
                () -> serviceAt(LocalDate.of(2026, 9, 29)).call(request("正文J"), Tier.FAST));
        assertTrue(caught.getMessage().contains("日") && caught.getMessage().contains("月"),
                "异常携带双口径诊断信息：" + caught.getMessage());
    }
}
