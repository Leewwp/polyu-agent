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

package com.nageoffer.ai.ragent.user.retention;

import com.nageoffer.ai.ragent.user.service.impl.AccountDeletionCascade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 数据保留期清理任务（覆盖八类数据）。
 *
 * <p>八类落位：①原始访问事件 30 天、②按日聚合 13 个月——两表属 analytics 票未建，建表后在此接线；
 * ③登录用户对话=账号存续期（注销随删已由账号硬删级联承担），本任务补 30 天冷静期到期硬删；
 * ④guest 对话 30 天+guest 账号清理——铸号起 30 天整号级联（复用 {@link AccountDeletionCascade#purge}）；
 * ⑤分享快照 90 天——expire_time 到期主动删行（原状仅读时惰性判过期，行不清理）；
 * ⑥guest token 180 天——现状全局会话 30 天自动失效严于决议上限，Redis 键自带 TTL，无清理动作；
 * ⑦反馈 400 天（comment 按 PII 对待）；⑧注销邮箱墓碑 180 天（expire_time 列自带到期时刻）。
 * 配套口径：t_biz_change_log 的 IP/UA 维持全行保留——该表承载审计回查，
 * 若未来数据量增长再评估 30 天前行 IP/UA 置 NULL（保行去标识），不删行。
 *
 * <p>跨模块表经 JdbcTemplate 直 SQL 触达（system 被 rag 依赖不能反向引其 mapper，同
 * AccountDeletionCascade 的落点理由）。单实例部署下 @Scheduled 天然不并发；各类清理相互独立、
 * 单类失败仅记日志不阻断其余类，账号类每批设上限防长事务风暴，剩余行下一轮扫描自然消化。
 */
@Slf4j
@Component
public class DataRetentionJob {

    /**
     * 单轮账号硬删上限：级联按用户逐个开事务，超量顺延下一轮（fixedDelay 扫描）
     */
    static final int MAX_ACCOUNT_BATCH = 500;

    private final JdbcTemplate jdbcTemplate;
    private final AccountDeletionCascade deletionCascade;
    private final DataRetentionProperties properties;
    private final Clock clock;

    @Autowired
    public DataRetentionJob(JdbcTemplate jdbcTemplate, AccountDeletionCascade deletionCascade,
            DataRetentionProperties properties) {
        this(jdbcTemplate, deletionCascade, properties, Clock.systemDefaultZone());
    }

    DataRetentionJob(JdbcTemplate jdbcTemplate, AccountDeletionCascade deletionCascade,
            DataRetentionProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.deletionCascade = deletionCascade;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 保留期巡检：fixedDelay 间隔与首扫延迟外置于 ragent.retention.scan-delay-ms / initial-delay-ms
     */
    @Scheduled(fixedDelayString = "${ragent.retention.scan-delay-ms:3600000}",
            initialDelayString = "${ragent.retention.initial-delay-ms:120000}")
    public void sweep() {
        if (!properties.isEnabled()) {
            log.debug("[retention] 清理总开关关闭，本轮跳过");
            return;
        }
        runSafely("软删到期硬删", this::purgeExpiredSoftDeletedAccounts);
        runSafely("guest 到期清理", this::purgeExpiredGuestAccounts);
        runSafely("分享快照到期删除", this::deleteExpiredShares);
        runSafely("反馈到期删除", this::deleteExpiredFeedback);
        runSafely("邮箱墓碑到期删除", this::deleteExpiredTombstones);
    }

    /**
     * 注销冷静期（默认 30 天）到期的软删账号逐个硬删级联；恢复窗口判定见 AccountLifecycleServiceImpl
     */
    private void purgeExpiredSoftDeletedAccounts() {
        Timestamp cutoff = cutoff(properties.getPurgeGraceDays());
        List<SoftDeletedAccount> expired = jdbcTemplate.query(
                "SELECT id, email FROM t_user WHERE deleted = 1 AND delete_time IS NOT NULL "
                        + "AND delete_time < ? LIMIT " + MAX_ACCOUNT_BATCH,
                (rs, rowNum) -> new SoftDeletedAccount(rs.getString("id"), rs.getString("email")), cutoff);
        for (SoftDeletedAccount account : expired) {
            deletionCascade.purge(account.id(), account.email());
        }
        if (!expired.isEmpty()) {
            log.info("[retention] 软删到期硬删完成：{} 个账号（cutoff={}）", expired.size(), cutoff);
        }
    }

    /**
     * 铸号超保留期（默认 30 天）的 guest 整号级联：对话随删、反馈匿名化、账号行删除（无邮箱，墓碑跳过）
     */
    private void purgeExpiredGuestAccounts() {
        Timestamp cutoff = cutoff(properties.getGuestRetentionDays());
        List<String> guestIds = jdbcTemplate.query(
                "SELECT id FROM t_user WHERE role = 'guest' AND deleted = 0 "
                        + "AND create_time < ? LIMIT " + MAX_ACCOUNT_BATCH,
                (rs, rowNum) -> rs.getString("id"), cutoff);
        for (String guestId : guestIds) {
            deletionCascade.purge(guestId, null);
        }
        if (!guestIds.isEmpty()) {
            log.info("[retention] guest 到期清理完成：{} 个账号（cutoff={}）", guestIds.size(), cutoff);
        }
    }

    /**
     * 分享快照 90 天主动清理：expire_time 到期即删行（含 REVOKED 行，撤销只管公开面失效、行保留随快照期）
     */
    private void deleteExpiredShares() {
        int rows = jdbcTemplate.update(
                "DELETE FROM t_answer_share WHERE expire_time IS NOT NULL AND expire_time < ?", now());
        logDeletedRows("分享快照", rows);
    }

    /**
     * 反馈记录 400 天清理：整行删除（comment 自由文本按 PII 对待）
     */
    private void deleteExpiredFeedback() {
        int rows = jdbcTemplate.update("DELETE FROM t_message_feedback WHERE create_time < ?",
                cutoff(properties.getFeedbackRetentionDays()));
        logDeletedRows("反馈记录", rows);
    }

    /**
     * 注销邮箱墓碑 180 天清理：以写入时固化的 expire_time 为准（含 U2 注销 upsert 时刻起算的行）
     */
    private void deleteExpiredTombstones() {
        int rows = jdbcTemplate.update("DELETE FROM t_user_email_tombstone WHERE expire_time < ?", now());
        logDeletedRows("邮箱墓碑", rows);
    }

    private void runSafely(String category, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Exception ex) {
            log.error("[retention] {} 失败，其余类别继续", category, ex);
        }
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private Timestamp cutoff(int retentionDays) {
        return Timestamp.from(clock.instant().minus(retentionDays, ChronoUnit.DAYS));
    }

    private void logDeletedRows(String category, int rows) {
        if (rows > 0) {
            log.info("[retention] {} 到期清理完成：{} 行", category, rows);
        }
    }

    /**
     * 软删到期账号载荷（email 可能为 NULL——注册用户注销均带邮箱，防御性透传给级联）
     */
    record SoftDeletedAccount(String id, String email) {
    }
}
