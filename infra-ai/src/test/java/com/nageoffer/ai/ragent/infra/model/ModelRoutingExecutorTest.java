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

package com.nageoffer.ai.ragent.infra.model;

import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.http.ModelClientErrorType;
import com.nageoffer.ai.ragent.infra.http.ModelClientException;
import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelRoutingExecutorTest {

    @Test
    void shouldStopFallbackAndKeepHealthUnchangedWhenThreadIsInterrupted() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget primary = target("primary", "bailian");
        ModelTarget fallback = target("fallback", "deepseek");
        ModelHealthStore.CallPermit permit = new ModelHealthStore.CallPermit(primary.id(), 7L);
        when(healthStore.allowCall(primary.id())).thenReturn(permit);

        AtomicInteger calls = new AtomicInteger();
        try {
            assertThatThrownBy(() -> executor.executeWithFallback(
                    ModelCapability.CHAT,
                    List.of(primary, fallback),
                    ModelTarget::id,
                    (client, target) -> {
                        calls.incrementAndGet();
                        Thread.currentThread().interrupt();
                        throw new ModelClientException(
                                "request interrupted",
                                ModelClientErrorType.NETWORK_ERROR,
                                null,
                                new InterruptedIOException("interrupted"));
                    }))
                    .isInstanceOf(CancellationException.class);
        } finally {
            // 清除测试线程的中断标记，避免污染其他用例
            Thread.interrupted();
        }

        assertThat(calls).hasValue(1);
        verify(healthStore).releaseHalfOpenPermit(permit);
        verify(healthStore, never()).markFailure(primary.id());
        verify(healthStore, never()).allowCall(fallback.id());
    }

    @Test
    void shouldRecognizeWrappedInterruptedExceptionAndRestoreInterruptFlag() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget target = target("primary", "deepseek");
        ModelHealthStore.CallPermit permit = new ModelHealthStore.CallPermit(target.id(), 0L);
        when(healthStore.allowCall(target.id())).thenReturn(permit);

        try {
            assertThatThrownBy(() -> executor.executeWithFallback(
                    ModelCapability.CHAT,
                    List.of(target),
                    ModelTarget::id,
                    (client, ignored) -> {
                        throw new UndeclaredThrowableException(new InterruptedException("interrupted"));
                    }))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(healthStore).releaseHalfOpenPermit(permit);
        verify(healthStore, never()).markFailure(target.id());
    }

    @Test
    void shouldPropagateDirectCancellationWithoutFallbackOrFailureMark() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget primary = target("primary", "bailian");
        ModelTarget fallback = target("fallback", "deepseek");
        ModelHealthStore.CallPermit permit = new ModelHealthStore.CallPermit(primary.id(), 9L);
        when(healthStore.allowCall(primary.id())).thenReturn(permit);

        assertThatThrownBy(() -> executor.executeWithFallback(
                ModelCapability.CHAT,
                List.of(primary, fallback),
                ModelTarget::id,
                (client, ignored) -> {
                    throw new CancellationException("cancelled by client");
                }))
                .isInstanceOf(CancellationException.class);

        verify(healthStore).releaseHalfOpenPermit(permit);
        verify(healthStore, never()).markFailure(primary.id());
        verify(healthStore, never()).allowCall(fallback.id());
    }

    @Test
    void shouldStillFallbackForRealModelFailure() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget primary = target("primary", "bailian");
        ModelTarget fallback = target("fallback", "deepseek");
        when(healthStore.allowCall(primary.id()))
                .thenReturn(new ModelHealthStore.CallPermit(primary.id(), 0L));
        when(healthStore.allowCall(fallback.id()))
                .thenReturn(new ModelHealthStore.CallPermit(fallback.id(), 0L));

        String result = executor.executeWithFallback(
                ModelCapability.CHAT,
                List.of(primary, fallback),
                ModelTarget::id,
                (client, target) -> {
                    if (target == primary) {
                        throw new IllegalStateException("provider unavailable");
                    }
                    return "ok";
                });

        assertThat(result).isEqualTo("ok");
        verify(healthStore).markFailure(primary.id());
        verify(healthStore).markSuccess(fallback.id());
    }

    @Test
    void shouldNotPolluteModelHealthWhenBudgetExhausted() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget primary = target("primary", "bailian");
        ModelTarget fallback = target("fallback", "deepseek");
        ModelHealthStore.CallPermit permit = new ModelHealthStore.CallPermit(primary.id(), 11L);
        when(healthStore.allowCall(primary.id())).thenReturn(permit);

        // 预算护栏经 LlmAttemptScope 注册观察者，在下一次发出前否决（#184：真实链路同构）
        LlmBudgetExhaustedException budget = new LlmBudgetExhaustedException("资讯预算耗尽");
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        AtomicInteger callerInvocations = new AtomicInteger();
        try {
            LlmAttemptScope.callWithin(target1 -> {
                throw budget;
            }, () -> executor.executeWithFallback(
                    ModelCapability.CHAT,
                    List.of(primary, fallback),
                    ModelTarget::id,
                    (client, ignored) -> {
                        callerInvocations.incrementAndGet();
                        return "never-reached";
                    }));
        } catch (Throwable e) {
            thrown.set(e);
        }

        assertThat(thrown.get()).isSameAs(budget);
        assertThat(callerInvocations).hasValue(0);
        verify(healthStore, never()).markFailure(primary.id());
        verify(healthStore, never()).markSuccess(primary.id());
        verify(healthStore, never()).allowCall(fallback.id());
        verify(healthStore).releaseHalfOpenPermit(permit);
    }

    @Test
    void shouldNotifyObserverPerAttemptIncludingFallback() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget primary = target("primary", "bailian");
        ModelTarget fallback = target("fallback", "deepseek");
        when(healthStore.allowCall(primary.id()))
                .thenReturn(new ModelHealthStore.CallPermit(primary.id(), 0L));
        when(healthStore.allowCall(fallback.id()))
                .thenReturn(new ModelHealthStore.CallPermit(fallback.id(), 0L));

        List<String> observedAttempts = new java.util.ArrayList<>();
        String result = LlmAttemptScope.callWithin(
                target -> observedAttempts.add(target.id()),
                () -> executor.executeWithFallback(
                        ModelCapability.CHAT,
                        List.of(primary, fallback),
                        ModelTarget::id,
                        (client, target) -> {
                            if (target == primary) {
                                throw new IllegalStateException("primary down");
                            }
                            return "ok";
                        }));

        assertThat(result).isEqualTo("ok");
        // fallback 链上每个候选各回调一次（预算计数含 fallback 的机制保证）
        assertThat(observedAttempts).containsExactly(primary.id(), fallback.id());
        verify(healthStore).markFailure(primary.id());
        verify(healthStore).markSuccess(fallback.id());
    }

    @Test
    void shouldKeepScopeIsolationAfterCallWithinExits() {
        ModelHealthStore healthStore = mock(ModelHealthStore.class);
        ModelRoutingExecutor executor = new ModelRoutingExecutor(healthStore);
        ModelTarget target = target("solo", "bailian");
        when(healthStore.allowCall(target.id()))
                .thenReturn(new ModelHealthStore.CallPermit(target.id(), 0L));

        List<String> observed = new java.util.ArrayList<>();
        LlmAttemptScope.callWithin(t -> observed.add("scoped"), () ->
                executor.executeWithFallback(ModelCapability.CHAT, List.of(target),
                        ModelTarget::id, (client, ignored) -> "ok"));
        // 域外再调用：无观察者零开销直通（主 RAG 链路形态），不残留上一域的观察者
        executor.executeWithFallback(ModelCapability.CHAT, List.of(target),
                ModelTarget::id, (client, ignored) -> "ok");

        assertThat(observed).containsExactly("scoped");
    }

    private static ModelTarget target(String id, String provider) {
        AIModelProperties.ModelCandidate candidate = new AIModelProperties.ModelCandidate();
        candidate.setId(id);
        candidate.setProvider(provider);
        candidate.setModel(id);
        return new ModelTarget(id, candidate, new AIModelProperties.ProviderConfig(), 30_000L);
    }
}
