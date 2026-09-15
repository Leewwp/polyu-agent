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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Origin/Referer 守卫测试：写请求来源不在白名单即拒；非浏览器客户端
 * （无 Origin/Referer）与 GET/OPTIONS 不受影响；Referer 兜底解析取 origin 部分。
 */
class OriginRefererGuardInterceptorTest {

    private OriginRefererGuardInterceptor interceptor;

    @BeforeEach
    void setUp() {
        SecurityCorsProperties properties = new SecurityCorsProperties();
        properties.setAllowedOrigins(List.of("https://polyuguide.com", "http://localhost:5173"));
        interceptor = new OriginRefererGuardInterceptor(properties);
    }

    private MockHttpServletRequest request(String method) {
        return new MockHttpServletRequest(method, "/api/ragent/auth/logout");
    }

    @Test
    void rejectsStateChangingRequestFromForeignOrigin() {
        MockHttpServletRequest request = request("POST");
        request.addHeader("Origin", "https://evil.example");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("跨站请求来源不在允许清单");
    }

    @Test
    void rejectsStateChangingRequestWithForeignRefererOnly() {
        // 旧浏览器只带 Referer 的兜底路径：解析 origin 部分后同样校验
        MockHttpServletRequest request = request("POST");
        request.addHeader("Referer", "https://evil.example/login-page?x=1");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ClientException.class);
    }

    @Test
    void rejectsMalformedReferer() {
        MockHttpServletRequest request = request("PUT");
        request.addHeader("Referer", "::not-a-uri");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(ClientException.class);
    }

    @Test
    void allowsAllowedOrigin() {
        MockHttpServletRequest request = request("POST");
        request.addHeader("Origin", "https://polyuguide.com");
        assertThatCode(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsNonBrowserClientWithoutOriginAndReferer() {
        // curl/服务间调用：无来源头可校验，交给登录态/角色拦截
        assertThatCode(() -> interceptor.preHandle(request("POST"), new MockHttpServletResponse(), new Object()))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsNonStateChangingMethodsEvenWithForeignOrigin() {
        MockHttpServletRequest get = request("GET");
        get.addHeader("Origin", "https://evil.example");
        assertThatCode(() -> interceptor.preHandle(get, new MockHttpServletResponse(), new Object()))
                .doesNotThrowAnyException();

        MockHttpServletRequest options = request("OPTIONS");
        options.addHeader("Origin", "https://evil.example");
        assertThatCode(() -> interceptor.preHandle(options, new MockHttpServletResponse(), new Object()))
                .doesNotThrowAnyException();
    }
}
