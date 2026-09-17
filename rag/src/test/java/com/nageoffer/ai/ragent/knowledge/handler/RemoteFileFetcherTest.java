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

package com.nageoffer.ai.ragent.knowledge.handler;

import com.nageoffer.ai.ragent.core.parser.HtmlDocumentParser;
import com.nageoffer.ai.ragent.ingestion.util.HttpClientHelper;
import com.nageoffer.ai.ragent.rag.service.FileStorageService;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RemoteFileFetcherTest {

    private static final String URL = "https://example.com/remote.txt";
    private static final String HTML_URL = "https://example.com/page.html";
    private static final String LAST_MODIFIED = "Tue, 21 Jul 2026 00:00:00 GMT";
    private static final byte[] NEW_CONTENT = "new remote content".getBytes();

    @Mock
    private HttpClientHelper httpClientHelper;

    @Mock
    private FileStorageService fileStorageService;

    private RemoteFileFetcher fetcher;

    @BeforeEach
    void setUp() throws Exception {
        fetcher = new RemoteFileFetcher(httpClientHelper, fileStorageService, new HtmlDocumentParser());
        Field maxFileSize = RemoteFileFetcher.class.getDeclaredField("maxFileSize");
        maxFileSize.setAccessible(true);
        maxFileSize.set(fetcher, DataSize.ofMegabytes(1));

        lenient().when(httpClientHelper.openStream(eq(URL), eq(Map.of()), anyLong()))
                .thenAnswer(invocation -> stream("etag-v2", LAST_MODIFIED, NEW_CONTENT));
    }

    @Test
    void shouldDownloadWhenEtagChangesEvenIfLastModifiedMatches() {
        when(httpClientHelper.head(URL, Map.of())).thenReturn(head("etag-v2", LAST_MODIFIED));

        try (RemoteFileFetcher.RemoteFetchResult result =
                     fetcher.fetchIfChanged(URL, "etag-v1", LAST_MODIFIED, "old-hash", "remote.txt")) {
            assertTrue(result.changed());
            assertEquals("etag-v2", result.etag());
        }

        verify(httpClientHelper).openStream(eq(URL), eq(Map.of()), anyLong());
    }

    @Test
    void shouldSkipWhenEtagMatchesEvenIfLastModifiedChanges() {
        when(httpClientHelper.head(URL, Map.of())).thenReturn(head("etag-v1", "Tue, 21 Jul 2026 00:00:01 GMT"));

        try (RemoteFileFetcher.RemoteFetchResult result =
                     fetcher.fetchIfChanged(URL, "etag-v1", LAST_MODIFIED, "old-hash", "remote.txt")) {
            assertFalse(result.changed());
            assertEquals("远程文件未变化", result.message());
        }

        verify(httpClientHelper, never()).openStream(eq(URL), eq(Map.of()), anyLong());
    }

    @Test
    void shouldFallbackToLastModifiedWhenEtagCannotBeCompared() {
        when(httpClientHelper.head(URL, Map.of())).thenReturn(head(null, LAST_MODIFIED));

        try (RemoteFileFetcher.RemoteFetchResult result =
                     fetcher.fetchIfChanged(URL, "etag-v1", LAST_MODIFIED, "old-hash", "remote.txt")) {
            assertFalse(result.changed());
            assertEquals("远程文件未变化", result.message());
        }

        verify(httpClientHelper, never()).openStream(eq(URL), eq(Map.of()), anyLong());
    }

    @Test
    void shouldDownloadAndUseHashWhenValidatorsAreMissing() throws Exception {
        when(httpClientHelper.head(URL, Map.of())).thenReturn(head(null, null));
        String contentHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(NEW_CONTENT));

        try (RemoteFileFetcher.RemoteFetchResult result =
                     fetcher.fetchIfChanged(URL, null, null, contentHash, "remote.txt")) {
            assertFalse(result.changed());
            assertEquals("内容哈希未变化", result.message());
            assertEquals(contentHash, result.contentHash());
        }

        verify(httpClientHelper).openStream(eq(URL), eq(Map.of()), anyLong());
    }

    @Test
    void shouldSkipWhenHtmlAssetVersionJitterOnlyChangesHead() throws Exception {
        // 首轮：无基线 → 判变化，落库 n2: 规范化哈希
        when(httpClientHelper.head(HTML_URL, Map.of())).thenReturn(headHtml(null, null));
        when(httpClientHelper.openStream(eq(HTML_URL), eq(Map.of()), anyLong()))
                .thenReturn(streamHtml("etag-h1", null, htmlPage("20260915141154", "正文保持一致")))
                .thenReturn(streamHtml("etag-h2", null, htmlPage("20260915142349", "正文保持一致")));

        String baselineHash;
        try (RemoteFileFetcher.RemoteFetchResult first =
                     fetcher.fetchIfChanged(HTML_URL, null, null, null, "page.html")) {
            assertTrue(first.changed());
            assertTrue(first.contentHash().startsWith("n2:"));
            baselineHash = first.contentHash();
        }

        // 次轮：head 内资源版本号查询串变化、正文一致 → SKIPPED（T23 核心判据）
        try (RemoteFileFetcher.RemoteFetchResult second =
                     fetcher.fetchIfChanged(HTML_URL, null, null, baselineHash, "page.html")) {
            assertFalse(second.changed());
            assertEquals("内容规范化哈希未变化", second.message());
            assertEquals(baselineHash, second.contentHash());
        }
    }

    @Test
    void shouldDetectRealHtmlContentChange() throws Exception {
        when(httpClientHelper.head(HTML_URL, Map.of())).thenReturn(headHtml(null, null));
        when(httpClientHelper.openStream(eq(HTML_URL), eq(Map.of()), anyLong()))
                .thenReturn(streamHtml(null, null, htmlPage("v1", "原正文内容")))
                .thenReturn(streamHtml(null, null, htmlPage("v2", "正文已被真实修改")));

        String baselineHash;
        try (RemoteFileFetcher.RemoteFetchResult first =
                     fetcher.fetchIfChanged(HTML_URL, null, null, null, "page.html")) {
            assertTrue(first.changed());
            baselineHash = first.contentHash();
        }

        try (RemoteFileFetcher.RemoteFetchResult second =
                     fetcher.fetchIfChanged(HTML_URL, null, null, baselineHash, "page.html")) {
            assertTrue(second.changed());
        }
    }

    @Test
    void shouldMigrateLegacyRawHashAsUnchanged() throws Exception {
        // 旧方案原始字节哈希（无 n2: 前缀）不可比：按未变化处理并登记新方案哈希
        when(httpClientHelper.head(HTML_URL, Map.of())).thenReturn(headHtml(null, null));
        when(httpClientHelper.openStream(eq(HTML_URL), eq(Map.of()), anyLong()))
                .thenReturn(streamHtml(null, null, htmlPage("20260915141154", "正文保持一致")));
        String legacyRawHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(htmlPage("20260915141154", "正文保持一致")));

        try (RemoteFileFetcher.RemoteFetchResult result =
                     fetcher.fetchIfChanged(HTML_URL, null, null, legacyRawHash, "page.html")) {
            assertFalse(result.changed());
            assertEquals("哈希方案迁移（旧原始字节哈希不可比，按未变化登记新方案哈希）", result.message());
            assertTrue(result.contentHash().startsWith("n2:"));
        }
    }

    /**
     * 同正文、仅 head 资源版本号查询串不同的两份页面在字节层面必然不同
     */
    private static byte[] htmlPage(String assetVersion, String bodyText) {
        return ("<html><head><link rel=\"stylesheet\" href=\"/assets/css/style.css?v=" + assetVersion
                + "\"></head><body><main><h1>标题</h1><p>" + bodyText + "</p></main></body></html>").getBytes();
    }

    private static HttpClientHelper.HttpHeadResponse head(String etag, String lastModified) {
        return new HttpClientHelper.HttpHeadResponse(etag, lastModified, "text/plain", (long) NEW_CONTENT.length, "remote.txt");
    }

    private static HttpClientHelper.HttpHeadResponse headHtml(String etag, String lastModified) {
        return new HttpClientHelper.HttpHeadResponse(etag, lastModified, "text/html", null, "page.html");
    }

    private static HttpClientHelper.HttpFetchStream streamHtml(String etag, String lastModified, byte[] content) {
        Response response = new Response.Builder()
                .request(new Request.Builder().url(HTML_URL).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.get("text/html"), content))
                .build();
        return new HttpClientHelper.HttpFetchStream(
                response,
                new ByteArrayInputStream(content),
                "text/html",
                "page.html",
                etag,
                lastModified,
                (long) content.length);
    }

    private static HttpClientHelper.HttpFetchStream stream(String etag, String lastModified, byte[] content) {
        Response response = new Response.Builder()
                .request(new Request.Builder().url(URL).build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.get("text/plain"), content))
                .build();
        return new HttpClientHelper.HttpFetchStream(
                response,
                new ByteArrayInputStream(content),
                "text/plain",
                "remote.txt",
                etag,
                lastModified,
                (long) content.length);
    }
}
