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

package com.nageoffer.ai.ragent.site.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.site.controller.request.SiteFeedbackStatusRequest;
import com.nageoffer.ai.ragent.site.controller.vo.SiteFeedbackVO;
import com.nageoffer.ai.ragent.site.service.SiteFeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 反馈管理面控制器（doc 25）：不挂 flag（照 AdminNewsItemController 先例）——
 * /admin/** 已在 SaTokenConfig ADMIN_PATH_PATTERNS 清单内（admin 角色拦截
 * + 审计自动覆盖），零额外配置
 */
@RestController
@RequestMapping("/admin/feedback")
@RequiredArgsConstructor
public class SiteFeedbackAdminController {

    private final SiteFeedbackService siteFeedbackService;

    /**
     * 分页列表 + status 过滤（时间倒序）
     */
    @GetMapping
    public Result<IPage<SiteFeedbackVO>> pageQuery(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(value = "status", required = false) Integer status) {
        return Results.success(siteFeedbackService.pageQuery(page, size, status));
    }

    /**
     * 状态流转（0 未处理 / 1 已处理 / 2 忽略）
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id,
            @RequestBody SiteFeedbackStatusRequest requestParam) {
        siteFeedbackService.updateStatus(id, requestParam.getStatus());
        return Results.success(null);
    }

    /**
     * 删除（逻辑删）
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        siteFeedbackService.delete(id);
        return Results.success(null);
    }
}
