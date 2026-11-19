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

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentToolPermissionDO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具权限矩阵管理端（#487）：/admin/** 下天然命中 SaTokenConfig.ADMIN_PATH_PATTERNS
 * （清单 /admin/** 项），@SaCheckRole 与清单双保险（沿 T1 AgentToolCallAuditAdminController 先例）；
 * AdminPathPatternsTest 增匹配力断言、bootstrap AdminSurfaceContractTest 全量契约自动归类。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/agent-tool-permissions")
@SaCheckRole("admin")
@ConditionalOnAgentEngine
public class AgentToolPermissionAdminController {

    private final AgentToolPermissionAdminService adminService;

    /**
     * 全量矩阵（＜百行不分页，role→tool_id 稳定排序）
     */
    @GetMapping
    public Result<List<AgentToolPermissionDO>> list() {
        return Results.success(adminService.list());
    }

    /**
     * 新增或更新：id 空=新增；role/decision/tool_id 语义校验在服务层
     */
    @PostMapping
    public Result<Void> save(@RequestBody AgentToolPermissionSaveRequest request) {
        adminService.save(request);
        return Results.success();
    }

    /**
     * 删除规则
     */
    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable String id) {
        adminService.remove(id);
        return Results.success();
    }
}
