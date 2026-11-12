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

import com.nageoffer.ai.ragent.agent.dao.entity.AgentToolPermissionDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentToolPermissionMapper;
import io.agentscope.core.permission.PermissionContextState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 快照缓存测试（#487 验收 8）：所选策略=TTL 60s 到期懒刷新（volatile 不可变快照）。
 * 断言：TTL 内零 DB 调用；到期后首次访问刷新且新规则可见；编译过程零 DB 调用；刷新失败保旧快照。
 */
class AgentToolPermissionCacheTest {

    private static final long BASE_MILLIS = 1_700_000_000_000L;

    private AgentToolPermissionMapper mapper;
    private final AtomicInteger loadCount = new AtomicInteger();
    private MutableClock clock;

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static AgentToolPermissionDO row(String role, String toolId, String decision, int enabled) {
        return AgentToolPermissionDO.builder()
                .id("id-" + role + "-" + toolId).role(role).toolId(toolId)
                .decision(decision).enabled(enabled).build();
    }

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(AgentToolPermissionMapper.class);
        clock = new MutableClock(Instant.ofEpochMilli(BASE_MILLIS));
        loadCount.set(0);
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            loadCount.incrementAndGet();
            return List.of(row("user", "search_news", "DENY", 1));
        });
    }

    @Test
    void 首次访问加载_TTL内零DB调用_到期后首次访问刷新() {
        AgentToolPermissionSnapshotCache cache = new AgentToolPermissionSnapshotCache(mapper, clock);

        List<AgentToolPermissionEntry> first = cache.snapshot();
        assertThat(first).hasSize(1);
        assertThat(loadCount.get()).isEqualTo(1);

        // TTL 内重复访问：零 DB 调用（每 run 编译只做内存查找的契约面）
        for (int i = 0; i < 5; i++) {
            assertThat(cache.snapshot()).isSameAs(first);
        }
        assertThat(loadCount.get()).isEqualTo(1);

        // 到期（TTL=60s）后首次访问触发刷新，管理员新写入的行可见
        // doAnswer 式重打桩：when() 式会在打桩时自身触发旧 answer 多计一次
        Mockito.doAnswer(invocation -> {
                    loadCount.incrementAndGet();
                    return List.of(row("user", "search_news", "DENY", 1),
                            row("guest", "mcp:*", "DENY", 1));
                }).when(mapper).selectList(any());
        clock.advance(Duration.ofSeconds(61));
        List<AgentToolPermissionEntry> refreshed = cache.snapshot();
        assertThat(loadCount.get()).isEqualTo(2);
        assertThat(refreshed).hasSize(2);
    }

    @Test
    void 停用行不进快照() {
        when(mapper.selectList(any())).thenAnswer(invocation -> {
            loadCount.incrementAndGet();
            return List.of(row("user", "search_news", "DENY", 1),
                    row("user", "search_exam_papers", "ALLOW", 0));
        });
        AgentToolPermissionSnapshotCache cache = new AgentToolPermissionSnapshotCache(mapper, clock);
        assertThat(cache.snapshot())
                .extracting(AgentToolPermissionEntry::toolId)
                .containsExactly("search_news");
    }

    @Test
    void 编译过程零DB调用_mock计数() {
        AgentToolPermissionSnapshotCache cache = new AgentToolPermissionSnapshotCache(mapper, clock);
        List<AgentToolPermissionEntry> snapshot = cache.snapshot();
        int loadsAfterWarm = loadCount.get();

        PermissionContextState state = AgentToolPermissionCompiler.compile("user", snapshot,
                List.of("search_news", "search_exam_papers"));
        assertThat(state.getDenyRules()).containsOnlyKeys("search_news");
        // 编译零 IO：快照在缓存层已固化，编译期间 mapper 零调用
        assertThat(loadCount.get()).isEqualTo(loadsAfterWarm);
    }

    @Test
    void 刷新失败保旧快照_failSoft() {
        AgentToolPermissionSnapshotCache cache = new AgentToolPermissionSnapshotCache(mapper, clock);
        List<AgentToolPermissionEntry> first = cache.snapshot();

        when(mapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        clock.advance(Duration.ofSeconds(61));
        assertThat(cache.snapshot()).isEqualTo(first);
        // 旧快照照常可编译，主链不受影响
        assertThat(AgentToolPermissionCompiler.compile("user", cache.snapshot(), List.of("search_news"))
                .getDenyRules()).containsKey("search_news");
    }
}
