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

package com.nageoffer.ai.ragent.agent.permission;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentToolPermissionDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentToolPermissionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * 权限矩阵全量快照缓存（#487）：volatile 不可变快照 + TTL 60s 到期懒刷新（所选策略，PR 记录）。
 * 表体量=3 role×个位数工具＜百行，全量加载后每 run 编译只做内存查找+builder 组装，零 IO。
 * 管理端写库后最迟 60s 生效（不做写时失效——TTL/失效二选一，选 TTL：免跨实例总线、自愈）。
 * 刷新失败保旧快照（fail-soft）：权限配置库抖动不阻断聊天主链。
 * flag 关态下检查点不触碰本缓存（连首次加载都不发生，关态零 DB 面）。
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
public class AgentToolPermissionSnapshotCache {

    /**
     * TTL：60 秒（票面建议值）
     */
    static final long TTL_MILLIS = 60_000L;

    private final AgentToolPermissionMapper mapper;
    private final Clock clock;

    /**
     * 不可变快照+装载时刻；null=从未加载
     */
    private volatile Snapshot snapshot;

    private record Snapshot(List<AgentToolPermissionEntry> entries, long loadedAtMillis) {
    }

    public AgentToolPermissionSnapshotCache(AgentToolPermissionMapper mapper) {
        this(mapper, Clock.systemUTC());
    }

    AgentToolPermissionSnapshotCache(AgentToolPermissionMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 当前生效快照：TTL 内直接返回同一不可变 List 实例（编译侧只读不复制）；
     * 到期或未加载时单飞刷新，刷新失败回退旧快照
     */
    public List<AgentToolPermissionEntry> snapshot() {
        Snapshot current = snapshot;
        if (current != null && !expired(current)) {
            return current.entries();
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && !expired(current)) {
                return current.entries();
            }
            Snapshot fresh = load();
            if (fresh != null) {
                snapshot = fresh;
                current = fresh;
            }
            // 刷新失败：保旧（可能仍为 null=从未成功加载→空快照，等价于无规则）
            return current == null ? List.of() : current.entries();
        }
    }

    private boolean expired(Snapshot current) {
        return clock.millis() - current.loadedAtMillis() >= TTL_MILLIS;
    }

    /**
     * 全量读 enabled=1 的行；任何异常只 WARN 不上抛。
     * Java 侧再滤一遍 enabled：SQL 条件与内存过滤双保险，脏行绝不进编译快照
     */
    private Snapshot load() {
        try {
            List<AgentToolPermissionEntry> entries = mapper.selectList(
                            Wrappers.lambdaQuery(AgentToolPermissionDO.class)
                                    .eq(AgentToolPermissionDO::getEnabled, 1))
                    .stream()
                    .filter(row -> row.getEnabled() != null && row.getEnabled() == 1)
                    .map(row -> new AgentToolPermissionEntry(row.getRole(), row.getToolId(), row.getDecision()))
                    .toList();
            return new Snapshot(entries, clock.millis());
        } catch (Exception e) {
            log.warn("工具权限矩阵快照刷新失败, 沿用旧快照", e);
            return null;
        }
    }
}
