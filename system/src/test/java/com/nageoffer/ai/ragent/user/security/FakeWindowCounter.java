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
import java.util.HashMap;
import java.util.Map;

/**
 * 测试用假窗口计数器：内置可拨时钟，支撑三态验证（未达限/达限拒绝/窗口恢复）。
 * 语义与 {@link RedisWindowCounter} 对齐：每次自增把过期刷新到 now+window。
 */
public class FakeWindowCounter implements WindowCounter {

    private final Map<String, Long> counts = new HashMap<>();
    private final Map<String, Long> expireAtMillis = new HashMap<>();
    private long nowMillis = 1_000_000L;

    /**
     * 时钟快进（时间旅行）
     */
    public void advance(Duration duration) {
        nowMillis += duration.toMillis();
    }

    public long now() {
        return nowMillis;
    }

    @Override
    public long increment(String key, Duration window) {
        if (expired(key)) {
            counts.put(key, 1L);
        } else {
            counts.merge(key, 1L, Long::sum);
        }
        expireAtMillis.put(key, nowMillis + window.toMillis());
        return counts.get(key);
    }

    @Override
    public long current(String key) {
        return expired(key) ? 0L : counts.getOrDefault(key, 0L);
    }

    private boolean expired(String key) {
        Long expireAt = expireAtMillis.get(key);
        return expireAt == null || nowMillis >= expireAt;
    }
}
