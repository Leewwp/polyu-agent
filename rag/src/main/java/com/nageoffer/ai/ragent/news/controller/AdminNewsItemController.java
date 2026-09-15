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

package com.nageoffer.ai.ragent.news.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.news.service.NewsAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 资讯管理面控制器（单条快速下架通道）
 *
 * <p>路径 /admin/** 已在 SaTokenConfig ADMIN_PATH_PATTERNS 清单内（admin 角色拦截
 * + 审计自动覆盖），无需新增白名单行；admin UI 归后续扩展，本控制器仅承载
 * 应急止血最小端点。
 */
@RestController
@RequestMapping("/admin/news")
@RequiredArgsConstructor
public class AdminNewsItemController {

    private final NewsAdminService newsAdminService;

    /**
     * 单条下架：status published → hidden，列表/热点/主题计数同步消失，幂等
     */
    @PostMapping("/item/{id}/hide")
    public Result<Void> hide(@PathVariable Long id) {
        newsAdminService.hide(id);
        return Results.success(null);
    }
}
