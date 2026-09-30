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

package com.nageoffer.ai.ragent.calendar.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import com.nageoffer.ai.ragent.calendar.controller.vo.KeyDateAuditVO;
import com.nageoffer.ai.ragent.calendar.service.KeyDateQueryService;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 校历纯读核对视图（#193 可选项落地；admin 面，零写径）。
 *
 * <p>维护者核对入口：逐源最近一轮诊断（解析行数+upsert/撤回/恢复/不变计数
 * 或退化原因——即合同「抓取轮日志」的落库载体，#192 摄取面写入、本端点只读）
 * + 隔离/探测计数 + 事件行数分布。与公开面差异=透出 lastDiag 与内部计数；
 * 路径落 SaTokenConfig.ADMIN_PATH_PATTERNS（/admin/**），方法级再钉
 * admin 角色（DashboardController 同范式）。
 */
@RestController
@RequestMapping("/admin/calendar")
@RequiredArgsConstructor
@SaCheckRole("admin")
public class AdminCalendarAuditController {

    private final KeyDateQueryService keyDateQueryService;

    /**
     * 源级核对（纯读）：五源状态+诊断+行数分布，无任何写径
     */
    @GetMapping("/key-dates/audit")
    public Result<List<KeyDateAuditVO>> audit() {
        return Results.success(keyDateQueryService.auditSources());
    }
}
