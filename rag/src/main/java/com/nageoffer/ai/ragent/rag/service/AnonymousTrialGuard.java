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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/**
 * 匿名试用配额守卫（T8，doc 13 §8.2）
 *
 * <p>只对 role=guest 的会话生效；普通用户/admin 直接放行（走既有限流体系）。
 * 配额按「游客身份 + 来源 IP」双键每日原子扣减（Redis INCR，首写设置当日过期），
 * 建议初始 3 次/日；额度终值与启用属维护者门（ragent.anonymous.*，默认关）。
 */
@Component
@RequiredArgsConstructor
public class AnonymousTrialGuard {

    private static final String ROLE_GUEST = "guest";
    private static final String KEY_PREFIX_USER = "rag:anon:quota:user:";
    private static final String KEY_PREFIX_IP = "rag:anon:quota:ip:";

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 匿名每日试用次数；0/负数 = 不限制（额度终值随门批复，建议 3）
     */
    @Value("${ragent.anonymous.daily-limit:3}")
    private int dailyLimit;

    /**
     * 检查并消耗一次匿名试用额度；非游客身份直接放行
     *
     * @throws ClientException 当日额度用尽
     */
    public void checkAndConsume(LoginUser user, String clientIp) {
        if (user == null || !ROLE_GUEST.equals(user.getRole())) {
            return;
        }
        if (dailyLimit <= 0) {
            return;
        }
        consume(KEY_PREFIX_USER + user.getUserId());
        if (clientIp != null && !clientIp.isBlank()) {
            consume(KEY_PREFIX_IP + clientIp);
        }
    }

    private void consume(String key) {
        String todayKey = key + ":" + LocalDate.now();
        Long count = stringRedisTemplate.opsForValue().increment(todayKey);
        if (count != null && count == 1L) {
            stringRedisTemplate.expireAt(todayKey, Date.from(LocalDate.now().plusDays(1)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant()));
        }
        if (count != null && count > dailyLimit) {
            throw new ClientException("今日匿名试用次数已用完，注册登录后可继续提问");
        }
    }

    /**
     * 只读查询当前剩余试用次数（U11-⑤ 配额状态可见；不消耗额度）。
     *
     * @return null=不受限（非游客或未启用限额）；否则为今日剩余次数（双键取已用较大值）
     */
    public Integer remainingQuota(LoginUser user, String clientIp) {
        if (user == null || !ROLE_GUEST.equals(user.getRole()) || dailyLimit <= 0) {
            return null;
        }
        long usedByUser = readUsedCount(KEY_PREFIX_USER + user.getUserId());
        long usedByIp = (clientIp == null || clientIp.isBlank()) ? 0 : readUsedCount(KEY_PREFIX_IP + clientIp);
        long used = Math.max(usedByUser, usedByIp);
        return (int) Math.max(0, dailyLimit - used);
    }

    /**
     * 游客身份当日限额终值（U11-⑤ 状态展示用；未启用限额时为 null）
     */
    public Integer currentDailyLimit(LoginUser user) {
        if (user == null || !ROLE_GUEST.equals(user.getRole()) || dailyLimit <= 0) {
            return null;
        }
        return dailyLimit;
    }

    private long readUsedCount(String key) {
        String value = stringRedisTemplate.opsForValue().get(key + ":" + LocalDate.now());
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
