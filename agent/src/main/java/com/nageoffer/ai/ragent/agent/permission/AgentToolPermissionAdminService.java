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

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentToolPermissionDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentToolPermissionMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * 工具权限矩阵管理端 CRUD（#487）：数据源与判定分离——本服务只管表，
 * 判定=编译器（快照 TTL 60s 内收敛，管理端改动最迟 60s 生效，不做事后失效总线）。
 * role 按 DB 实证三值字符串校验（admin/user/guest），不强依赖 UserRole 枚举（枚举缺 GUEST 是既有缺口）。
 */
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentToolPermissionAdminService {

    private static final Set<String> ROLES = Set.of("admin", "user", "guest");
    private static final Set<String> DECISIONS = Set.of("ALLOW", "ASK", "DENY");

    private final AgentToolPermissionMapper mapper;

    /**
     * 全量列表（角色→工具 id 稳定排序）：矩阵＜百行，不分页
     */
    public List<AgentToolPermissionDO> list() {
        return mapper.selectList(Wrappers.lambdaQuery(AgentToolPermissionDO.class)).stream()
                .sorted(Comparator.comparing(AgentToolPermissionDO::getRole)
                        .thenComparing(AgentToolPermissionDO::getToolId))
                .toList();
    }

    /**
     * 新增或更新：id 空=新增；唯一索引 (role, tool_id) 冲突翻译成友好报错
     */
    public void save(AgentToolPermissionSaveRequest request) {
        validate(request);
        if (StrUtil.isBlank(request.getId())) {
            AgentToolPermissionDO row = AgentToolPermissionDO.builder()
                    .role(request.getRole())
                    .toolId(request.getToolId())
                    .decision(request.getDecision())
                    .enabled(request.getEnabled() == null ? 1 : request.getEnabled())
                    .description(request.getDescription())
                    .build();
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException | PersistenceException e) {
                if (isUniqueViolation(e)) {
                    throw new ClientException("该角色下此工具的规则已存在");
                }
                throw e;
            }
            return;
        }
        AgentToolPermissionDO existing = mapper.selectById(request.getId());
        if (existing == null) {
            throw new ClientException("规则不存在或已被删除");
        }
        existing.setRole(request.getRole());
        existing.setToolId(request.getToolId());
        existing.setDecision(request.getDecision());
        // 更新路径 enabled 未传沿用库内现值（新增路径才默认 1）：部分更新不得静默把停用行翻回启用
        existing.setEnabled(request.getEnabled() == null ? existing.getEnabled() : request.getEnabled());
        existing.setDescription(request.getDescription());
        try {
            mapper.updateById(existing);
        } catch (DuplicateKeyException | PersistenceException e) {
            if (isUniqueViolation(e)) {
                throw new ClientException("该角色下此工具的规则已存在");
            }
            throw e;
        }
    }

    /**
     * 语义校验（DB CHECK 是第二层防线）：role 三值、decision 三值、
     * tool_id 含 * 时只许通配哨兵 mcp:*（其余通配写法静默永不匹配，必须拦下）
     */
    private static void validate(AgentToolPermissionSaveRequest request) {
        if (request == null || StrUtil.isBlank(request.getRole()) || !ROLES.contains(request.getRole())) {
            throw new ClientException("role 仅允许 admin/user/guest");
        }
        if (StrUtil.isBlank(request.getDecision()) || !DECISIONS.contains(request.getDecision())) {
            throw new ClientException("decision 仅允许 ALLOW/ASK/DENY");
        }
        String toolId = StrUtil.trimToEmpty(request.getToolId());
        if (StrUtil.isBlank(toolId) || toolId.length() > 128) {
            throw new ClientException("tool_id 不能为空且不超过 128 字符");
        }
        if (toolId.contains("*") && !AgentToolPermissionCompiler.MCP_WILDCARD.equals(toolId)) {
            throw new ClientException("tool_id 通配仅支持 mcp:*（全部 MCP 工具）");
        }
        request.setToolId(toolId);
    }

    private static boolean isUniqueViolation(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause.getMessage() != null && cause.getMessage().contains("uk_agent_tool_permission_role_tool")) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 按 id 删除（配置表用硬删，无逻辑删除列）
     */
    public void remove(String id) {
        if (StrUtil.isBlank(id) || mapper.deleteById(id) == 0) {
            throw new ClientException("规则不存在或已被删除");
        }
    }
}
