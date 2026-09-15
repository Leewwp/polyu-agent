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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 登录失败限速三态测试（注入时钟/计数器验证 未达限/达限拒/窗口恢复）。
 * 数值口径：5 次/15 分钟/（IP+账号）双键，超限锁 15 分钟。
 */
class LoginRateLimiterTest {

    private static final String IP = "203.0.113.7";
    private static final String USER = "someone@example.com";

    private FakeWindowCounter counter;
    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        counter = new FakeWindowCounter();
        limiter = new LoginRateLimiter(counter);
        org.springframework.test.util.ReflectionTestUtils.setField(limiter, "maxFailures", 5);
        org.springframework.test.util.ReflectionTestUtils.setField(limiter, "lockSeconds", 900);
    }

    private void fail(String ip, String user) {
        limiter.recordFailure(ip, user);
    }

    @Test
    void underLimitLoginIsAllowed() {
        // 未达限：4 次失败后仍放行（第 5 次才达限）
        for (int i = 0; i < 4; i++) {
            fail(IP, USER);
        }
        assertDoesNotThrow(() -> limiter.checkLocked(IP, USER));
    }

    @Test
    void reachingLimitLocksLogin() {
        for (int i = 0; i < 5; i++) {
            fail(IP, USER);
        }
        assertThrows(ClientException.class, () -> limiter.checkLocked(IP, USER));
    }

    @Test
    void ipKeyLocksEvenWithDifferentUsernames() {
        // 账号键随机化（撞库换用户名）时，IP 键仍兜底锁住
        for (int i = 0; i < 5; i++) {
            fail(IP, "victim-" + i + "@example.com");
        }
        assertThrows(ClientException.class, () -> limiter.checkLocked(IP, "fresh-" + USER));
    }

    @Test
    void userKeyLocksEvenFromDifferentIps() {
        // IP 随机化（分布式撞库单账号）时，账号键仍兜底锁住
        for (int i = 0; i < 5; i++) {
            fail("198.51.100." + i, USER);
        }
        assertThrows(ClientException.class, () -> limiter.checkLocked("203.0.113.99", USER));
    }

    @Test
    void windowRecoveryUnlocksAfterLockPeriod() {
        for (int i = 0; i < 5; i++) {
            fail(IP, USER);
        }
        assertThrows(ClientException.class, () -> limiter.checkLocked(IP, USER));

        // 时间旅行越过锁定窗口（15 分钟）即恢复
        counter.advance(Duration.ofSeconds(901));
        assertDoesNotThrow(() -> limiter.checkLocked(IP, USER));
    }

    @Test
    void windowSlidesFromLastFailure() {
        for (int i = 0; i < 4; i++) {
            fail(IP, USER);
        }
        // 第 4 次失败后过 14 分钟：窗口自最后一次失败滑动，仍未过期
        counter.advance(Duration.ofMinutes(14));
        fail(IP, USER);
        assertThrows(ClientException.class, () -> limiter.checkLocked(IP, USER));

        // 再过 15 分钟（距最后一次失败）恢复
        counter.advance(Duration.ofSeconds(901));
        assertDoesNotThrow(() -> limiter.checkLocked(IP, USER));
    }

    @Test
    void checkLockedDoesNotIncreaseCount() {
        for (int i = 0; i < 5; i++) {
            fail(IP, USER);
        }
        // 反复探测锁状态不增加计数（检查只读）
        for (int i = 0; i < 10; i++) {
            assertThrows(ClientException.class, () -> limiter.checkLocked(IP, USER));
        }
        counter.advance(Duration.ofSeconds(901));
        assertDoesNotThrow(() -> limiter.checkLocked(IP, USER));
    }

    @Test
    void tryAcquireRejectsOverLimitAndRecovers() {
        String scope = "ragent:rl:guest:";
        for (int i = 0; i < 10; i++) {
            assertDoesNotThrow(() -> limiter.tryAcquire(scope, IP, 10, Duration.ofSeconds(300)));
        }
        assertThrows(ClientException.class, () -> limiter.tryAcquire(scope, IP, 10, Duration.ofSeconds(300)));

        counter.advance(Duration.ofSeconds(301));
        assertDoesNotThrow(() -> limiter.tryAcquire(scope, IP, 10, Duration.ofSeconds(300)));
    }

    @Test
    void keysAreSanitizedAgainstRedisSeparatorInjection() {
        // 含冒号/空格的输入不会拼出越界键（与正常键冲突）
        limiter.recordFailure("1.2.3.4:extra", "a b");
        assertDoesNotThrow(() -> limiter.checkLocked("1.2.3.4", "a"));
    }
}
