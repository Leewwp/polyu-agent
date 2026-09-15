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
import cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * sa-token 1.46 + Redisson 连接层兼容 DAO（批次三 #29 升级回归，2026-09-10 实弹冒烟定位）
 *
 * <p>sa-token 1.46.0 把 {@code update(key, value)} 的实现从「读剩余 TTL → 携正 TTL 重写」
 * 改为 {@code SET key value KEEPTTL XX}（{@code SaTokenDaoForRedisTemplate#setStringAndKeepTTL}）。
 * 本项目的 Redis 接入是 Redisson（redisson-spring-data-41），其连接适配器
 * {@code RedissonConnection.set(byte[], byte[], Expiration, SetOption)} 不识别
 * {@code Expiration.keepTtl()} 的哨兵值（-2s），直接翻译为 {@code PX -2000 XX}，
 * Redis 拒绝负过期时间 → 登录（StpUtil.login → SaSession.addTerminal → session.update）
 * 稳定抛 B000001，认证链全断。Redisson 4.6.1/4.7.0 同版该方法均未处理 keepTtl
 * （上游源码核对过），sa-token 亦无补丁版，故在本层恢复 1.45 的显式 TTL 语义。
 *
 * <p>本类是纯薄层：仅覆写一个方法、语义逐行取自 sa-token 1.45.0 官方实现
 * （补上 1.46 新增的 key 前缀包装），上游任一方修复后可整体删除回归官方 DAO。
 * {@code @Primary} 使 Spring 在两个 SaTokenDao 候选中优先注入本类
 * （官方 DAO 由 AutoConfiguration.imports 无条件注册，无法用条件注解退让）。
 */
@Component
@Primary
public class RedissonCompatSaTokenDao extends SaTokenDaoForRedisTemplate {

    @Override
    public void update(String key, String value) {
        String finalKey = wrapKey(key);
        long expireMs = stringRedisTemplate.getExpire(finalKey, TimeUnit.MILLISECONDS);
        // -2 = 无此键：无事可做（1.45 同语义，update 只作用于已存在的键）
        if (expireMs == SaTokenDao.NOT_VALUE_EXPIRE) {
            return;
        }
        // -1 = 永不过期：重写且不设 TTL
        if (expireMs == SaTokenDao.NEVER_EXPIRE) {
            stringRedisTemplate.opsForValue().set(finalKey, value);
            return;
        }
        stringRedisTemplate.opsForValue().set(finalKey, value, expireMs, TimeUnit.MILLISECONDS);
    }
}
