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

package com.nageoffer.ai.ragent.ingestion.strategy.fetcher;

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.ingestion.domain.context.DocumentSource;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.rag.security.IngestionUrlGuard;
import com.nageoffer.ai.ragent.rag.security.RedirectGuard;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 文档获取器单元测试（O2/M5）：抓取字节上限——超限响应被拒且错误含限额语义，
 * 不超限的正常抓取回归通过。重定向行为由 RedirectGuardTests/NewsHttpFetchClientTests 覆盖
 */
class HttpUrlFetcherTests {

    private MockWebServer server;
    private HttpUrlFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        // 回环 MockWebServer：宽松档守卫（与 News/RedirectGuard 测试同口径）
        fetcher = new HttpUrlFetcher(new HttpClientHelper(
                new OkHttpClient(), new RedirectGuard(new IngestionUrlGuard(true))));
        ReflectionTestUtils.setField(fetcher, "maxFileSize", DataSize.ofBytes(16));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void oversizedBodyRejectedWithLimitSemantics() {
        server.enqueue(new MockResponse.Builder()
                .body("x".repeat(64))
                .build());

        DocumentSource source = DocumentSource.builder()
                .type(SourceType.URL)
                .location(server.url("/doc.pdf").toString())
                .build();

        ServiceException ex = assertThrows(ServiceException.class, () -> fetcher.fetch(source));

        assertTrue(ex.getMessage().contains("文件大小超过限制"), "错误信息应含限额语义: " + ex.getMessage());
    }

    @Test
    void bodyWithinLimitFetchesNormally() {
        byte[] payload = "valid-content".getBytes(StandardCharsets.UTF_8);
        server.enqueue(new MockResponse.Builder()
                .body(new String(payload, StandardCharsets.UTF_8))
                .build());

        DocumentSource source = DocumentSource.builder()
                .type(SourceType.URL)
                .location(server.url("/doc.txt").toString())
                .build();

        FetchResult result = fetcher.fetch(source);

        assertArrayEquals(payload, result.content());
    }
}
