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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 匿名试用配额守卫单元测试：非游客零开销、双键扣减、首写过期、超额拒绝
 */
class AnonymousTrialGuardTest {

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private AnonymousTrialGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        guard = new AnonymousTrialGuard(stringRedisTemplate);
        ReflectionTestUtils.setField(guard, "dailyLimit", 3);
    }

    private LoginUser guest() {
        return LoginUser.builder().userId("999").username("guest-abc").role("guest").build();
    }

    private LoginUser normalUser() {
        return LoginUser.builder().userId("1").username("alice").role("user").build();
    }

    @Test
    void nonGuestPassesWithoutRedisTouch() {
        assertDoesNotThrow(() -> guard.checkAndConsume(normalUser(), "1.2.3.4"));
        assertDoesNotThrow(() -> guard.checkAndConsume(null, "1.2.3.4"));
        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    void guestConsumesUserAndIpCountersWithFirstWriteExpiry() {
        String day = LocalDate.now(ZoneId.of("Asia/Hong_Kong")).toString();
        when(valueOperations.increment("rag:anon:quota:user:999:" + day)).thenReturn(1L);
        when(valueOperations.increment("rag:anon:quota:ip:1.2.3.4:" + day)).thenReturn(1L);

        assertDoesNotThrow(() -> guard.checkAndConsume(guest(), "1.2.3.4"));

        verify(stringRedisTemplate).expireAt(eq("rag:anon:quota:user:999:" + day), any(java.util.Date.class));
        verify(stringRedisTemplate).expireAt(eq("rag:anon:quota:ip:1.2.3.4:" + day), any(java.util.Date.class));
    }

    @Test
    void guestRejectedWhenUserQuotaExhausted() {
        String day = LocalDate.now(ZoneId.of("Asia/Hong_Kong")).toString();
        when(valueOperations.increment("rag:anon:quota:user:999:" + day)).thenReturn(4L);

        assertThrows(ClientException.class, () -> guard.checkAndConsume(guest(), "1.2.3.4"));
    }

    @Test
    void zeroOrNegativeLimitMeansUnlimited() {
        ReflectionTestUtils.setField(guard, "dailyLimit", 0);
        assertDoesNotThrow(() -> guard.checkAndConsume(guest(), "1.2.3.4"));
        verify(valueOperations, never()).increment(anyString());
    }
}
