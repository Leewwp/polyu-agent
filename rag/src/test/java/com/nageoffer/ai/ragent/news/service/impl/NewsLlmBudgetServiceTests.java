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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * 资讯 LLM 预算护栏+付费回执测试（#184 硬门）
 *
 * <p>覆盖：attempts 含 fallback 计数、日/月额度否决、超额降级落 DEGRADED、
 * 请求指纹复用不重复付费、网关重试 ≤2、重启后预算按持久化合计续算、
 * 指纹覆盖提示词/模型/参数、POISONED 不无限复读。Mapper 全 mock
 * （NewsEnrichServiceTests 先例），逐次发出经 LlmAttemptScope 模拟
 * （executor 侧逐次回调保证由 infra-ai ModelRoutingExecutorTest 承担）。
 */
class NewsLlmBudgetServiceTests {

    private static final ModelTarget QWEN_FLASH = new ModelTarget("qwen-flash",
            new AIModelProperties.ModelCandidate(), new AIModelProperties.ProviderConfig(), 30_000L);

    private static final ModelTarget QWEN_TURBO = new ModelTarget("qwen-turbo",
            new AIModelProperties.ModelCandidate(), new AIModelProperties.ProviderConfig(), 30_000L);

    private NewsLlmReceiptMapper receiptMapper;
    private LLMService llmService;
    private ModelSelector modelSelector;
    private NewsFetchProperties properties;

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, NewsLlmReceiptDO.class);
        receiptMapper = mock(NewsLlmReceiptMapper.class);
        llmService = mock(LLMService.class);
        modelSelector = mock(ModelSelector.class);
        properties = new NewsFetchProperties();
        when(modelSelector.selectChatCandidates(anyBoolean(), any())).thenReturn(List.of(QWEN_FLASH));
        // 默认：无历史行、日/月其它行成本合计 0
        when(receiptMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(receiptMapper.selectObjs(any())).thenReturn(List.of(BigDecimal.ZERO));
        when(receiptMapper.insert(any(NewsLlmReceiptDO.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, NewsLlmReceiptDO.class).setId(1L);
            return 1;
        });
    }

    private NewsLlmBudgetService service() {
        return new NewsLlmBudgetService(receiptMapper, llmService, modelSelector, properties,
                () -> Date.from(LocalDate.of(2026, 9, 29).atTime(10, 0)
                        .atZone(NewsLlmBudgetService.HKT_ZONE).toInstant()));
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
     * 主选一次（随后失败）+fallback 一次（成功），共两次真实发出
     */
    private void stubChatWithFallbackAttempts() {
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            LlmAttemptScope.notifyBeforeAttempt(QWEN_TURBO);
            return "{\"ok\":true}";
        });
    }

    private List<Map<String, Object>> capturedUpdatePairs() {
        ArgumentCaptor<Wrapper<NewsLlmReceiptDO>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(receiptMapper, org.mockito.Mockito.atLeastOnce()).update(org.mockito.ArgumentMatchers.eq((NewsLlmReceiptDO) null), captor.capture());
        return captor.getAllValues().stream()
                .map(wrapper -> ((LambdaUpdateWrapper<NewsLlmReceiptDO>) wrapper).getParamNameValuePairs())
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

    @Test
    void fallbackAttemptsCountedTowardBudget() {
        stubChatWithFallbackAttempts();

        NewsLlmBudgetService.LlmCall call = service().call(request("正文A"), Tier.FAST);

        assertEquals("{\"ok\":true}", call.content());
        verify(llmService, times(1)).chat(any(ChatRequest.class), any(Tier.class));
        List<Map<String, Object>> pairs = capturedUpdatePairs();
        // write-ahead 两次：attempts=1（主选发出前）与 attempts=2（fallback 发出前），单次逻辑调用
        assertTrue(pairs.stream().anyMatch(p -> Integer.valueOf(1).equals(p.get("MPGENVAL1"))
                        && p.containsValue(NewsLlmBudgetService.STATUS_PENDING)),
                "第一次发出前记账 attempts=1，实际=" + pairs);
        assertTrue(pairs.stream().anyMatch(p -> Integer.valueOf(2).equals(p.get("MPGENVAL1")) && hasCost(p, 0.01)),
                "fallback 发出前记账 attempts=2 且成本=2×0.005，实际=" + pairs);
        // 成功回执：先落库再用（响应原文+实际服务模型）
        assertTrue(pairs.stream().anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_SUCCESS)
                        && p.containsValue("{\"ok\":true}") && p.containsValue("qwen-turbo")),
                "成功响应与实际服务模型落回执，实际=" + pairs);
    }

    @Test
    void dailyBudgetExhaustedDegradesBeforeAnyAttempt() {
        // 日合计已到 ¥1.0（其它行），下一次发出 1.0+0.005 > 1.0 → 否决
        when(receiptMapper.selectObjs(any())).thenReturn(List.of(BigDecimal.ONE), List.of(BigDecimal.ZERO));
        // executor 形态：真实发出（client.chat）前回调观察者——此处被否决
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "unreachable";
        });

        NewsLlmBudgetService service = service();
        assertThrows(LlmBudgetExhaustedException.class, () -> service.call(request("正文B"), Tier.FAST));

        // 否决先于记账外任何副作用：无 SUCCESS 回执、无成本落库，仅 DEGRADED 事件行
        List<Map<String, Object>> pairs = capturedUpdatePairs();
        assertTrue(pairs.stream().anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_DEGRADED)),
                "预算耗尽落 DEGRADED 降级事件行，实际=" + pairs);
        assertTrue(pairs.stream().noneMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_SUCCESS)),
                "被否决请求不得产生成功回执");
    }

    @Test
    void monthlyBudgetBlocksWhenDailyStillFine() {
        // 日合计 0、月合计已到 ¥15 → 月额度否决
        when(receiptMapper.selectObjs(any())).thenReturn(List.of(BigDecimal.ZERO), List.of(new BigDecimal("15.0")));
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "unreachable";
        });

        assertThrows(LlmBudgetExhaustedException.class,
                () -> service().call(request("正文C"), Tier.FAST));
        assertTrue(capturedUpdatePairs().stream()
                        .anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_DEGRADED)),
                "月额度否决同样落 DEGRADED，实际=" + capturedUpdatePairs());
    }

    @Test
    void existingReceiptReusedWithoutPayingAgain() {
        NewsLlmReceiptDO cached = NewsLlmReceiptDO.builder()
                .id(9L).requestFingerprint("fp-x").attempts(3)
                .status(NewsLlmBudgetService.STATUS_SUCCESS)
                .responseText("{\"cached\":true}").build();
        when(receiptMapper.selectOne(any(Wrapper.class))).thenReturn(cached);

        NewsLlmBudgetService.LlmCall call = service().call(request("正文D"), Tier.FAST);

        assertEquals("{\"cached\":true}", call.content());
        assertTrue(call.reused());
        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
        verify(receiptMapper, never()).insert(any(NewsLlmReceiptDO.class));
    }

    @Test
    void gatewayRetriesCappedAtTwoThenFails() {
        AtomicInteger chatCalls = new AtomicInteger();
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            chatCalls.incrementAndGet();
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            throw new IllegalStateException("provider unavailable");
        });

        assertThrows(IllegalStateException.class, () -> service().call(request("正文E"), Tier.FAST));

        assertEquals(3, chatCalls.get(), "同请求重试 ≤2：首次+2 次重试后放弃");
        List<Map<String, Object>> pairs = capturedUpdatePairs();
        assertTrue(pairs.stream().anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_FAILED)
                        && Integer.valueOf(2).equals(p.get("MPGENVAL2"))),
                "重试耗尽落 FAILED 且 retries=2，实际=" + pairs);
    }

    @Test
    void restartKeepsBudgetEnforcementFromPersistedTotals() {
        // 进程 A：日其它行合计 0.995，本次 1 次发出 0.005 → 1.0 ≤ 1.0 放行（记账已 write-ahead 落库）
        when(receiptMapper.selectObjs(any())).thenReturn(List.of(new BigDecimal("0.995")), List.of(BigDecimal.ZERO));
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "{\"ok\":true}";
        });
        NewsLlmBudgetService processA = service();
        assertEquals("{\"ok\":true}", processA.call(request("进程A请求"), Tier.FAST).content());

        // 进程 B（新实例=重启，无内存态）：库中日合计已含 A 的 0.005 → 1.0，再发 0.005 超限 → 否决
        when(receiptMapper.selectObjs(any())).thenReturn(List.of(BigDecimal.ONE), List.of(BigDecimal.ZERO));
        NewsLlmBudgetService processB = service();
        assertThrows(LlmBudgetExhaustedException.class, () -> processB.call(request("进程B请求"), Tier.FAST));
    }

    @Test
    void fingerprintCoversPromptModelAndEffectiveParams() {
        NewsLlmBudgetService service = service();
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
        // 主选模型变化换指纹（提示词动态词表之外的第二道失效条件）
        when(modelSelector.selectChatCandidates(anyBoolean(), any())).thenReturn(List.of(QWEN_TURBO));
        assertNotEquals(baseline, service.fingerprintOf(request("提示词X"), Tier.FAST), "主选模型变化换指纹");
    }

    @Test
    void poisonedReceiptIsNotReplayed() {
        NewsLlmReceiptDO poisoned = NewsLlmReceiptDO.builder()
                .id(8L).requestFingerprint("fp-poison").attempts(4)
                .status(NewsLlmBudgetService.STATUS_POISONED)
                .errorBrief("复用响应解析无效，已隔离不无限复读").build();
        when(receiptMapper.selectOne(any(Wrapper.class))).thenReturn(poisoned);

        assertThrows(IllegalStateException.class, () -> service().call(request("正文F"), Tier.FAST));
        verify(llmService, never()).chat(any(ChatRequest.class), any(Tier.class));
    }

    @Test
    void invalidReusedResponsePoisonsReceipt() {
        NewsLlmReceiptDO cached = NewsLlmReceiptDO.builder()
                .id(7L).requestFingerprint("fp-reuse").attempts(1)
                .status(NewsLlmBudgetService.STATUS_SUCCESS)
                .responseText("not-json").build();
        when(receiptMapper.selectOne(any(Wrapper.class))).thenReturn(cached);

        NewsLlmBudgetService.LlmCall call = service().call(request("正文G"), Tier.FAST);
        assertTrue(call.reused());
        call.reportInvalid();

        List<Map<String, Object>> pairs = capturedUpdatePairs();
        assertTrue(pairs.stream().anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_POISONED)),
                "复用响应解析无效 → 清除响应并隔离，实际=" + pairs);
    }

    @Test
    void freshResponseParseFailureKeepsOneReuse() {
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "bad-json";
        });

        NewsLlmBudgetService.LlmCall call = service().call(request("正文H"), Tier.FAST);
        assertEquals("bad-json", call.content());
        call.reportInvalid();

        List<Map<String, Object>> pairs = capturedUpdatePairs();
        // 新鲜响应解析失败：不置 POISONED（保留一次下轮复用），标 FAILED
        assertTrue(pairs.stream().noneMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_POISONED)));
        assertTrue(pairs.stream().anyMatch(p -> p.containsValue(NewsLlmBudgetService.STATUS_FAILED)
                        && p.containsValue("响应解析无效，保留一次复用")),
                "新鲜响应解析失败保留一次复用，实际=" + pairs);
    }

    @Test
    void receiptRowPersistedWithHktDayAndMonthKeys() {
        when(llmService.chat(any(ChatRequest.class), any(Tier.class))).thenAnswer(invocation -> {
            LlmAttemptScope.notifyBeforeAttempt(QWEN_FLASH);
            return "{\"ok\":true}";
        });

        service().call(request("正文I"), Tier.FAST);

        // 固定时钟=2026-09-29 10:00 HKT：日/月键随 write-ahead 记账落库（重启不清零的持久化载体）
        assertTrue(capturedUpdatePairs().stream().anyMatch(p -> p.containsValue("2026-09-29") && p.containsValue("2026-09")),
                "stat_date/stat_month 按 HKT 落键，实际=" + capturedUpdatePairs());
        ArgumentCaptor<NewsLlmReceiptDO> insertCaptor = ArgumentCaptor.forClass(NewsLlmReceiptDO.class);
        verify(receiptMapper, times(1)).insert(insertCaptor.capture());
        NewsLlmReceiptDO inserted = insertCaptor.getValue();
        assertEquals("qwen-flash", inserted.getModelId(), "指纹成分模型随行落库");
        assertNotNull(inserted.getStatDate());
    }
}
