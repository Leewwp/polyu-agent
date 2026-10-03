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

package com.nageoffer.ai.ragent.mcp.rag;

import com.nageoffer.ai.ragent.mcp.executor.McpToolException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RagPublicApiClient} 的 HTTP 层测试（mock 用 JDK 内置 HttpServer，零新依赖）：
 * 验证 URL/查询参数构造、信封解析、业务错误与 404 的转译、传输失败不泄漏细节。
 * 隐藏隔离不在这里测——那是 rag 公开面的合同（rag 侧 NewsQueryServiceTests），
 * 本层只证明「只走公开端点、只透传公开面返回的数据」。
 */
class RagPublicApiClientTest {

    private static HttpServer server;
    private static String baseUrl;
    private static String lastNewsQuery;
    private static String lastNewsPath;
    private static com.sun.net.httpserver.HttpHandler defaultNewsHandler;
    private static com.sun.net.httpserver.HttpHandler defaultKeyDatesHandler;

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        defaultNewsHandler = exchange -> {
            lastNewsPath = exchange.getRequestURI().getPath();
            lastNewsQuery = exchange.getRequestURI().getRawQuery();
            respond(exchange, 200, """
                    {"code":"0","message":null,"data":{
                      "records":[{"id":7,"url":"https://example.invalid/7","titleZh":"标题","titleEn":"title",
                                  "summaryZh":"摘要","summaryEn":"summary","category":"campus",
                                  "publishTime":"2026-09-28T08:00:00.000+08:00","heat":3,
                                  "topics":["t"],"source":{"sourceKey":"official"}}],
                      "total":1,"page":1,"size":5,"hasMore":false},
                     "requestId":"r1"}""");
        };
        defaultKeyDatesHandler = exchange ->
                respond(exchange, 200, """
                        {"code":"0","message":null,"data":{
                          "coverageAcademicYear":"2026/27","lastFullSyncAt":"2026-10-01T08:00:00",
                          "today":"2026-10-03","anySourceAbnormal":false,
                          "currentAndUpcoming":[{"uid":"u1","titleZh":"缴费截止","titleEn":"Fee deadline",
                            "academicYear":"2026/27","term":"S1","precision":"exact-day",
                            "dateStart":"2026-10-03","dateEnd":null,"fuzzyHint":null,
                            "audienceText":"全日制本科生","sourceUrl":"https://polyu.example/fee",
                            "phase":"today","daysUntil":0}],
                          "recentPast":[],"archived":[],"undated":[],"archivedTotal":0},
                         "requestId":"r2"}""");
        server.createContext("/public/news/mcp-search", defaultNewsHandler);
        server.createContext("/public/calendar/key-dates", defaultKeyDatesHandler);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    @Test
    void shouldHitPublicNewsEndpointWithExpectedFilters() {
        RagPublicApi client = client();
        lastNewsQuery = null;

        RagPublicApi.NewsPage page = client.searchNews("奖学金", "scholarship",
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), 1, 5);

        assertEquals("/public/news/mcp-search", lastNewsPath, "只能走受限公开端点");
        Map<String, String> params = queryMap(lastNewsQuery);
        assertEquals("奖学金", params.get("q"));
        assertEquals("scholarship", params.get("topic"));
        assertEquals("2026-09-01", params.get("from"));
        assertEquals("2026-09-30", params.get("to"));
        assertEquals("1", params.get("page"));
        assertEquals("5", params.get("size"));
        assertEquals(1, page.records().size());
        assertEquals(Long.valueOf(1L), page.total());
        assertEquals("标题", page.records().get(0).titleZh());
        assertEquals("campus", page.records().get(0).category());
    }

    /**
     * 空条件不带对应查询参数（queryParamIfPresent 语义）：不是传空串
     */
    @Test
    void shouldOmitAbsentFiltersFromQuery() {
        lastNewsQuery = null;

        client().searchNews(null, null, null, null, 1, 10);

        Map<String, String> params = queryMap(lastNewsQuery);
        assertNull(params.get("q"));
        assertNull(params.get("topic"));
        assertNull(params.get("from"));
        assertNull(params.get("to"));
        assertEquals("10", params.get("size"));
    }

    @Test
    void shouldParseKeyDateBoard() {
        RagPublicApi.KeyDateBoard board = client().keyDateBoard();

        assertEquals("2026/27", board.coverageAcademicYear());
        assertEquals("2026-10-03", board.today());
        assertEquals(1, board.currentAndUpcoming().size());
        RagPublicApi.KeyDate date = board.currentAndUpcoming().get(0);
        assertEquals("缴费截止", date.titleZh());
        assertEquals("全日制本科生", date.audienceText());
        assertEquals(Integer.valueOf(0), date.daysUntil());
    }

    /**
     * 业务失败（信封 code 非 0）：message 是 rag 面向用户的文案 → McpToolException 原样透传
     */
    @Test
    void shouldTranslateBusinessFailureToToolException() {
        swapHandler("/public/news/mcp-search", 200,
                "{\"code\":\"A0400\",\"message\":\"主题不存在\",\"data\":null}");
        try {
            McpToolException ex = assertThrows(McpToolException.class,
                    () -> client().searchNews(null, "nope", null, null, 1, 10));
            assertEquals("主题不存在", ex.getMessage());
        } finally {
            restoreDefaultHandler("/public/news/mcp-search");
        }
    }

    /**
     * 404=功能开关关闭/未部署（DisabledController 孪生兜底）：已知形态给模型
     */
    @Test
    void shouldTranslate404ToFeatureUnavailable() {
        swapHandler("/public/calendar/key-dates", 404, "not found");
        try {
            McpToolException ex = assertThrows(McpToolException.class, () -> client().keyDateBoard());
            assertTrue(ex.getMessage().contains("未开放"));
        } finally {
            restoreDefaultHandler("/public/calendar/key-dates");
        }
    }

    /**
     * 传输层失败：包成普通 IllegalStateException（异常细节只进日志），不得伪装成 McpToolException
     */
    @Test
    void shouldWrapTransportFailureWithoutLeakingDetails() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        RagPublicApi dead = new RagPublicApiClient("http://127.0.0.1:" + deadPort);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> dead.searchNews("x", null, null, null, 1, 5));
        assertInstanceOf(IllegalStateException.class, ex);
    }

    private static RagPublicApi client() {
        return new RagPublicApiClient(baseUrl);
    }

    private static int findDeadPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 临时替换指定路径的应答（用毕 restoreDefaultHandler 还原，不污染同路径其他测试）
     */
    private static void swapHandler(String path, int status, String body) {
        server.removeContext(path);
        server.createContext(path, exchange -> respond(exchange, status, body));
    }

    private static void restoreDefaultHandler(String path) {
        server.removeContext(path);
        server.createContext(path, "/public/news/mcp-search".equals(path)
                ? defaultNewsHandler : defaultKeyDatesHandler);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private static Map<String, String> queryMap(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), null);
            } else {
                params.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }
}
