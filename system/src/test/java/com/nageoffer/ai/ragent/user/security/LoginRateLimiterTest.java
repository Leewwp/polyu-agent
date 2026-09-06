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

package com.nageoffer.ai.ragent.user.security;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import com.nageoffer.ai.ragent.framework.exception.ClientException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录限速单元测试：初始化幂等 + 双维度计数 + 超限拒绝 + 键名清洗
 */
class LoginRateLimiterTest {

    private RedissonClient redissonClient;
    private RRateLimiter limiter;
    private LoginRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
        limiter = mock(RRateLimiter.class);
        when(redissonClient.<RRateLimiter>getRateLimiter(any(String.class))).thenReturn(limiter);
        when(limiter.trySetRate(any(RateType.class), anyLong(), anyLong(), any(RateIntervalUnit.class)))
                .thenReturn(true);
        rateLimiter = new LoginRateLimiter(redissonClient);
        setWindow(rateLimiter, 10, 300);
    }

    private void setWindow(LoginRateLimiter limiter, int attempts, int windowSeconds) {
        org.springframework.test.util.ReflectionTestUtils.setField(limiter, "attemptsPerWindow", attempts);
        org.springframework.test.util.ReflectionTestUtils.setField(limiter, "windowSeconds", windowSeconds);
    }

    @Test
    void acquireConsumesBothDimensionsWhenAvailable() {
        when(limiter.tryAcquire()).thenReturn(true);
        rateLimiter.acquire("127.0.0.1", "admin");
        verify(redissonClient).getRateLimiter("ragent:rl:login:ip:127.0.0.1");
        verify(redissonClient).getRateLimiter("ragent:rl:login:user:admin");
        verify(limiter, org.mockito.Mockito.times(2)).trySetRate(RateType.OVERALL, 10, 300, RateIntervalUnit.SECONDS);
        verify(limiter, org.mockito.Mockito.times(2)).expireIfNotSet(any(Duration.class));
    }

    @Test
    void throwsWhenAnyDimensionExhausted() {
        when(limiter.tryAcquire()).thenReturn(false);
        assertThrows(ClientException.class, () -> rateLimiter.acquire("127.0.0.1", "admin"));
    }

    @Test
    void sanitizesUnsafeCharactersInKeys() {
        when(limiter.tryAcquire()).thenReturn(true);
        rateLimiter.acquire("1.2.3.4, 5.6.7.8", "a b/c");
        verify(redissonClient).getRateLimiter("ragent:rl:login:ip:1.2.3.4__5.6.7.8");
        verify(redissonClient).getRateLimiter("ragent:rl:login:user:a_b_c");
        verify(limiter, never()).expireIfNotSet((Duration) null);
    }
}
