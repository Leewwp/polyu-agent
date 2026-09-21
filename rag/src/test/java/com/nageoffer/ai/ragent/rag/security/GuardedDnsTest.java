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
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接级出站地址复校（#101 M4 残余面）：纯内网解析拒、混合解析整体拒（不让
 * OkHttp 挑公网那条连）、全公网放行、宽松档放行，以及挂到真实 OkHttpClient
 * 上连接确实被拦 + syncHttpClient 共享 bean 不动的排除断言。
 */
class GuardedDnsTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    /** stub 解析：无视 hostname 返回给定地址 */
    private static Dns resolveTo(List<InetAddress> addresses) {
        return hostname -> addresses;
    }

    private static InetAddress address(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void purePrivateResolutionIsRejected() {
        GuardedDns dns = new GuardedDns(new IngestionUrlGuard(false),
                resolveTo(List.of(address("127.0.0.1"))));
        ClientException ex = assertThrows(ClientException.class, () -> dns.lookup("evil.example"));
        assertTrue(ex.getMessage().contains("内网"), ex.getMessage());
    }

    @Test
    void mixedResolutionFailsTheWholeCallNotJustOneAddress() {
        // 公网+内网混合：整单失败——OkHttp 不许在地址列表里挑公网那条连（重绑定面正在这里）
        GuardedDns dns = new GuardedDns(new IngestionUrlGuard(false),
                resolveTo(List.of(address("8.8.8.8"), address("10.0.0.5"))));
        assertThrows(ClientException.class, () -> dns.lookup("evil.example"));

        GuardedDns metadata = new GuardedDns(new IngestionUrlGuard(false),
                resolveTo(List.of(address("1.2.3.4"), address("169.254.169.254"))));
        assertThrows(ClientException.class, () -> metadata.lookup("evil.example"));
    }

    @Test
    void allPublicResolutionPassesThroughUnchanged() throws Exception {
        List<InetAddress> publicAddresses = List.of(address("8.8.8.8"), address("1.1.1.1"));
        GuardedDns dns = new GuardedDns(new IngestionUrlGuard(false), resolveTo(publicAddresses));
        List<InetAddress> resolved = dns.lookup("ok.example");
        assertEquals(publicAddresses, resolved);
    }

    @Test
    void lenientProfileAllowsPrivateAddressesLikeUrlLevelGuard() throws Exception {
        // allow-private-hosts=true（本地档）：与 URL 级守卫同口径放行，localhost 源文件可抓
        GuardedDns dns = new GuardedDns(new IngestionUrlGuard(true),
                resolveTo(List.of(address("127.0.0.1"))));
        assertEquals(1, dns.lookup("localhost-files").size());
    }

    @Test
    void unknownHostExceptionFromResolutionPropagatesUnwrapped() {
        GuardedDns dns = new GuardedDns(new IngestionUrlGuard(false), hostname -> {
            throw new UnknownHostException("no dns");
        });
        UnknownHostException ex = assertThrows(UnknownHostException.class, () -> dns.lookup("gone.example"));
        assertEquals("no dns", ex.getMessage());
    }

    @Test
    void okHttpCallThroughGuardedDnsNeverConnectsWhenResolutionIsInternal() {
        // 回环 MockWebServer 充当「重绑定后的内网目标」：stub Dns 把建连解析指到 127.0.0.1，
        // 守卫须在建连前拦截——请求失败且服务端零命中
        OkHttpClient client = new OkHttpClient.Builder()
                .dns(new GuardedDns(new IngestionUrlGuard(false), resolveTo(List.of(address("127.0.0.1")))))
                .build();
        assertThrows(Exception.class, () -> client.newCall(
                new Request.Builder().url(server.url("/steal").toString()).build()).execute());
        assertEquals(0, server.getRequestCount());
    }

    @Test
    void lenientClientStillReachesLoopbackServerThroughGuardedDns() throws Exception {
        // 反向对照：宽松档下同一挂载形态可正常完成请求（挂载本身不破坏建连）
        server.enqueue(new MockResponse.Builder().body("ok").build());
        OkHttpClient client = new OkHttpClient.Builder()
                .dns(new GuardedDns(new IngestionUrlGuard(true), resolveTo(List.of(address("127.0.0.1")))))
                .build();
        try (okhttp3.Response response = client.newCall(
                new Request.Builder().url(server.url("/doc").toString()).build()).execute()) {
            assertTrue(response.isSuccessful());
            assertEquals("ok", response.body().string());
        }
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void syncHttpClientBeanStaysOnSystemDns() {
        // 排除断言：可信内部端点（MinerU/LightRAG/WebSearchChannel）共用的 bean 不接守卫
        OkHttpClient syncClient = new com.nageoffer.ai.ragent.rag.config.HttpClientConfig().syncHttpClient();
        assertSame(okhttp3.Dns.SYSTEM, syncClient.dns());
    }
}
