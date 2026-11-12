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

import cn.dev33.satoken.annotation.SaCheckRole;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具调用审计管理端查询（#486 最简化）：列表+四维过滤+分页。
 * /admin/** 已在 SaTokenConfig.ADMIN_PATH_PATTERNS，@SaCheckRole 与清单双保险（沿 DashboardController 先例）。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/agent-tool-calls")
@SaCheckRole("admin")
@ConditionalOnAgentEngine
public class AgentToolCallAuditAdminController {

    private final AgentToolCallAuditAdminReader reader;

    @GetMapping
    public Result<IPage<AgentToolCallAuditVO>> page(AgentToolCallAuditQueryRequest requestParam) {
        return Results.success(reader.page(requestParam));
    }
}
