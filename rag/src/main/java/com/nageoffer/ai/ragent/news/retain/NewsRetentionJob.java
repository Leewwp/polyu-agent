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

package com.nageoffer.ai.ragent.news.retain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * 资讯保留期清理任务（独立于通用 DataRetentionJob 面）
 *
 * <p>清理口径=发布时间早于 rag.news.retention-days（默认 90 天）的条目；
 * t_news_item_topic 随外键 ON DELETE CASCADE 自动清理。照 DataRetentionJob
 * 范式：批上限防长事务（超量顺延下一轮 fixedDelay 扫描）；flag rag.news.enabled
 * 关（默认）时本组件不装配。
 *
 * <p>注意：PG DELETE 不支持 LIMIT，用 {@code DELETE WHERE id IN (SELECT … LIMIT n)}
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.news.enabled", havingValue = "true")
public class NewsRetentionJob {

    /**
     * 单轮删除上限（行级小表，500 已远超日常量级；剩余行下一轮消化）
     */
    static final int DELETE_BATCH = 500;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final int retentionDays;

    @Autowired
    public NewsRetentionJob(JdbcTemplate jdbcTemplate,
            @Value("${rag.news.retention-days:90}") int retentionDays) {
        this(jdbcTemplate, Clock.systemDefaultZone(), retentionDays);
    }

    NewsRetentionJob(JdbcTemplate jdbcTemplate, Clock clock, int retentionDays) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.retentionDays = retentionDays;
    }

    /**
     * 每小时巡检（形状沿 ragent.retention 外置先例，参数走 rag.news.retention-*）
     */
    @Scheduled(fixedDelayString = "${rag.news.retention-scan-delay-ms:3600000}",
            initialDelayString = "${rag.news.retention-initial-delay-ms:120000}")
    public void purgeExpiredItems() {
        Timestamp cutoff = Timestamp.from(clock.instant().minus(retentionDays, ChronoUnit.DAYS));
        int rows = jdbcTemplate.update(
                "DELETE FROM t_news_item WHERE id IN "
                        + "(SELECT id FROM t_news_item WHERE publish_time < ? LIMIT " + DELETE_BATCH + ")",
                cutoff);
        if (rows > 0) {
            log.info("[news] 保留期清理完成：删除 {} 条早于 {} 的条目", rows, cutoff);
        } else {
            log.debug("[news] 保留期清理：无到期条目（cutoff={}）", cutoff);
        }
    }
}
