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
import com.nageoffer.ai.ragent.site.controller.request.SiteAboutSaveRequest;
import com.nageoffer.ai.ragent.site.controller.vo.SiteAboutVO;
import com.nageoffer.ai.ragent.site.service.SiteAboutService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 关于页管理面控制器（doc 25）：不挂 flag（admin 面先例）；
 * /admin/** 由 SaTokenConfig 覆盖 admin 拦截+审计
 */
@RestController
@RequestMapping("/admin/about")
@RequiredArgsConstructor
public class SiteAboutAdminController {

    private final SiteAboutService siteAboutService;

    /**
     * 当前内容（编辑回显）
     */
    @GetMapping
    public Result<SiteAboutVO> getAbout() {
        return Results.success(siteAboutService.getAbout());
    }

    /**
     * 保存（upsert：内容+两码 URL 整段覆盖）
     */
    @PutMapping
    public Result<Void> save(@RequestBody SiteAboutSaveRequest requestParam) {
        siteAboutService.saveAbout(requestParam.getContent(), requestParam.getContentEn(),
                requestParam.getQrImageUrl(), requestParam.getQrImageUrlAlt());
        return Results.success(null);
    }

    /**
     * 二维码上传（png/jpg/webp ≤2MB）→ 资产桶公共 URL；前端拿 URL 再随 save 落表
     */
    @PostMapping("/qr")
    public Result<Map<String, String>> uploadQr(@RequestParam("file") MultipartFile file) throws Exception {
        String url = siteAboutService.uploadQr(file.getBytes(),
                file.getOriginalFilename(), file.getContentType());
        return Results.success(Map.of("url", url));
    }
}
