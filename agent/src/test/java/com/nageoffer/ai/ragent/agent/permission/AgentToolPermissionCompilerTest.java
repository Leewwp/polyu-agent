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

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编译器单测（#487 验收 2，零 DB）：纯函数 快照+挂载集 → PermissionContextState。
 * 期望值全部来自票面与 agentscope 2.0.2 字节码实证（规则 source="role-matrix"、
 * rulesFor 精确匹配→通配必须编译期展开），不由实现反推。
 */
class AgentToolPermissionCompilerTest {

    /**
     * 测试自持的快照行构造器：与 DO 解耦（缓存快照口径）
     */
    record ToolRule(String role, String toolId, String decision) {
    }

    private static List<AgentToolPermissionEntry> entries(ToolRule... rules) {
        return java.util.Arrays.stream(rules)
                .map(rule -> new AgentToolPermissionEntry(rule.role(), rule.toolId(), rule.decision()))
                .toList();
    }

    private static final List<String> MOUNTED = List.of("search_exam_papers", "search_news", "query_key_dates");

    @ParameterizedTest
    @ValueSource(strings = {"admin", "user", "guest"})
    void 三角色各自只编译本角色的规则(String role) {
        PermissionContextState state = AgentToolPermissionCompiler.compile(role,
                entries(new ToolRule("admin", "search_news", "ALLOW"),
                        new ToolRule("user", "search_news", "DENY"),
                        new ToolRule("guest", "mcp:*", "DENY")),
                MOUNTED);
        if ("admin".equals(role)) {
            assertThat(state.getAllowRules()).containsKey("search_news");
            assertThat(state.getDenyRules()).isEmpty();
        } else if ("user".equals(role)) {
            assertThat(state.getDenyRules()).containsKey("search_news");
            assertThat(state.getAllowRules()).isEmpty();
        } else {
            // guest 的 mcp:* 展开为全部挂载工具
            assertThat(state.getDenyRules()).containsOnlyKeys("search_exam_papers", "search_news", "query_key_dates");
        }
    }

    @Test
    void 通配mcp星展开为当前挂载集_未挂载工具不产生规则() {
        PermissionContextState state = AgentToolPermissionCompiler.compile("guest",
                entries(new ToolRule("guest", "mcp:*", "DENY")),
                List.of("search_exam_papers", "query_key_dates"));
        assertThat(state.getDenyRules()).containsOnlyKeys("search_exam_papers", "query_key_dates");
    }

    @Test
    void 挂载集为空时通配不产生规则_精确名照常产生() {
        PermissionContextState state = AgentToolPermissionCompiler.compile("user",
                entries(new ToolRule("user", "mcp:*", "DENY"),
                        new ToolRule("user", "search_news", "ASK")),
                List.of());
        assertThat(state.getDenyRules()).isEmpty();
        assertThat(state.getAskRules()).containsOnlyKeys("search_news");
    }

    @Test
    void 精确工具名不参与通配语义_字面mcp前缀工具名也是精确匹配() {
        // 挂载集里的工具若真叫 "mcp:foo"（当前命名无此前缀，防御性断言），精确规则原样生效
        PermissionContextState state = AgentToolPermissionCompiler.compile("user",
                entries(new ToolRule("user", "mcp:foo", "ALLOW")),
                List.of("mcp:foo", "search_news"));
        assertThat(state.getAllowRules()).containsOnlyKeys("mcp:foo");
    }

    @Test
    void 三类decision各产出对应map的规则_source为role矩阵() {
        PermissionContextState state = AgentToolPermissionCompiler.compile("user",
                entries(new ToolRule("user", "search_news", "DENY"),
                        new ToolRule("user", "search_exam_papers", "ASK"),
                        new ToolRule("user", "query_key_dates", "ALLOW")),
                MOUNTED);
        assertThat(state.getDenyRules().get("search_news")).hasSize(1);
        assertThat(state.getAskRules().get("search_exam_papers")).hasSize(1);
        assertThat(state.getAllowRules().get("query_key_dates")).hasSize(1);

        PermissionRule deny = state.getDenyRules().get("search_news").get(0);
        // 期望值来自票面：PermissionRule(toolName, ruleContent, behavior, source="role-matrix")
        assertThat(deny.toolName()).isEqualTo("search_news");
        assertThat(deny.ruleContent()).isEmpty();
        assertThat(deny.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(deny.source()).isEqualTo("role-matrix");
        assertThat(state.getAskRules().get("search_exam_papers").get(0).behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(state.getAllowRules().get("query_key_dates").get(0).behavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void 空表或本角色无规则编译出trivial态_三map全空() {
        // 空表
        PermissionContextState empty = AgentToolPermissionCompiler.compile("user", List.of(), MOUNTED);
        assertThat(empty.isTrivial()).isTrue();
        assertThat(empty.getAllowRules()).isEmpty();
        assertThat(empty.getAskRules()).isEmpty();
        assertThat(empty.getDenyRules()).isEmpty();
        // 有其他角色规则但本角色无
        PermissionContextState otherRoleOnly = AgentToolPermissionCompiler.compile("guest",
                entries(new ToolRule("user", "search_news", "DENY")), MOUNTED);
        assertThat(otherRoleOnly.isTrivial()).isTrue();
    }

    @Test
    void 未知decision按deny最严落法_防御脏数据() {
        // DB CHECK 约束已挡脏值；编译器对未知值按最严处理，绝不静默放行
        PermissionContextState state = AgentToolPermissionCompiler.compile("user",
                entries(new ToolRule("user", "search_news", "OBEY")), MOUNTED);
        assertThat(state.getDenyRules()).containsOnlyKeys("search_news");
    }

    @Test
    void 编译态mode为DEFAULT_可被isTrivial口径接纳() {
        // agentscope isTrivial 字节码实证：mode==DEFAULT 且三 map 空才 trivial——mode 必须显式
        PermissionContextState state = AgentToolPermissionCompiler.compile("user", List.of(), MOUNTED);
        assertThat(state.getMode()).isEqualTo(PermissionMode.DEFAULT);
    }
}
