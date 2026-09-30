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

import com.nageoffer.ai.ragent.calendar.ics.KeyDateIcsFeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 公开校历 .ics 订阅控制器（#194；薄壳，生成在 {@link KeyDateIcsFeedService}）。
 *
 * <p>路径在 SaToken 白名单（/public/calendar/**，#193 已放行）——日历客户端匿名
 * 拉取，无 JSON Result 包裹（text/calendar 原文）。flag rag.calendar.enabled 关
 * （默认）时不装配，由 {@link PublicCalendarDisabledController} 孪生 404 兜底。
 *
 * <p>Cache-Control 一小时是客户端节流的卫生值，<b>不构成提醒必达承诺</b>——
 * 手机实际刷新频率由用户日历账户设置决定（票面兼容性观察项口径）。
 */
@RestController
@RequestMapping("/public/calendar")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.calendar.enabled", havingValue = "true")
public class PublicKeyDateIcsController {

    /**
     * RFC 5545 注册媒体类型（+charset 参数按惯例内联）
     */
    private static final MediaType TEXT_CALENDAR = MediaType.parseMediaType(
            "text/calendar;charset=" + StandardCharsets.UTF_8);

    private final KeyDateIcsFeedService feedService;

    /**
     * 订阅 feed：GET /public/calendar/key-dates.ics（手机/桌面日历「添加订阅
     * 日历」填此 URL）。导出即执行唯一写径回写（ics_export_state/ics_last_dates）
     */
    @GetMapping(value = "/key-dates.ics", produces = "text/calendar;charset=UTF-8")
    public ResponseEntity<String> keyDatesIcs() {
        KeyDateIcsFeedService.FeedResult result = feedService.exportFeed();
        return ResponseEntity.ok()
                .contentType(TEXT_CALENDAR)
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .body(result.feed());
    }
}
