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

package com.nageoffer.ai.ragent.user.controller;

import java.time.Duration;

import com.nageoffer.ai.ragent.user.security.ClientIps;
import com.nageoffer.ai.ragent.user.security.WindowCounter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * CSP 违规上报落点（B9 尾项 Report-Only 起步；doc103 §A.5 拍板）。
 *
 * <p>nginx 两 TLS server 块下发 Content-Security-Policy-Report-Only 头
 * （report-uri 指向 /csp-report，经网关 /api/ 前缀反代到应用 context path），浏览器把
 * 违规报告 POST 到本端点。落地形态=极简：WARN 日志（截断至 512 字符）+ 每 IP 窗口限流，
 * 不落库不回内容；两周观察零报告后再议转 enforce（届时端点随 enforce 策略一并定去留）。
 *
 * <p>路径已入 {@code SaTokenConfig.PUBLIC_EXCLUDE_PATTERNS}（匿名上报面——报告由浏览器
 * 自动发出，不携带登录态语义）；bootstrap 契约测试消费同一常量，无需另改。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CspReportController {

    /** 与 LoginRateLimiter 键风格一致的限流键前缀（ragent:rl:* 系） */
    private static final String RATE_KEY_PREFIX = "ragent:rl:csp:";
    /** Report-Only 期噪声上界：单 IP 5 分钟窗口最多记 60 条日志（超出静默丢——浏览器只认 2xx） */
    private static final int MAX_LOGGED_PER_WINDOW = 60;

    private final WindowCounter windowCounter;

    /**
     * 接收浏览器 CSP 违规报告（Content-Type 为 application/csp-report 或 application/json，
     * StringHttpMessageConverter 对任意类型均可承载 String，无需区分）。
     */
    @PostMapping("/csp-report")
    public ResponseEntity<Void> report(@RequestBody(required = false) String reportBody) {
        String ip = ClientIps.resolve();
        long count = windowCounter.increment(RATE_KEY_PREFIX + sanitize(ip), Duration.ofMinutes(5));
        if (count <= MAX_LOGGED_PER_WINDOW) {
            log.warn("[csp-report] ip={} body={}", ip, abbreviate(reportBody));
        }
        return ResponseEntity.noContent().build();
    }

    /** 键名只保留安全字符，防注入 Redis 键分隔符（与 LoginRateLimiter.sanitize 同口径） */
    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9.:_-]", "_");
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "<empty>";
        }
        return body.length() <= 512 ? body : body.substring(0, 512) + "...(truncated, total=" + body.length() + ")";
    }
}
