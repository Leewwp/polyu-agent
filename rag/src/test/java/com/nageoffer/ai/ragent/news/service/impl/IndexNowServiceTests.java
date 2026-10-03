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

package com.nageoffer.ai.ragent.news.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link IndexNowServiceImpl} 合同测试（#213）：本站 canonical 白名单（外部原文
 * URL 结构性丢弃）、200/202 受理分类、429 进退避窗（窗内静默跳过）、开关关闭零调用。
 * HTTP 层用 JDK 内建 HttpServer 桩（与 mcp-server 测试同法，零新增依赖）。
 */
class IndexNowServiceTests {

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicReference<JsonNode> lastBody = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startStub(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = exchange.getRequestBody().readAllBytes();
            lastBody.set(new ObjectMapper().readTree(new String(body, StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/indexnow";
    }

    private NewsFetchProperties props(String endpoint, boolean enabled) {
        NewsFetchProperties properties = new NewsFetchProperties();
        properties.setSiteBaseUrl("https://polyuguide.com");
        properties.setIndexnowEnabled(enabled);
        properties.setIndexnowKey("ee751b74b79272cc9e40f864450cbca4");
        properties.setIndexnowEndpoint(endpoint);
        return properties;
    }

    private IndexNowServiceImpl service(NewsFetchProperties properties, long clockMs) {
        return new IndexNowServiceImpl(new OkHttpClient(), properties, new ObjectMapper(), () -> clockMs);
    }

    @Test
    void submitsOnlyCanonicalSiteUrlsAndDropsExternalOnes() throws Exception {
        String endpoint = startStub(200);

        service(props(endpoint, true), 0L).submitSiteUrls(List.of(
                "/daily",
                "/news/179",
                "https://polyuguide.com/hot",
                "https://www.polyu.edu.hk/shtm/news-and-events/news/2026/x"));

        JsonNode body = lastBody.get();
        assertThat(body).isNotNull();
        assertThat(body.get("host").asText()).isEqualTo("polyuguide.com");
        assertThat(body.get("key").asText()).isEqualTo("ee751b74b79272cc9e40f864450cbca4");
        assertThat(body.get("keyLocation").asText())
                .isEqualTo("https://polyuguide.com/ee751b74b79272cc9e40f864450cbca4.txt");
        List<String> urls = new java.util.ArrayList<>();
        body.get("urlList").forEach(url -> urls.add(url.asText()));
        // 红线：外部原文 URL 不进提交清单
        assertThat(urls).containsExactlyInAnyOrder(
                "https://polyuguide.com/daily",
                "https://polyuguide.com/news/179",
                "https://polyuguide.com/hot");
    }

    @Test
    void acceptedOn200And202() throws Exception {
        String endpoint = startStub(202);

        IndexNowServiceImpl indexNow = service(props(endpoint, true), 0L);
        indexNow.submitSiteUrls(List.of("/daily"));
        indexNow.submitSiteUrls(List.of("/daily"));

        // 200/202=受理形态，不进退避：连续两次都实际发出
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void backoffWindowSuppressesAfter429() throws Exception {
        String endpoint = startStub(429);
        long[] clock = {1000L};
        IndexNowServiceImpl indexNow = new IndexNowServiceImpl(new OkHttpClient(),
                props(endpoint, true), new ObjectMapper(), () -> clock[0]);

        indexNow.submitSiteUrls(List.of("/daily"));
        assertThat(hits.get()).isEqualTo(1);
        // 退避窗内（+24h 前）：静默跳过
        clock[0] = 1000L + IndexNowServiceImpl.BACKOFF_MS - 1;
        indexNow.submitSiteUrls(List.of("/daily"));
        assertThat(hits.get()).isEqualTo(1);
        // 退避窗过后：恢复提交
        clock[0] = 1000L + IndexNowServiceImpl.BACKOFF_MS + 1;
        indexNow.submitSiteUrls(List.of("/daily"));
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void disabledFlagMakesNoHttpRequest() throws Exception {
        String endpoint = startStub(200);

        service(props(endpoint, false), 0L).submitSiteUrls(List.of("/daily"));

        assertThat(hits.get()).isZero();
    }

    @Test
    void missingKeyShortCircuitsBeforeHttp() throws Exception {
        String endpoint = startStub(200);
        NewsFetchProperties properties = props(endpoint, true);
        properties.setIndexnowKey("  ");

        service(properties, 0L).submitSiteUrls(List.of("/daily"));

        assertThat(hits.get()).isZero();
    }
}
