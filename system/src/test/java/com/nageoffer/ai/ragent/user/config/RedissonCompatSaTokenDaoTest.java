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

package com.nageoffer.ai.ragent.user.config;

import cn.dev33.satoken.dao.SaTokenDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedissonCompatSaTokenDao 三态语义锁定：键不存在 no-op / 永不过期重写无 TTL /
 * 携剩余 TTL 重写——对应 sa-token 1.45 官方 update 行为，防止未来回归。
 */
@DisplayName("RedissonCompatSaTokenDao update 三态")
@ExtendWith(MockitoExtension.class)
class RedissonCompatSaTokenDaoTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RedissonCompatSaTokenDao dao;

    @Test
    @DisplayName("键不存在（-2）：no-op，不产生任何写命令")
    void noOpWhenKeyMissing() {
        when(stringRedisTemplate.getExpire("satoken:login:session:1", TimeUnit.MILLISECONDS))
                .thenReturn(SaTokenDao.NOT_VALUE_EXPIRE);

        dao.update("satoken:login:session:1", "{\"data\":1}");

        verify(stringRedisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("永不过期（-1）：重写且不带 TTL")
    void rewriteWithoutTtlWhenNeverExpire() {
        when(stringRedisTemplate.getExpire("satoken:login:session:1", TimeUnit.MILLISECONDS))
                .thenReturn(SaTokenDao.NEVER_EXPIRE);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        dao.update("satoken:login:session:1", "{\"data\":1}");

        verify(valueOperations).set("satoken:login:session:1", "{\"data\":1}");
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("有剩余 TTL：以剩余毫秒重写（保留原过期时刻）")
    void rewriteWithRemainingTtl() {
        when(stringRedisTemplate.getExpire("satoken:login:session:1", TimeUnit.MILLISECONDS))
                .thenReturn(30000L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        dao.update("satoken:login:session:1", "{\"data\":1}");

        verify(valueOperations).set(eq("satoken:login:session:1"), eq("{\"data\":1}"), eq(30000L), eq(TimeUnit.MILLISECONDS));
    }
}
