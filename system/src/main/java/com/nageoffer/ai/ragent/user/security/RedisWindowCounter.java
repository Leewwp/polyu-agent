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

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis 滑动窗口计数器：INCR + 每次刷新 EXPIRE（窗口自最后一次计数起滑动）。
 * 键随过期自动清除，无需兜底清理任务。
 */
@Component
@RequiredArgsConstructor
public class RedisWindowCounter implements WindowCounter {

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public long increment(String key, Duration window) {
        Long value = stringRedisTemplate.opsForValue().increment(key);
        stringRedisTemplate.expire(key, window);
        return value == null ? 0L : value;
    }

    @Override
    public long current(String key) {
        String value = stringRedisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }
}
