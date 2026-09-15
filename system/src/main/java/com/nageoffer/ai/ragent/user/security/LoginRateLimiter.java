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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 登录失败限速：登录失败 5 次/15 分钟/（IP+账号）双键，
 * 超限锁 15 分钟——计数只记失败（成功登录不计），窗口自最后一次失败滑动，
 * 因此「锁 15 分钟」= 距最后一次失败 15 分钟后自动解除。
 *
 * <p>旧实现（10 次/300s 固定窗口、成功也计数）已被本语义替换。
 * 另提供通用 {@link #tryAcquire}（按次数限流）给游客铸造等入口做防刷。
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final String FAIL_KEY_PREFIX = "ragent:rl:login:fail:";

    private final WindowCounter windowCounter;

    /**
     * 窗口内允许的登录失败次数（5 次/15 分钟）
     */
    @Value("${ragent.security.login.max-failures:5}")
    private int maxFailures;

    /**
     * 失败计数窗口=锁定时长（超限锁 15 分钟）
     */
    @Value("${ragent.security.login.lock-seconds:900}")
    private int lockSeconds;

    /**
     * 登录前置检查：IP 或账号任一键失败数达限即拒绝（不新增计数）
     */
    public void checkLocked(String ip, String username) {
        if (windowCounter.current(FAIL_KEY_PREFIX + "ip:" + sanitize(ip)) >= maxFailures
                || windowCounter.current(FAIL_KEY_PREFIX + "user:" + sanitize(username)) >= maxFailures) {
            throw new ClientException("登录失败次数过多，已临时锁定，请 15 分钟后再试");
        }
    }

    /**
     * 登录失败后计数：双键各记一次（用户不存在同样计——防用户名探测撞库）
     */
    public void recordFailure(String ip, String username) {
        Duration window = Duration.ofSeconds(lockSeconds);
        windowCounter.increment(FAIL_KEY_PREFIX + "ip:" + sanitize(ip), window);
        windowCounter.increment(FAIL_KEY_PREFIX + "user:" + sanitize(username), window);
    }

    /**
     * 通用按次限流：本次计入后超过 limit 即拒绝（游客铸造/注册/重置等入口共用）
     */
    public void tryAcquire(String scope, String key, int limit, Duration window) {
        long count = windowCounter.increment(scope + sanitize(key), window);
        if (count > limit) {
            throw new ClientException("操作过于频繁，请稍后再试");
        }
    }

    private String sanitize(String value) {
        if (value == null) {
            return "unknown";
        }
        // 键名只保留安全字符，防止注入 Redis 键分隔符
        return value.replaceAll("[^A-Za-z0-9.:_-]", "_");
    }
}
