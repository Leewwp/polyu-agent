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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 站点公开端点关闭态兜底（照 PublicNewsDisabledController 孪生范式）：
 * rag.site.enabled=false（默认）时 SitePublicController 不装配，
 * 本兜底接管同路径返回 404——与「功能未部署」语义一致，不泄漏开关状态。
 * 两前缀共用一个孪生（doc 25 D5）
 */
@RestController
@ConditionalOnProperty(name = "rag.site.enabled", havingValue = "false", matchIfMissing = true)
public class SitePublicDisabledController {

    @PostMapping("/public/feedback")
    public ResponseEntity<Void> submitFeedback() {
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/public/about")
    public ResponseEntity<Void> getAbout() {
        return ResponseEntity.notFound().build();
    }
}
