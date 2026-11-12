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

package com.nageoffer.ai.ragent.agent.audit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

/**
 * t_agent_tool_call 保留期清理（#486，90 天）：参照 DataRetentionJob 的
 * JdbcTemplate 直 SQL + fixedDelay 模式，但独立成类——单类失败不影响其他清理类。
 * 清理不随 agent.audit.enabled 关停：关停前的残留行仍按期清。
 */
@Slf4j
@Component
public class AgentToolCallRetentionJob {

    /**
     * 保留天数（票面决议 90 天）
     */
    static final int RETENTION_DAYS = 90;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AgentToolCallRetentionJob(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemDefaultZone());
    }

    AgentToolCallRetentionJob(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * 保留期巡检：fixedDelay 间隔与首扫延迟外置于 agent.audit.retention-*（对齐 ragent.retention.* 惯例）
     */
    @Scheduled(fixedDelayString = "${agent.audit.retention-scan-delay-ms:3600000}",
            initialDelayString = "${agent.audit.retention-initial-delay-ms:120000}")
    public void sweep() {
        try {
            Timestamp cutoff = Timestamp.from(clock.instant().minus(RETENTION_DAYS, ChronoUnit.DAYS));
            int rows = jdbcTemplate.update("DELETE FROM t_agent_tool_call WHERE create_time < ?", cutoff);
            if (rows > 0) {
                log.info("[agent-audit-retention] 工具调用审计到期清理完成：{} 行（cutoff={}）", rows, cutoff);
            }
        } catch (Exception ex) {
            // 异常不出本类：调度线程与其余清理类不受影响，剩余行下一轮扫描自然消化
            log.error("[agent-audit-retention] 工具调用审计清理失败，下一轮重试", ex);
        }
    }
}
