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

package com.nageoffer.ai.ragent.ingestion.util;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.security.IngestionUrlGuard;
import com.nageoffer.ai.ragent.rag.security.RedirectGuard;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HttpClientHelper 专属测试（issue #125 测试欠账补齐：此前 cap/etag/filename 仅被
 * 抓取器测试间接覆盖）。客户端用受信 new OkHttpClient()（连接层 GuardedDns 语义由
 * GuardedDnsTest/RedirectGuardTests 专项覆盖），守卫宽松档放行回环 MockWebServer；
 * 本类只测 helper 语义：限读/预检/头部投影/重定向跟随。
 */
class HttpClientHelperTest {

    private MockWebServer server;
    private HttpClientHelper helper;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        helper = new HttpClientHelper(new OkHttpClient(), new RedirectGuard(new IngestionUrlGuard(true)));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    private String url(String path) {
        return server.url(path).toString();
    }

    @Test
    void getReturnsBodyHeadersAndFileNameFromPath() {
        server.enqueue(new MockResponse.Builder()
                .addHeader("Content-Type", "application/pdf")
                .addHeader("ETag", "\"v1\"")
                .addHeader("Last-Modified", "Tue, 21 Jul 2026 00:00:00 GMT")
                .body("pdf-bytes")
                .build());

        HttpClientHelper.HttpFetchResponse resp = helper.get(url("/docs/guide.pdf"), Map.of());

        assertArrayEquals("pdf-bytes".getBytes(), resp.body());
        assertEquals("application/pdf", resp.contentType());
        assertEquals("\"v1\"", resp.etag());
        assertEquals("Tue, 21 Jul 2026 00:00:00 GMT", resp.lastModified());
        assertEquals("guide.pdf", resp.fileName());
    }

    @Test
    void fileNamePrefersContentDispositionOverPath() {
        server.enqueue(new MockResponse.Builder()
                .addHeader("Content-Disposition", "attachment; filename=\"annual-report.pdf\"")
                .body("x")
                .build());

        assertEquals("annual-report.pdf", helper.get(url("/download/12345"), Map.of()).fileName());
    }

    @Test
    void getWithLimitRejectsOversizedStreamingBody() {
        server.enqueue(new MockResponse.Builder()
                .body("x".repeat(64))
                .build());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> helper.getWithLimit(url("/big.bin"), Map.of(), 16));
        assertTrue(ex.getMessage().contains("文件大小超过限制"));
    }

    @Test
    void getWithLimitRejectsOversizedContentLengthBeforeBodyRead() {
        // MockWebServer 按实际 body 自动回填 Content-Length：64 字节体即真实超长头
        server.enqueue(new MockResponse.Builder()
                .body("x".repeat(64))
                .build());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> helper.getWithLimit(url("/huge.bin"), Map.of(), 16));
        assertTrue(ex.getMessage().contains("文件大小超过限制"));
    }

    @Test
    void headProjectsEtagLastModifiedAndContentLength() {
        server.enqueue(new MockResponse.Builder()
                .addHeader("ETag", "\"v2\"")
                .addHeader("Last-Modified", "Wed, 22 Jul 2026 00:00:00 GMT")
                .addHeader("Content-Length", "1024")
                .setHeader("Content-Type", "text/html")
                .code(200)
                .build());

        HttpClientHelper.HttpHeadResponse head = helper.head(url("/page.html"), Map.of());

        assertEquals("\"v2\"", head.etag());
        assertEquals("Wed, 22 Jul 2026 00:00:00 GMT", head.lastModified());
        assertEquals(1024L, head.contentLength());
        assertEquals("page.html", head.fileName());
    }

    @Test
    void redirectsAreFollowedHopByHop() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(302)
                .addHeader("Location", url("/final.html"))
                .build());
        server.enqueue(new MockResponse.Builder()
                .addHeader("Content-Type", "text/html")
                .body("<html>final</html>")
                .build());

        HttpClientHelper.HttpFetchResponse resp = helper.get(url("/hop"), Map.of());

        assertEquals("<html>final</html>", new String(resp.body()));
        // 逐跳跟随：首请求（/hop）+ 末请求（/final.html）共两次
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void openStreamRejectsOversizedKnownContentLength() {
        server.enqueue(new MockResponse.Builder()
                .addHeader("Content-Type", "application/octet-stream")
                .body("x".repeat(64))
                .build());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> helper.openStream(url("/stream.bin"), Map.of(), 16));
        assertTrue(ex.getMessage().contains("文件大小超过限制"));
    }

    @Test
    void wrapWithLimitAbortsStreamingReadAtLimit() throws Exception {
        InputStream wrapped = HttpClientHelper.wrapWithLimit(
                new java.io.ByteArrayInputStream("x".repeat(64).getBytes(java.nio.charset.StandardCharsets.UTF_8)), 16);

        // 限读原语语义=读到超限即抛（截断会伪装成完整内容，比失败更危险）
        ServiceException ex = assertThrows(ServiceException.class, () -> {
            byte[] buffer = new byte[8];
            while (wrapped.read(buffer) != -1) {
                // 读过限即抛
            }
        });
        assertTrue(ex.getMessage().contains("文件大小超过限制"));
    }
}
