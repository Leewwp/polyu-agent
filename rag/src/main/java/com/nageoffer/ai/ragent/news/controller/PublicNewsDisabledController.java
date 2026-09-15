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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开资讯关闭态兜底（照 PublicShare 孪生范式）：flag 关（默认）时
 * PublicNewsController 不装配，本兜底接管同路径返回 HTTP 404——
 * 与「功能未部署」语义一致（关闭态口径），不泄漏功能开关状态，
 * 也避免 no-handler 落到系统错误产生运维噪音。
 */
@RestController
@RequestMapping("/public/news")
@ConditionalOnProperty(name = "rag.news.enabled", havingValue = "false", matchIfMissing = true)
public class PublicNewsDisabledController {

    /**
     * 与启用态同形的四端点 404 兜底（URL 形状保持一致，避免路径参数不匹配漏出 no-handler）
     */
    @GetMapping("/list")
    public ResponseEntity<Void> list(@RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return notFound();
    }

    @GetMapping("/hot")
    public ResponseEntity<Void> hot(@RequestParam(value = "limit", defaultValue = "10") int limit) {
        return notFound();
    }

    /**
     * 与启用态同形的详情端点 404 兜底（后增路径）
     */
    @GetMapping("/detail")
    public ResponseEntity<Void> detail(@RequestParam("id") long id) {
        return notFound();
    }

    /**
     * 与启用态同形的检索端点 404 兜底（后增路径）
     */
    @GetMapping("/search")
    public ResponseEntity<Void> search(@RequestParam("q") String q,
            @RequestParam(value = "sort", defaultValue = "time") String sort,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return notFound();
    }

    @GetMapping("/topics")
    public ResponseEntity<Void> topics() {
        return notFound();
    }

    @GetMapping("/topic/{slug}")
    public ResponseEntity<Void> topicDetail(@PathVariable String slug,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return notFound();
    }

    private ResponseEntity<Void> notFound() {
        return ResponseEntity.notFound().build();
    }
}
