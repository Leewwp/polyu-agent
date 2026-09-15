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

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClientIps 取值顺序测试。
 *
 * <p>顺序语义：X-Forwarded-For 首值 → X-Real-IP → remoteAddr。生产网关自 2026-09-12
 * 起以 X-Forwarded-For 覆写 $remote_addr（edge 层修法），XFF 首值即真实客户端 IP，
 * 伪造头不再改变限流/锁定/配额的计费 IP；本测试锁定取值顺序不因改动漂移。
 */
class ClientIpsTest {

    @Test
    void prefersFirstXffValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        assertThat(ClientIps.fromRequest(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void fallsBackToRealIpWhenXffAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Real-IP", "203.0.113.9");
        request.setRemoteAddr("127.0.0.1");
        assertThat(ClientIps.fromRequest(request)).isEqualTo("203.0.113.9");
    }

    @Test
    void fallsBackToRemoteAddrWhenNoForwardedHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.5");
        assertThat(ClientIps.fromRequest(request)).isEqualTo("192.0.2.5");
    }

    @Test
    void trimsWhitespaceAroundFirstXffValue() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", " 203.0.113.8 , 10.0.0.2");
        assertThat(ClientIps.fromRequest(request)).isEqualTo("203.0.113.8");
    }
}
