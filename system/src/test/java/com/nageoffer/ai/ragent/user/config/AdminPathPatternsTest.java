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

import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 管理面路径清单的**匹配力**测试。
 *
 * <p>断言的不是「清单里有这一项」，而是「这一项真的能匹配上那些真实端点」——
 * 两者不等价：模式写错（少一层、写成 `/ingestion/*` 只匹配单层）时清单看着是对的，
 * 拦截器却不生效。用与 Spring MVC 同一套 {@link PathPatternParser} 求值，
 * 避免靠肉眼比对字符串。
 *
 * <p>此处只覆盖已确认的管理面端点；全量 controller 的覆盖面审计见安全审查报告。
 */
class AdminPathPatternsTest {

    private final PathPatternParser parser = new PathPatternParser();

    private boolean coveredByAdminPatterns(String requestPath) {
        PathContainer container = PathContainer.parsePath(requestPath);
        return Arrays.stream(SaTokenConfig.ADMIN_PATH_PATTERNS)
                .map(parser::parse)
                .anyMatch(pattern -> pattern.matches(container));
    }

    @Test
    void 采集管道与任务的真实端点均落在管理面清单内() {
        List<String> ingestionEndpoints = List.of(
                "/ingestion/pipelines",
                "/ingestion/pipelines/abc123",
                "/ingestion/tasks",
                "/ingestion/tasks/abc123",
                "/ingestion/tasks/abc123/nodes",
                "/ingestion/tasks/upload");
        for (String endpoint : ingestionEndpoints) {
            assertTrue(coveredByAdminPatterns(endpoint),
                    "管理面清单未覆盖端点: " + endpoint);
        }
    }

    @Test
    void 其余管理面端点仍被覆盖_防模式改动误伤() {
        List<String> adminEndpoints = List.of(
                "/knowledge-base/kb1/docs/upload",
                "/agents/tree",
                "/admin/users",
                "/rag/settings",
                "/rag/traces/run1",
                "/users/42");
        for (String endpoint : adminEndpoints) {
            assertTrue(coveredByAdminPatterns(endpoint),
                    "管理面清单未覆盖端点: " + endpoint);
        }
    }

    @Test
    void 用户侧与公开面不应被管理面清单命中() {
        // 判别力断言：清单若被写成过宽（如 /**），这里会失败
        List<String> userFacingEndpoints = List.of(
                "/rag/v3/chat",
                "/agent/v1/chat",
                "/public/news/feed",
                "/public/share/token123",
                "/auth/login");
        for (String endpoint : userFacingEndpoints) {
            assertFalse(coveredByAdminPatterns(endpoint),
                    "用户侧端点被误纳入管理面: " + endpoint);
        }
    }
}
