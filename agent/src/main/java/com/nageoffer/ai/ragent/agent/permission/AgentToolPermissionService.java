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

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.tool.AgentToolCatalog.ResolvedCatalog;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.PermissionContextState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 每 run 会话级权限装载检查点（#487）：startRun/startConfirmRun 拿到 activeAgent 后、
 * launchStream 之前调用。flag 关态直接返回——连空编译都不做（票面关态契约），
 * AgentState.permissionContext 保持 null/trivial，引擎链路与现状逐位相同。
 *
 * <p>每 run 一次编译：输入=缓存快照（内存查找）+本 run 目录快照的挂载集，
 * 输出经 agent.replacePermissionContext(userId, conversationId, state) 装进会话槽
 * （框架内部=set+刷 engineCache+saveAgentState 三步，agentscope 2.0.2 公开 API）。
 * 不占 per-tool 热路径，不碰答案生成链路。
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentToolPermissionService {

    private final AgentProperties properties;
    private final AgentToolPermissionSnapshotCache snapshotCache;

    /**
     * @param role 请求线程 UserContext.getRole()（admin/user/guest；空值=无规则编译为 trivial 态）
     */
    public void installForRun(ReActAgent agent, ResolvedCatalog catalog,
                              String userId, String conversationId, String role) {
        if (!properties.getToolPermission().isEnabled()) {
            return;
        }
        PermissionContextState state = AgentToolPermissionCompiler.compile(
                role, snapshotCache.snapshot(), catalog.mcpToolIds());
        agent.replacePermissionContext(userId, conversationId, state);
    }
}
