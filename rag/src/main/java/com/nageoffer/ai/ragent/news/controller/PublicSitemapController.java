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

import com.nageoffer.ai.ragent.news.service.NewsSeoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 站点 sitemap 控制器（#213，父票 #182 r3 §发现面——P2-b）。
 *
 * <p>站点级常开（不随 rag.news.enabled 门控）：sitemap 本体只含静态公共路由，
 * 资讯段（主题页/详情页/日报归档）由 {@link NewsSeoService} 按 flag 条件拼入——
 * 关闭态不残留在售内容入口，也不整包 404（搜索引擎对间歇 404 的 sitemap 更敏感）。
 *
 * <p>路径 /public/sitemap.xml 已进 SaToken 白名单（PUBLIC_EXCLUDE_PATTERNS 精确项；
 * 类级映射即全路径——与白名单/契约测试三者同形）；
 * 网关把根路径 /sitemap.xml 精确反代到本端点。Cache-Control 一小时=抓取节流卫生值。
 */
@RestController
@RequestMapping("/public/sitemap.xml")
@RequiredArgsConstructor
public class PublicSitemapController {

    private static final MediaType APPLICATION_XML = MediaType.parseMediaType(
            "application/xml;charset=" + StandardCharsets.UTF_8);

    private final NewsSeoService newsSeoService;

    @GetMapping(produces = "application/xml;charset=UTF-8")
    public ResponseEntity<String> sitemap() {
        return ResponseEntity.ok()
                .contentType(APPLICATION_XML)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(newsSeoService.renderSitemap());
    }
}
