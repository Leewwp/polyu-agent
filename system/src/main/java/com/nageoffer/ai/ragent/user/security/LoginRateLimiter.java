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

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.nageoffer.ai.ragent.framework.exception.ClientException;

import lombok.RequiredArgsConstructor;

/**
 * 登录尝试限速：IP 与用户名两个维度各自固定窗口计数，任一超限即拒绝。
 *
 * <p>Redisson RRateLimiter 以 trySetRate 幂等初始化（整体速率），并给键设置兜底过期，
 * 避免限速键永久驻留；计数语义为“窗口内允许 N 次尝试”，成功登录同样计一次（实现简单，
 * 且正常用户 5 分钟内 10 次登录已远超日常）。
 */
@Component
@RequiredArgsConstructor
public class LoginRateLimiter {

    private static final String KEY_PREFIX = "ragent:rl:login:";

    private final RedissonClient redissonClient;

    @Value("${ragent.security.login.attempts-per-window:10}")
    private int attemptsPerWindow;

    @Value("${ragent.security.login.window-seconds:300}")
    private int windowSeconds;

    /**
     * 消耗一次登录尝试额度；任一维度超限抛 ClientException
     */
    public void acquire(String ip, String username) {
        check(KEY_PREFIX + "ip:" + sanitize(ip));
        check(KEY_PREFIX + "user:" + sanitize(username));
    }

    private void check(String key) {
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        limiter.trySetRate(RateType.OVERALL, attemptsPerWindow, windowSeconds, RateIntervalUnit.SECONDS);
        limiter.expireIfNotSet(Duration.ofSeconds(windowSeconds * 2L));
        if (!limiter.tryAcquire()) {
            throw new ClientException("登录尝试过于频繁，请稍后再试");
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
