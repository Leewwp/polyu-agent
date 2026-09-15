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

/**
 * 滑动窗口计数器（限速底座）：每次自增都会把窗口过期时间刷新到「now + window」，
 * 即窗口自最后一次计数起算滑动——达限后的封锁时长恰等于窗口长度。
 *
 * <p>接口化的目的是测试可注入：单测用假实现携带可拨时钟，验证
 * 未达限/达限拒绝/窗口恢复三态，生产用 Redis 实现。
 */
public interface WindowCounter {

    /**
     * 自增一次并返回窗口内当前计数；窗口自本次起重新滑动
     */
    long increment(String key, Duration window);

    /**
     * 只读当前计数（不动窗口）；无计数或已过期返回 0
     */
    long current(String key);
}
