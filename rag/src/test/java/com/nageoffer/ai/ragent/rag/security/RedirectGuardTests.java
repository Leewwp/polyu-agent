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

package com.nageoffer.ai.ragent.rag.security;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重定向守卫单元测试（O2/M4）：逐跳复校（内网/元数据/非 HTTP scheme 拒绝）、
 * 跳数上限（环终止）、相对 Location 解析、正常跳转跟随。
 * 严格档（allow-private-hosts=false）覆盖拒绝场景；回环 MockWebServer 的
 * 正常跟随场景用宽松档。
 */
class RedirectGuardTests {

    private MockWebServer server;
    private final OkHttpClient client = new OkHttpClient();
    private final RedirectGuard permissiveGuard = new RedirectGuard(new IngestionUrlGuard(true));
    private final RedirectGuard strictGuard = new RedirectGuard(new IngestionUrlGuard(false));

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    private String url(String path) {
        return server.url(path).toString();
    }

    private Request get(String path) {
        return new Request.Builder().url(url(path)).get().build();
    }

    private String executeBody(RedirectGuard guard, String path) throws Exception {
        try (Response response = guard.execute(client, get(path))) {
            return response.body() == null ? "" : response.body().string();
        }
    }

    @Test
    void followsNormalRedirectChainWithinHopLimit() throws Exception {
        server.enqueue(redirect("/b"));
        server.enqueue(redirect("/c"));
        server.enqueue(body("final-content"));

        String body = executeBody(permissiveGuard, "/a");

        assertEquals("final-content", body);
        assertEquals(3, server.getRequestCount());
    }

    @Test
    void resolvesRelativeLocationAgainstCurrentUrl() throws Exception {
        server.enqueue(redirect("landing"));   // 相对路径（无前导斜杠）
        server.enqueue(body("landed"));

        String body = executeBody(permissiveGuard, "/start/page");

        assertEquals("landed", body);
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void redirectToMetadataAddressRejectedBeforeConnecting() {
        server.enqueue(redirect("http://169.254.169.254/latest/meta-data/"));

        ClientException ex = assertThrows(ClientException.class,
                () -> executeBody(strictGuard, "/public"));

        assertTrue(ex.getMessage().contains("内网") || ex.getMessage().contains("保留"));
        // 只发了初始请求，未对元数据地址建立任何连接
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void redirectToIntranetNameRejected() {
        // 内网主机名形态（polyu-es 为 compose 服务名，公网不可解析为公网地址）
        server.enqueue(redirect("http://polyu-es:9200/_cat/indices"));

        assertThrows(ClientException.class, () -> executeBody(strictGuard, "/public"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void redirectToNonHttpSchemeRejected() {
        // file:// 等非 HTTP scheme：OkHttp HttpUrl.resolve 对其返回 null，
        // 在构造下一跳请求前即整单失败（与守卫 scheme 校验等效的双保险）
        server.enqueue(redirect("file:///etc/passwd"));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> executeBody(permissiveGuard, "/public"));

        assertTrue(ex.getMessage().contains("无法解析"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void redirectLoopTerminatesAtHopLimit() {
        // 恒 302 自跳转：初始 + 3 跳后达上限整单失败
        server.enqueue(redirect(url("/loop")));
        server.enqueue(redirect(url("/loop")));
        server.enqueue(redirect(url("/loop")));
        server.enqueue(redirect(url("/loop")));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> executeBody(permissiveGuard, "/loop"));

        assertTrue(ex.getMessage().contains("跳数上限"));
        assertEquals(4, server.getRequestCount());
    }

    @Test
    void redirectWithoutLocationHeaderFails() {
        server.enqueue(new MockResponse.Builder().code(302).build());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> executeBody(permissiveGuard, "/dangling"));

        assertTrue(ex.getMessage().contains("Location"));
        assertEquals(1, server.getRequestCount());
    }

    private static MockResponse redirect(String location) {
        return new MockResponse.Builder().code(302)
                .addHeader("Location", location)
                .build();
    }

    private static MockResponse body(String body) {
        return new MockResponse.Builder().body(body).build();
    }
}
