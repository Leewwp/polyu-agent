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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公开校历关闭态兜底（照 PublicNewsDisabledController 孪生范式）：flag
 * rag.calendar.enabled 关（默认）时 {@link PublicKeyDateController} 与
 * {@link PublicKeyDateIcsController} 不装配，本兜底接管同路径返回 HTTP 404
 * ——「功能未部署」语义，不泄漏开关状态。
 */
@RestController
@RequestMapping("/public/calendar")
@ConditionalOnProperty(name = "rag.calendar.enabled", havingValue = "false", matchIfMissing = true)
public class PublicCalendarDisabledController {

    /**
     * 与启用态同形的端点 404 兜底（URL 形状一致，避免漏出 no-handler 系统错误）
     */
    @GetMapping("/key-dates")
    public ResponseEntity<Void> keyDates() {
        return ResponseEntity.notFound().build();
    }

    /**
     * .ics 订阅端点 404 兜底（#194 同范式：关闭态日历客户端得到 404 而非系统错误）
     */
    @GetMapping("/key-dates.ics")
    public ResponseEntity<Void> keyDatesIcs() {
        return ResponseEntity.notFound().build();
    }
}
