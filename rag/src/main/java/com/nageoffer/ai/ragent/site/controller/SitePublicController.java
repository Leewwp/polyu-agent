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

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.site.controller.request.SiteFeedbackSubmitRequest;
import com.nageoffer.ai.ragent.site.controller.vo.SiteAboutVO;
import com.nageoffer.ai.ragent.site.service.SiteAboutService;
import com.nageoffer.ai.ragent.site.service.SiteFeedbackService;
import com.nageoffer.ai.ragent.user.security.ClientIps;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点反馈与关于页公开端点（doc 25 D5：rag.site.enabled 总开关，默认关）。
 * 前缀 /public/feedback、/public/about 已进 PUBLIC_EXCLUDE_PATTERNS——
 * 匿名可访问，UserContext 拦截器整体跳过（表无 user_id 列的原因）
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.site.enabled", havingValue = "true")
public class SitePublicController {

    private final SiteFeedbackService siteFeedbackService;
    private final SiteAboutService siteAboutService;

    /**
     * 匿名提交反馈（IP 日限 ≤5 条）
     */
    @PostMapping("/public/feedback")
    public Result<Void> submitFeedback(@RequestBody SiteFeedbackSubmitRequest requestParam) {
        siteFeedbackService.submit(requestParam.getContent(), requestParam.getContact(), ClientIps.resolve());
        return Results.success(null);
    }

    /**
     * 关于页只读（未配置返回空内容态）
     */
    @GetMapping("/public/about")
    public Result<SiteAboutVO> getAbout() {
        return Results.success(siteAboutService.getAbout());
    }
}
