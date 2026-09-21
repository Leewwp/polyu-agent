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

package com.nageoffer.ai.ragent.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;

/**
 * 账号硬删级联：自助注销到期清理与 admin 删号共用同一套动作。
 *
 * <p>跨模块表（rag 的会话/消息/反馈/分享、agent 的会话/消息/记忆）经 JdbcTemplate 直 SQL
 * 触达——system 模块被 rag 依赖，不能反向引用其 mapper；用户数据表非知识调度域，直 SQL 是
 * 最小侵入的落点。级联内容：
 * <ul>
 *   <li>对话两族（t_conversation/t_message + t_agent_*）随账号物理删除（注销随删）</li>
 *   <li>反馈匿名化断链（user_id 置哨兵 '0'，行保留 400 天由保留期任务清理）</li>
 *   <li>名下有效分享快照撤销（公开面立即失效；行由 90 天保留期任务清理）</li>
 *   <li>email 转 SHA-256 墓碑存 180 天（防重复注册滥用回查，幂等 upsert）</li>
 *   <li>用户行物理删除 + Sa-Token 会话全端下线</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountDeletionCascade {

    /**
     * 反馈匿名化哨兵：t_message_feedback.user_id NOT NULL，断链只能指向不存在的固定 ID
     */
    static final String ANONYMOUS_USER_ID = "0";

    /**
     * 注销邮箱墓碑保留天数
     */
    private static final int EMAIL_TOMBSTONE_DAYS = 180;

    private final JdbcTemplate jdbcTemplate;

    /**
     * 对指定用户执行硬删级联（事务内）。调用方负责前置校验（密码确认/权限/冷静期到期）
     */
    @Transactional(rollbackFor = Exception.class)
    public void purge(String userId, String email) {
        // 对话两族随账号删除：workflow 族（t_conversation/t_message）与 agent 族（engine=agent 现行写入面）
        jdbcTemplate.update("DELETE FROM t_conversation WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_message WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_agent_conversation WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_agent_message WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_agent_context_compaction WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_agent_memory WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_agent_memory_extraction WHERE user_id = ?", userId);
        // #104 级联补洞：注销前漏清的三张 user_id 关联表（残行即永久孤儿）
        jdbcTemplate.update("DELETE FROM t_agent_memory_control WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_agent_state WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM t_conversation_summary WHERE user_id = ?", userId);
        // 反馈匿名化断链：行保留（400 天保留期），评价统计不受账号消亡影响
        jdbcTemplate.update(
                "UPDATE t_message_feedback SET user_id = '" + ANONYMOUS_USER_ID
                        + "', update_time = CURRENT_TIMESTAMP WHERE user_id = ?",
                userId);
        // 名下有效分享撤销：不删行（快照保留期 90 天），公开访问立即失效。
        // #104 级联补洞：agent 会话分享同款撤销——此前漏撤，注销硬删后 ACTIVE 链接继续公开可访问（隐私缺陷）
        jdbcTemplate.update(
                "UPDATE t_answer_share SET status = 'REVOKED', revoked_time = CURRENT_TIMESTAMP, "
                        + "update_time = CURRENT_TIMESTAMP WHERE owner_user_id = ? AND status = 'ACTIVE'",
                userId);
        jdbcTemplate.update(
                "UPDATE t_agent_conversation_share SET status = 'REVOKED', revoked_time = CURRENT_TIMESTAMP, "
                        + "update_time = CURRENT_TIMESTAMP WHERE owner_user_id = ? AND status = 'ACTIVE'",
                userId);
        // 邮箱墓碑（180 天回查；同邮箱再注销刷新过期时间）
        if (email != null && !email.isBlank()) {
            jdbcTemplate.update(
                    "INSERT INTO t_user_email_tombstone (id, email_hash, user_id, expire_time) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (email_hash) DO UPDATE SET expire_time = EXCLUDED.expire_time",
                    IdUtil.getSnowflakeNextIdStr(), sha256(email), userId,
                    Date.from(Instant.now().plus(EMAIL_TOMBSTONE_DAYS, ChronoUnit.DAYS)));
        }
        // 用户行物理删除
        jdbcTemplate.update("DELETE FROM t_user WHERE id = ?", userId);
        // 全端会话下线（token 失效）
        try {
            StpUtil.logout(userId);
        } catch (Exception ex) {
            // 会话清理失败不阻断数据级联（无会话的调用方——如离线清理任务——会走到这里）
            log.warn("[account-purge] Sa-Token 会话下线跳过：userId={} 原因={}", userId, ex.getMessage());
        }
        log.info("[account-purge] 用户硬删级联完成：userId={} emailPresent={} ", userId, email != null);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
