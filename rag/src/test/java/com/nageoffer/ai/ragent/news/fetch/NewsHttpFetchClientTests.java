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

package com.nageoffer.ai.ragent.news.fetch;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * 抓取纪律测试：UA 标识、robots 门（Disallow 拒绝/4xx 允许）、
 * 同 host ≥10s 节拍、瞬时错误（5xx）重试 1 次、永久错误（404）不重试。
 * 纪律合同移植自 scripts/crawl/fetch_sources.py（其 tests/ 的语义等价移植）。
 */
class NewsHttpFetchClientTests {

    private MockWebServer server;
    private FakeSleeper sleeper;
    private FakeClock clock;
    private NewsHttpFetchClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        clock = new FakeClock();
        sleeper = new FakeSleeper(clock);
        client = new NewsHttpFetchClient(new OkHttpClient(), "polyuguide-feed/1.0 (+https://polyuguide.com)",
                sleeper, clock);
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    private String url(String path) {
        return server.url(path).toString();
    }

    @Test
    void sendsConfiguredUserAgentOnRobotsAndContentRequests() throws Exception {
        server.enqueue(body(""));          // robots（无 Disallow 规则体）
        server.enqueue(body("<html>x</html>"));

        byte[] body = client.get(url("/media/media-releases/"));

        assertEquals("<html>x</html>", new String(body));
        RecordedRequest robots = server.takeRequest(2, TimeUnit.SECONDS);
        RecordedRequest content = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNullAll(robots, content);
        assertEquals("polyuguide-feed/1.0 (+https://polyuguide.com)", robots.getHeaders().get("User-Agent"));
        assertEquals("polyuguide-feed/1.0 (+https://polyuguide.com)", content.getHeaders().get("User-Agent"));
    }

    @Test
    void robotsDisallowBlocksFetchWithoutContentRequest() throws Exception {
        server.enqueue(body("""
                User-agent: *
                Disallow: /media/
                """));

        NewsFetchException ex = assertThrows(NewsFetchException.class,
                () -> client.get(url("/media/media-releases/")));
        assertFalse(ex.isTransientError());
        // 只有 robots 请求，无内容请求
        assertFalse(server.takeRequest(2, TimeUnit.SECONDS) == null);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void robots404MeansAllowAll() {
        server.enqueue(code(404));
        server.enqueue(body("<html>ok</html>"));

        assertDoesNotThrow(() -> client.get(url("/anything/")));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void transient500RetriesExactlyOnceThenSucceeds() throws Exception {
        server.enqueue(body(""));               // robots
        server.enqueue(code(500));              // 第一次内容抓取
        server.enqueue(body("<html>ok</html>"));

        byte[] body = client.get(url("/recent-focus/"));

        assertEquals("<html>ok</html>", new String(body));
        assertEquals(3, server.getRequestCount());  // robots + 失败尝试 + 重试
        // 重试前等待了 30s（RETRY_DELAY）
        assertTrue(sleeper.waits.stream().anyMatch(w -> w >= 30_000L),
                "重试等待应 ≥30s，实际：" + sleeper.waits);
    }

    @Test
    void transient500TwiceFailsWithTransientFlag() throws Exception {
        server.enqueue(body(""));               // robots
        server.enqueue(code(500));
        server.enqueue(code(503));

        NewsFetchException ex = assertThrows(NewsFetchException.class, () -> client.get(url("/x/")));
        assertTrue(ex.isTransientError());
        assertTrue(ex.getMessage().contains("重试 1 次后仍失败"));
        assertEquals(3, server.getRequestCount());  // robots + 两次失败尝试
    }

    @Test
    void permanent404DoesNotRetry() throws Exception {
        server.enqueue(code(404));              // robots 404 → 允许全部
        server.enqueue(code(404));              // 内容 404 → 永久错误

        NewsFetchException ex = assertThrows(NewsFetchException.class, () -> client.get(url("/gone/")));
        assertFalse(ex.isTransientError());
        assertEquals(2, server.getRequestCount());  // robots + 内容，无重试
        assertEquals(0, sleeper.waits.stream().filter(w -> w >= 30_000L).count());
    }

    @Test
    void sameHostRequestsArePacedAtLeastTenSeconds() throws Exception {
        server.enqueue(body(""));   // robots
        server.enqueue(body("a"));  // 第一次内容
        server.enqueue(body("b"));  // 第二次内容（不同 path 同 host）

        client.get(url("/media/media-releases/"));
        client.get(url("/media/campus-reports/"));

        // robots→内容1 与 内容1→内容2 两次间隔都应补足 ≥10s
        assertEquals(2, sleeper.waits.stream().filter(w -> w >= 9_999L).count(),
                "同 host 相邻请求应补足 ≥10s 间隔，实际等待序列：" + sleeper.waits);
    }

    @Test
    void crawlDelayRaisesIntervalBeyondDefault() throws Exception {
        server.enqueue(body("""
                User-agent: *
                Crawl-delay: 20
                Disallow:
                """));
        server.enqueue(body("a"));
        server.enqueue(body("b"));

        client.get(url("/p1/"));
        client.get(url("/p2/"));

        assertTrue(sleeper.waits.stream().anyMatch(w -> w >= 19_999L),
                "Crawl-delay 20s 应抬高间隔，实际：" + sleeper.waits);
    }

    private static MockResponse body(String body) {
        return new MockResponse.Builder().body(body).build();
    }

    private static MockResponse code(int code) {
        return new MockResponse.Builder().code(code).build();
    }

    private static void assertNotNullAll(RecordedRequest... requests) {
        for (RecordedRequest request : requests) {
            assertFalse(request == null, "请求未到达");
        }
    }

    /**
     * 假 sleeper：记录每次等待并推进假时钟（等待即代表时间流逝）
     */
    private static final class FakeSleeper implements NewsHttpFetchClient.Sleeper {

        private final List<Long> waits = new ArrayList<>();
        private final FakeClock clock;

        FakeSleeper(FakeClock clock) {
            this.clock = clock;
        }

        @Override
        public void sleep(long millis) {
            waits.add(millis);
            clock.advance(millis);
        }
    }

    /**
     * 假单调时钟：受 FakeSleeper 推进（sleep 即时间前进，同一测试内不真等）
     */
    private static final class FakeClock implements NewsHttpFetchClient.MonotonicClock {

        private final AtomicLong now = new AtomicLong();

        void advance(long millis) {
            now.addAndGet(millis);
        }


        @Override
        public long nowMillis() {
            return now.get();
        }
    }
}
