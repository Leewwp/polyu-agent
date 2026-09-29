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
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import com.nageoffer.ai.ragent.news.dao.mapper.NewsLlmReceiptMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资讯 LLM 预算护栏+付费回执测试（#184 硬门+维护者六点修正）
 *
 * <p>覆盖：attempts 含 fallback 计数、日/月额度否决、超额降级落 DEGRADED、
 * 请求指纹复用不重复付费、重试同指纹累计（跨调度耗尽不再重试）、重启后预算按
 * 持久化合计续算、跨日/跨月重试不改写历史账目归属（周期行账本）、成本上界按
 * 候选链最贵候选×完整限额推导、POISONED 不无限复读。Mapper 全 mock
 * （NewsEnrichServiceTests 先例），逐次发出经 LlmAttemptScope 模拟
 * （executor 侧逐次回调保证由 infra-ai ModelRoutingExecutorTest 承担）。
 */
class NewsLlmBudgetServiceTests {

    private static final ModelTarget QWEN_FLASH = new ModelTarget("qwen-flash",
            new AIModelProperties.ModelCandidate(), new AIModelProperties.ProviderConfig(), 30_000L);

    private static final ModelTarget QWEN_PLUS = new ModelTarget("qwen-plus",
            new AIModelProperties.ModelCandidate(), new AIModelProperties.ProviderConfig(), 30_000L);

    /**
     * 现行链 [qwen-flash, qwen-plus] 的单次成本上界：
     * (4000+2000)×0.8+1024×2 per 1M = 0.006848（qwen-plus 最贵，维护者修正点2 推导）
     */
    private static final double UPPER_BOUND_PLUS = 0.006848D;

    private NewsLlmReceiptMapper receiptMapper;
    private LLMService llmService;
    private ModelSelector modelSelector;
    private NewsFetchProperties properties;
    private AtomicInteger idSeq;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsLlmReceiptDO.class);
        receiptMapper = mock(NewsLlmReceiptMapper.class);
        llmService = mock(LLMService.class);
        modelSelector = mock(ModelSelector.class);
        properties = new NewsFetchProperties();
        idSeq = new AtomicInteger();
        when(modelSelector.selectChatCandidates(anyBoolean(), any()))
                .thenReturn(List.of(QWEN_FLASH, QWEN_PLUS));
        // 默认：无历史行、当日行不存在、三项聚合（日基数/月基数/本月自有）全 0
        when(receiptMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(receiptMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(receiptMapper.selectObjs(any())).thenReturn(List.of(BigDecimal.ZERO));
        when(receiptMapper.insert(any(NewsLlmReceiptDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, NewsLlmReceiptDO.class).setId((long) idSeq.incrementAndGet());
            return 1;
        });
    }

    private NewsLlmBudgetService service(LocalDate hktDate) {
        return new NewsLlmBudgetService(receiptMapper, llmService, modelSelector, properties,
                () -> Date.from(hktDate.atTime(10, 0).atZone(NewsLlmBudgetService.HKT_ZONE).toInstant()));
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
     * 模拟一次逻辑调用内路由 fallback：executor 在每个候选真实发出前回调观察者——
     * 主选一次（失败）+fallback 一次（成功），共两次真实发出
     */
    private void stubChatWithFallbackAttempts() {
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            LlmAttemptScope.notifyBeforeAttempt(QWEN_PLUS);
            return "{\"ok\":true}";
        });
    }

    private List<Map<String, Object>> capturedUpdatePairs() {
        ArgumentCaptor<Wrapper<NewsLlmReceiptDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(receiptMapper, atLeastOnce()).update(org.mockito.ArgumentMatchers.eq((NewsLlmReceiptDO) null), captor.capture());
        return captor.getAllValues().stream()
                .map(wrapper -> {
                    LambdaUpdateWrapper<NewsLlmReceiptDO> update = (LambdaUpdateWrapper<NewsLlmReceiptDO>) wrapper;
                    // 触发 WHERE 段构建，物化 eq(id) 条件参数（set 参数为即时写入）
                    update.getSqlSegment();
                    return update.getParamNameValuePairs();
                })
                .toList();
    }

    /**
     * BigDecimal 等值断言（equals 对 scale 敏感，用 compareTo）
     */
    private static boolean hasCost(Map<String, Object> pairs, double expected) {
        return pairs.values().stream()
                .filter(value -> value instanceof BigDecimal)
                .map(value -> (BigDecimal) value)
                .anyMatch(cost -> cost.compareTo(BigDecimal.valueOf(expected)) == 0);
    }

    private static boolean hasAttempts(Map<String, Object> pairs, int expected) {
        return pairs.containsValue(Integer.valueOf(expected)) || pairs.containsValue(Long.valueOf(expected));
    }

    // ==================== 基础硬门 ====================

    @Test
    void fallbackAttemptsCountedTowardBudget() {
        stubChatWithFallbackAttempts();

        NewsLlmBudgetService.LlmCall call = service(LocalDate.of(2026, 9, 29)).call(request("正文A"), Tier.FAST);

        assertEquals("{\"ok\":true}", call.content());
        verify(llmService, times(1)).chat(any(ChatRequest.class), any(Tier.class));
        List<Map<String, Object>> pairs = capturedUpdatePairs();
        // write-ahead 两次：attempts=1（主选发出前）与 attempts=2（fallback 发出前），单次逻辑调用
        assertTrue(pairs.stream().anyMatch(p -> hasAttempts(p, 1) && p.containsValue(NewsLlmBudgetService.STATUS_PENDING)),
                "第一次发出前记账 attempts=1，实际=" + pairs);
        // fallback 候选成本按上界入账：attempts=2 → 2×0.006848（修正点2/5）
        assertTrue(pairs.stream().anyMatch(p -> hasAttempts(p, 2) && hasCost(p, 2 * UPPER_BOUND_PLUS)),
                "fallback 发出前记账 attempts=2 且成本=2×上界，实际=" + pairs);
        // 成功回执：先落库再用（响应原文+实际服务模型）
        assertTrue(pairs.stream().anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_SUCCESS)
                        && p.containsValue("{\"ok\":true}") && p.containsValue("qwen-plus")),
                "成功响应与实际服务模型落回执，实际=" + pairs);
    }

    @Test
    void dailyBudgetExhaustedDegradesBeforeAnyAttempt() {
        // 日基数（其它指纹当日合计）已到 ¥1.0，下一次发出 1.0+0.006848 > 1.0 → 否决
        when(receiptMapper.selectObjs(any())).thenReturn(
                List.of(BigDecimal.ONE), List.of(BigDecimal.ZERO), List.of(BigDecimal.ZERO));
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "unreachable";
        });

        assertThrows(LlmBudgetExhaustedException.class,
                () -> service(LocalDate.of(2026, 9, 29)).call(request("正文B"), Tier.FAST));

        List<Map<String, Object>> pairs = capturedUpdatePairs();
        assertTrue(pairs.stream().anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_DEGRADED)),
                "预算耗尽落 DEGRADED 降级事件行，实际=" + pairs);
        assertTrue(pairs.stream().noneMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_SUCCESS)),
                "被否决请求不得产生成功回执");
    }

    @Test
    void monthlyBudgetBlocksWhenDailyStillFine() {
        // 日基数 0、月基数（其它指纹）已到 ¥10 → 月额度否决（月 ¥10 保守默认，修正点1）
        when(receiptMapper.selectObjs(any())).thenReturn(
                List.of(BigDecimal.ZERO), List.of(BigDecimal.TEN), List.of(BigDecimal.ZERO));
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "unreachable";
        });

        assertThrows(LlmBudgetExhaustedException.class,
                () -> service(LocalDate.of(2026, 9, 29)).call(request("正文C"), Tier.FAST));
        assertTrue(capturedUpdatePairs().stream()
                        .anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_DEGRADED)),
                "月额度否决同样落 DEGRADED，实际=" + capturedUpdatePairs());
    }

    @Test
    void existingReceiptReusedWithoutPayingAgain() {
        NewsLlmReceiptDO cached = NewsLlmReceiptDO.builder()
                .id(9L).requestFingerprint("fp-x").attempts(3).statDate("2026-09-28").statMonth("2026-09")
                .status(NewsLlmBudgetService.STATUS_SUCCESS)
                .responseText("{\"cached\":true}").build();
        when(receiptMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cached));

        NewsLlmBudgetService.LlmCall call = service(LocalDate.of(2026, 9, 29)).call(request("正文D"), Tier.FAST);

        assertEquals("{\"cached\":true}", call.content());
        assertTrue(call.reused());
        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
        verify(receiptMapper, never()).insert(any(NewsLlmReceiptDO.class));
    }

    @Test
    void poisonedReceiptIsNotReplayed() {
        NewsLlmReceiptDO poisoned = NewsLlmReceiptDO.builder()
                .id(8L).requestFingerprint("fp-poison").attempts(4)
                .status(NewsLlmBudgetService.STATUS_POISONED)
                .errorBrief("复用响应解析无效，已隔离不无限复读").build();
        when(receiptMapper.selectList(any(Wrapper.class))).thenReturn(List.of(poisoned));

        assertThrows(IllegalStateException.class,
                () -> service(LocalDate.of(2026, 9, 29)).call(request("正文F"), Tier.FAST));
        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
    }

    @Test
    void invalidReusedResponsePoisonsReceipt() {
        NewsLlmReceiptDO cached = NewsLlmReceiptDO.builder()
                .id(7L).requestFingerprint("fp-reuse").attempts(1)
                .status(NewsLlmBudgetService.STATUS_SUCCESS)
                .responseText("not-json").build();
        when(receiptMapper.selectList(any(Wrapper.class))).thenReturn(List.of(cached));

        NewsLlmBudgetService.LlmCall call = service(LocalDate.of(2026, 9, 29)).call(request("正文G"), Tier.FAST);
        assertTrue(call.reused());
        call.reportInvalid();

        assertTrue(capturedUpdatePairs().stream()
                        .anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_POISONED)),
                "复用响应解析无效 → 清除响应并隔离，实际=" + capturedUpdatePairs());
    }

    @Test
    void freshResponseParseFailureKeepsOneReuse() {
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "bad-json";
        });

        NewsLlmBudgetService.LlmCall call = service(LocalDate.of(2026, 9, 29)).call(request("正文H"), Tier.FAST);
        assertEquals("bad-json", call.content());
        call.reportInvalid();

        List<Map<String, Object>> pairs = capturedUpdatePairs();
        assertTrue(pairs.stream().noneMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_POISONED)));
        assertTrue(pairs.stream().anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_FAILED)
                        && p.containsValue("响应解析无效，保留一次复用")),
                "新鲜响应解析失败保留一次复用，实际=" + pairs);
    }

    // ==================== 修正点2：成本上界推导 ====================

    @Test
    void costUpperBoundDerivedFromMostExpensiveChainCandidate() {
        NewsLlmBudgetService service = service(LocalDate.of(2026, 9, 29));
        // 现行链 [qwen-flash, qwen-plus]：取 qwen-plus（最贵）×完整限额（输入 6000、输出 1024）
        assertEquals(0, service.costPerAttemptUpperBound().compareTo(BigDecimal.valueOf(UPPER_BOUND_PLUS)),
                "上界=(6000×0.8+1024×2)/1e6=0.006848，实际=" + service.costPerAttemptUpperBound());
        // 仅 flash 链：(6000×0.5+1024×2)/1e6=0.005048
        when(modelSelector.selectChatCandidates(anyBoolean(), any())).thenReturn(List.of(QWEN_FLASH));
        assertEquals(0, service.costPerAttemptUpperBound().compareTo(BigDecimal.valueOf(0.005048D)),
                "flash 单候选链上界=0.005048");
        // 链内出现未配价候选：按表内最贵（qwen-plus）兜底，不静默
        when(modelSelector.selectChatCandidates(anyBoolean(), any()))
                .thenReturn(List.of(QWEN_FLASH, new ModelTarget("mystery-model",
                        new AIModelProperties.ModelCandidate(), new AIModelProperties.ProviderConfig(), 30_000L)));
        assertEquals(0, service.costPerAttemptUpperBound().compareTo(BigDecimal.valueOf(UPPER_BOUND_PLUS)),
                "未配价候选按表内最贵单价兜底");
    }

    // ==================== 修正点3：跨日/跨月账目归属 ====================

    @Test
    void crossDayRetryKeepsHistoryLedgerAttribution() {
        // 本地桩：insert 落盘行对象，selectOne 回最新行——模拟真实库「重试会话续读同一当日行」
        List<NewsLlmReceiptDO> persistedRows = new java.util.ArrayList<>();
        when(receiptMapper.insert(any(NewsLlmReceiptDO.class))).thenAnswer(invocation -> {
            NewsLlmReceiptDO row = invocation.getArgument(0);
            row.setId((long) idSeq.incrementAndGet());
            persistedRows.add(row);
            return 1;
        });
        when(receiptMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation ->
                persistedRows.isEmpty() ? null : persistedRows.get(persistedRows.size() - 1));
        // 日1：三次发出全部失败（重试耗尽），当日周期行 attempts=3、FAILED
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            throw new IllegalStateException("provider down");
        });
        assertThrows(IllegalStateException.class,
                () -> service(LocalDate.of(2026, 9, 29)).call(request("跨日请求"), Tier.FAST));
        int updatesAfterDay1 = capturedUpdatePairs().size();
        assertEquals(1, idSeq.get(), "日1 重试全部落同一当日周期行");
        NewsLlmReceiptDO day1Row = persistedRows.get(0);

        // 日2：同指纹重试成功（历史行无响应可复用）——当日行不存在，新开周期行
        persistedRows.clear();
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "{\"ok\":true}";
        });
        when(receiptMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                NewsLlmReceiptDO.builder().id(day1Row.getId()).requestFingerprint("fp-day1")
                        .statDate("2026-09-29").statMonth("2026-09").attempts(3).retries(2)
                        .status(NewsLlmBudgetService.STATUS_FAILED).build()));
        NewsLlmBudgetService.LlmCall call = service(LocalDate.of(2026, 9, 30)).call(request("跨日请求"), Tier.FAST);
        assertEquals("{\"ok\":true}", call.content());

        assertEquals(2, idSeq.get(), "跨日重试新开周期行（日2 独立行）");
        List<Map<String, Object>> allPairs = capturedUpdatePairs();
        List<Map<String, Object>> day2Pairs = allPairs.subList(updatesAfterDay1, allPairs.size());
        assertTrue(day2Pairs.stream().allMatch(p -> p.containsValue(2L)),
                "日2 的全部更新只落在新周期行（id=2），实际=" + day2Pairs);
        assertTrue(day2Pairs.stream().anyMatch(p -> hasAttempts(p, 1)),
                "日2 行 attempts 从 1 起算（历史 attempts=3 留在日1 行）");
        // 周期键不可变：任何 update 都不再携带 stat_date/stat_month（keys 随行落定）
        assertTrue(allPairs.stream().noneMatch(p -> p.values().stream()
                        .anyMatch(v -> String.valueOf(v).matches("2026-\\d{2}-\\d{2}") || String.valueOf(v).matches("2026-\\d{2}"))),
                "周期键随行落定后不被 update 改写，实际=" + allPairs);
    }

    @Test
    void crossMonthRetryOpensNewPeriodRowAndKeepsMonthLedger() {
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "{\"ok\":true}";
        });
        service(LocalDate.of(2026, 9, 30)).call(request("跨月请求"), Tier.FAST);
        int insertsAfterSep = idSeq.get();

        // 10 月重试：历史行（9 月）无响应 → 新周期行 stat_month=2026-10
        when(receiptMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                NewsLlmReceiptDO.builder().id(1L).requestFingerprint("fp-month1")
                        .statDate("2026-09-30").statMonth("2026-09").attempts(1)
                        .status(NewsLlmBudgetService.STATUS_FAILED).build()));
        service(LocalDate.of(2026, 10, 1)).call(request("跨月请求"), Tier.FAST);

        assertEquals(insertsAfterSep + 1, idSeq.get(), "跨月重试新开周期行");
        ArgumentCaptor<NewsLlmReceiptDO> insertCaptor = ArgumentCaptor.forClass(NewsLlmReceiptDO.class);
        verify(receiptMapper, times(2)).insert(insertCaptor.capture());
        assertEquals("2026-09", insertCaptor.getAllValues().get(0).getStatMonth(), "9 月账目留在 9 月行");
        assertEquals("2026-10", insertCaptor.getAllValues().get(1).getStatMonth(), "10 月重试开新行");
        // 周期键不可变：update 不携带任何周期键
        assertTrue(capturedUpdatePairs().stream().noneMatch(p -> p.values().stream()
                        .anyMatch(v -> String.valueOf(v).matches("2026-\\d{2}-?\\d{0,2}"))),
                "月度归属不被 update 改写");
    }

    // ==================== 修正点4：重试同指纹累计 ====================

    @Test
    void retriesCumulativeAcrossSchedulesExhaustedStopsRetrying() {
        // 历史已累计 retries=2（此前调度耗尽过）→ 本次预算 0：单次失败即上抛，不再重试
        NewsLlmReceiptDO historyRow = NewsLlmReceiptDO.builder()
                .id(1L).requestFingerprint("fp-retries").statDate("2026-09-29").statMonth("2026-09")
                .attempts(3).retries(2).status(NewsLlmBudgetService.STATUS_FAILED).build();
        when(receiptMapper.selectList(any(Wrapper.class))).thenReturn(List.of(historyRow));
        when(receiptMapper.selectOne(any(Wrapper.class))).thenReturn(historyRow);
        AtomicInteger chatCalls = new AtomicInteger();
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            chatCalls.incrementAndGet();
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            throw new IllegalStateException("still down");
        });

        assertThrows(IllegalStateException.class,
                () -> service(LocalDate.of(2026, 9, 29)).call(request("累计重试请求"), Tier.FAST));

        assertEquals(1, chatCalls.get(), "同指纹累计重试已耗尽（2/2）：单次失败即上抛，不再重试");
        assertTrue(capturedUpdatePairs().stream().anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_FAILED)));
    }

    @Test
    void retryBudgetContinuesFromPersistedRetries() {
        // 历史累计 retries=1：本次还剩 1 次重试预算 → 共 2 次调用后耗尽
        NewsLlmReceiptDO historyRow = NewsLlmReceiptDO.builder()
                .id(1L).requestFingerprint("fp-cont").statDate("2026-09-29").statMonth("2026-09")
                .attempts(1).retries(1).status(NewsLlmBudgetService.STATUS_PENDING).build();
        when(receiptMapper.selectList(any(Wrapper.class))).thenReturn(List.of(historyRow));
        when(receiptMapper.selectOne(any(Wrapper.class))).thenReturn(historyRow);
        AtomicInteger chatCalls = new AtomicInteger();
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            chatCalls.incrementAndGet();
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            throw new IllegalStateException("down");
        });

        assertThrows(IllegalStateException.class,
                () -> service(LocalDate.of(2026, 9, 29)).call(request("续算请求"), Tier.FAST));
        assertEquals(2, chatCalls.get(), "历史 retries=1 → 本次仅剩 1 次重试（共 2 次调用）");
        // 累计口径落库：FAILED 行 retries=历史1+本次1=2
        assertTrue(capturedUpdatePairs().stream().anyMatch(p ->
                        p.containsValue(NewsLlmBudgetService.STATUS_FAILED) && hasRetries(p, 2)),
                "累计 retries=2 落库，实际=" + capturedUpdatePairs());
    }

    private static boolean hasRetries(Map<String, Object> pairs, int expected) {
        return pairs.containsValue(Integer.valueOf(expected)) || pairs.containsValue(Long.valueOf(expected));
    }

    // ==================== 重启恢复 ====================

    @Test
    void restartKeepsBudgetEnforcementFromPersistedTotals() {
        // 进程 A：日基数 0.99，本次 1 次发出 0.006848 → 0.996848 ≤ 1.0 放行（write-ahead 已落库）
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "{\"ok\":true}";
        });
        when(receiptMapper.selectObjs(any())).thenReturn(
                List.of(new BigDecimal("0.99")), List.of(BigDecimal.ZERO), List.of(BigDecimal.ZERO));
        NewsLlmBudgetService processA = service(LocalDate.of(2026, 9, 29));
        assertEquals("{\"ok\":true}", processA.call(request("进程A请求"), Tier.FAST).content());

        // 进程 B（新实例=重启，无内存态）：库中日基数已含 A 的 0.006848 → 0.996848，再发即超限 → 否决
        when(receiptMapper.selectObjs(any())).thenReturn(
                List.of(new BigDecimal("0.996848")), List.of(BigDecimal.ZERO), List.of(BigDecimal.ZERO));
        NewsLlmBudgetService processB = service(LocalDate.of(2026, 9, 29));
        assertThrows(LlmBudgetExhaustedException.class,
                () -> processB.call(request("进程B请求"), Tier.FAST));
    }

    // ==================== 指纹 ====================

    @Test
    void fingerprintCoversPromptModelAndEffectiveParams() {
        NewsLlmBudgetService service = service(LocalDate.of(2026, 9, 29));
        ChatRequest base = request("提示词X");
        String baseline = service.fingerprintOf(base, Tier.FAST);
        assertEquals(baseline, service.fingerprintOf(request("提示词X"), Tier.FAST), "同请求同指纹");
        assertNotEquals(baseline, service.fingerprintOf(request("提示词Y"), Tier.FAST),
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
        assertNotEquals(baseline, service.fingerprintOf(request("提示词X"), Tier.FAST), "主选模型变化换指纹");
    }

    @Test
    void periodKeysPersistedOnRowInsertWithHktDayAndMonth() {
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "{\"ok\":true}";
        });

        service(LocalDate.of(2026, 9, 29)).call(request("正文I"), Tier.FAST);

        ArgumentCaptor<NewsLlmReceiptDO> insertCaptor = ArgumentCaptor.forClass(NewsLlmReceiptDO.class);
        verify(receiptMapper, times(1)).insert(insertCaptor.capture());
        NewsLlmReceiptDO inserted = insertCaptor.getValue();
        assertEquals("2026-09-29", inserted.getStatDate(), "stat_date 按 HKT 日随行落库");
        assertEquals("2026-09", inserted.getStatMonth(), "stat_month 随行落库");
        assertEquals(0, inserted.getCostEstimate().compareTo(BigDecimal.ZERO));
    }

    // ==================== 预算耗尽异常隔离（executor 侧证据见 infra-ai ModelRoutingExecutorTest） ====================

    @Test
    void budgetExhaustedExceptionCarriesDualCaliberDiagnostics() {
        when(receiptMapper.selectObjs(any())).thenReturn(
                List.of(BigDecimal.ONE), List.of(BigDecimal.ZERO), List.of(BigDecimal.ZERO));
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "unreachable";
        });

        AtomicReference<LlmBudgetExhaustedException> caught = new AtomicReference<>();
        try {
            service(LocalDate.of(2026, 9, 29)).call(request("正文J"), Tier.FAST);
        } catch (LlmBudgetExhaustedException e) {
            caught.set(e);
        }
        assertTrue(caught.get().getMessage().contains("日"), "异常携带双口径诊断信息："
                + caught.get().getMessage());
    }
}
