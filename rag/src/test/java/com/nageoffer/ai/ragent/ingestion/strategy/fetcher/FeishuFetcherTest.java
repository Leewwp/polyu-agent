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
import com.nageoffer.ai.ragent.rag.config.FetchLimits;
import com.nageoffer.ai.ragent.rag.security.IngestionUrlGuard;
import com.nageoffer.ai.ragent.rag.security.RedirectGuard;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 飞书抓取器首测（issue #125 测试欠账：此前零测试）。覆盖离线可测面：文件下载分支
 * 走守卫 helper+上限（issue #125 起 URL 承载内容的抓取必有限）、空地址校验、
 * docx 令牌解析防御面；docx 内容分支与 token 面的 URL 为硬编码 open.feishu.cn
 * 常量，离线不可测，留待线上冒烟。
 */
class FeishuFetcherTest {

    private MockWebServer server;
    private FeishuFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        fetcher = newFetcher("1024B");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    private FeishuFetcher newFetcher(String rawLimit) {
        HttpClientHelper helper = new HttpClientHelper(new OkHttpClient(), new RedirectGuard(new IngestionUrlGuard(true)));
        return new FeishuFetcher(new OkHttpClient(), helper, limits(rawLimit));
    }

    private static FetchLimits limits(String raw) {
        FetchLimits limits = new FetchLimits();
        ReflectionTestUtils.setField(limits, "maxFileSize", raw);
        return limits;
    }

    private DocumentSource source(String location) {
        return DocumentSource.builder()
                .type(SourceType.FEISHU)
                .location(location)
                .fileName(null)
                .credentials(null)
                .build();
    }

    @Test
    void fileDownloadBranchFetchesBytesWithTypeAndName() {
        server.enqueue(new MockResponse.Builder()
                .addHeader("Content-Type", "application/pdf")
                .body("feishu-file-bytes")
                .build());

        FetchResult result = fetcher.fetch(source(server.url("/space/file.pdf").toString()));

        assertArrayEquals("feishu-file-bytes".getBytes(), result.content());
        assertEquals("application/pdf", result.mimeType());
        assertEquals("file.pdf", result.fileName());
    }

    @Test
    void oversizedFileDownloadIsRejected() {
        server.enqueue(new MockResponse.Builder()
                .body("x".repeat(64))
                .build());

        FeishuFetcher limited = newFetcher("16B");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> limited.fetch(source(server.url("/space/big.pdf").toString())));
        assertTrue(ex.getMessage().contains("文件大小超过限制"));
    }

    @Test
    void blankLocationIsRejected() {
        ServiceException ex = assertThrows(ServiceException.class, () -> fetcher.fetch(source(" ")));
        assertEquals("飞书文档地址不能为空", ex.getMessage());
    }
}
