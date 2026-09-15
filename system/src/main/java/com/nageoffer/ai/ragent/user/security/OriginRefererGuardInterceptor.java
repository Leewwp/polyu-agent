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

package com.nageoffer.ai.ragent.user.security;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * 状态变更请求的 Origin/Referer 校验（CSRF 防线）。
 *
 * <p>浏览器会为跨站/同站请求强制携带不可伪造的 Origin（旧浏览器兜底 Referer）——
 * 凡带 Origin/Referer 且不在白名单（{@link SecurityCorsProperties}）的写请求一律拒绝；
 * 两者都不带的请求（curl/服务间调用）放行，由登录态与管理面角色拦截兜底。
 * GET 状态变更端点（/rag/v3/chat、/agent/v1/chat）由 nginx Sec-Fetch-Site 断言覆盖。
 */
@Component
@RequiredArgsConstructor
public class OriginRefererGuardInterceptor implements HandlerInterceptor {

    private static final Set<String> STATE_CHANGING_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    private final SecurityCorsProperties securityCorsProperties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getDispatcherType() == DispatcherType.ASYNC
                || !STATE_CHANGING_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            return true;
        }
        String origin = request.getHeader("Origin");
        String candidate = StringUtils.hasText(origin) ? origin : originFromReferer(request.getHeader("Referer"));
        if (!StringUtils.hasText(candidate)) {
            // 非浏览器客户端：无 Origin/Referer 可言，交给登录态/角色拦截
            return true;
        }
        boolean allowed = securityCorsProperties.getAllowedOrigins().stream()
                .anyMatch(allowedOrigin -> allowedOrigin.equalsIgnoreCase(candidate.trim()));
        if (!allowed) {
            throw new ClientException("跨站请求来源不在允许清单，已拒绝");
        }
        return true;
    }

    private String originFromReferer(String referer) {
        if (!StringUtils.hasText(referer)) {
            return null;
        }
        try {
            URI uri = URI.create(referer.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort();
            if (port < 0
                    || ("http".equalsIgnoreCase(uri.getScheme()) && port == 80)
                    || ("https".equalsIgnoreCase(uri.getScheme()) && port == 443)) {
                return String.format("%s://%s", uri.getScheme(), uri.getHost()).toLowerCase(Locale.ROOT);
            }
            return String.format("%s://%s:%d", uri.getScheme(), uri.getHost(), port).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException malformed) {
            return "invalid-referer";
        }
    }
}
