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

import java.util.List;

/**
 * 权限矩阵编译器（#487）：纯函数，输入=快照行集+当前挂载 MCP 工具集，输出 PermissionContextState。
 * 零 IO、零 Spring 依赖，编译发生在每 run 检查点（请求线程）。
 *
 * <p>通配策略=编译期展开：agentscope PermissionEngine.rulesFor 按 toolName 精确 Map.get
 * （2.0.2 字节码实证），匹配期通配不可行；tool_id="mcp:*" 是命名空间哨兵（挂载 id 为
 * search_exam_papers 等裸名，无 mcp: 字面前缀），展开为本 run 目录快照实际挂载的全部 MCP 工具，
 * 其余取值一律按精确工具名。同一 (role, tool_id) 的唯一索引保证规则不叠加歧义。
 *
 * <p>PermissionRule.ruleContent 留空=对该工具全入参生效（ruleMatches 字节码实证：空 content 恒真）；
 * mode 显式 DEFAULT——isTrivial 的字节码判据要求 mode==DEFAULT 且三 map 空才 trivial。
 */
public final class AgentToolPermissionCompiler {

    /**
     * 规则来源标记：区分于框架内置/其他来源的规则（票面固定值）
     */
    public static final String RULE_SOURCE = "role-matrix";

    /**
     * 通配哨兵：=当前挂载的全部 MCP 工具
     */
    public static final String MCP_WILDCARD = "mcp:*";

    private AgentToolPermissionCompiler() {
    }

    /**
     * @param role              admin/user/guest；未知或空 role → trivial 态（等价于该角色无规则）
     * @param entries           全量快照（含其他角色行，本方法只挑本角色）
     * @param mountedMcpToolIds 本 run 目录快照挂载的 MCP 工具 id（通配展开的目标集）
     */
    public static PermissionContextState compile(String role, List<AgentToolPermissionEntry> entries,
                                                 List<String> mountedMcpToolIds) {
        PermissionContextState.Builder builder = PermissionContextState.builder()
                .mode(PermissionMode.DEFAULT);
        if (role == null || role.isBlank() || entries == null || entries.isEmpty()) {
            return builder.build();
        }
        for (AgentToolPermissionEntry entry : entries) {
            if (!role.equals(entry.role())) {
                continue;
            }
            for (String toolName : resolveToolNames(entry.toolId(), mountedMcpToolIds)) {
                addRule(builder, toolName, entry.decision());
            }
        }
        return builder.build();
    }

    /**
     * tool_id → 实际生效的工具名集：mcp:* 展开为挂载集，其余精确名透传（挂载与否不由编译器裁决）
     */
    private static List<String> resolveToolNames(String toolId, List<String> mountedMcpToolIds) {
        if (MCP_WILDCARD.equals(toolId)) {
            return mountedMcpToolIds == null ? List.of() : mountedMcpToolIds;
        }
        return List.of(toolId);
    }

    private static void addRule(PermissionContextState.Builder builder, String toolName, String decision) {
        PermissionBehavior behavior = behaviorOf(decision);
        PermissionRule rule = new PermissionRule(toolName, "", behavior, RULE_SOURCE);
        switch (behavior) {
            case DENY -> builder.addDenyRule(toolName, rule);
            case ASK -> builder.addAskRule(toolName, rule);
            case ALLOW -> builder.addAllowRule(toolName, rule);
            default -> builder.addDenyRule(toolName, rule);
        }
    }

    /**
     * 未知 decision 按 DENY 最严落法（DB CHECK 已挡脏值，此处是防御第二层，绝不静默放行）
     */
    private static PermissionBehavior behaviorOf(String decision) {
        return switch (decision == null ? "" : decision) {
            case "ALLOW" -> PermissionBehavior.ALLOW;
            case "ASK" -> PermissionBehavior.ASK;
            default -> PermissionBehavior.DENY;
        };
    }
}
