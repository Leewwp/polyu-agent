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

import com.nageoffer.ai.ragent.framework.exception.AbstractException;
import com.nageoffer.ai.ragent.rag.security.RedirectGuard;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 带抓取纪律的 HTTP GET 客户端（纪律合同，
 * 移植自 scripts/crawl/fetch_sources.py）
 *
 * <p>四条纪律在本类单点收口，四型抓取器不再各自处理：
 * <ol>
 *   <li>robots.txt：每 host 拉取一次（进程内缓存）并先校验，命中 Disallow 拒绝抓取；
 *       robots 4xx 视为允许（REP），获取失败默认允许但记录（非 strict）；</li>
 *   <li>限速：同 host 相邻请求间隔 ≥ max(robots Crawl-delay, 10s)，跨 host 不互相约束；</li>
 *   <li>重试：瞬时错误（IO/超时/5xx/408/429）等 30s 重试 1 次，再失败抛
 *       {@link NewsFetchException}；永久错误（其余 4xx）不重试直接抛；</li>
 *   <li>UA：全部请求带 rag.news.ua 配置的产品身份 UA。</li>
 * </ol>
 * 红线（CLAUDE.md 规则 6）：不绕任何登录墙/验证码/反爬——robots 拒绝即放弃该 URL。
 */
@Slf4j
@Component
public class NewsHttpFetchClient {

    /**
     * 同 host 最小间隔（秒）
     */
    static final double MIN_INTERVAL_SECONDS = 10.0;

    /**
     * 瞬时错误重试前等待（秒）
     */
    static final long RETRY_DELAY_SECONDS = 30;

    /**
     * robots.txt 拉取超时（毫秒）——robots 不应长等
     */
    static final long ROBOTS_TIMEOUT_MILLIS = 10_000;

    /**
     * robots 获取失败时的占位（允许但记录）
     */
    private static final RobotsRules ROBOTS_UNRESOLVED = RobotsRules.allowAll();

    private final OkHttpClient httpClient;
    private final RedirectGuard redirectGuard;
    private final String userAgent;
    private final Sleeper sleeper;
    private final MonotonicClock clock;

    private final Map<String, RobotsRules> robotsCache = new ConcurrentHashMap<>();
    private final Map<String, Long> lastRequestAtMillis = new ConcurrentHashMap<>();

    /**
     * Spring 装配构造器
     */
    @org.springframework.beans.factory.annotation.Autowired
    public NewsHttpFetchClient(@Qualifier("syncHttpClient") OkHttpClient httpClient,
                               RedirectGuard redirectGuard,
                               @Value("${rag.news.ua:polyuguide-feed/1.0}") String userAgent) {
        this(httpClient, redirectGuard, userAgent, millis -> Thread.sleep(millis), () -> System.nanoTime() / 1_000_000L);
    }

    /**
     * 全参构造器（测试注入守卫与假 sleeper/时钟）
     */
    NewsHttpFetchClient(OkHttpClient httpClient,
                        RedirectGuard redirectGuard,
                        String userAgent,
                        Sleeper sleeper,
                        MonotonicClock clock) {
        this.httpClient = httpClient;
        this.redirectGuard = redirectGuard;
        this.userAgent = userAgent;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /**
     * 纪律化 GET：robots 校验 → 节拍 → 抓取（瞬时重试 1 次）→ 响应体字节
     */
    public byte[] get(String url) {
        String hostKey = NewsUrlNormalizer.hostKey(url);
        RobotsRules rules = robotsFor(url, hostKey);
        double intervalSeconds = Math.max(
                rules.crawlDelaySeconds() == null ? 0 : rules.crawlDelaySeconds(),
                MIN_INTERVAL_SECONDS);

        String pathAndQuery = NewsUrlNormalizer.pathAndQuery(url);
        if (rules.disallows(pathAndQuery)) {
            throw new NewsFetchException("robots.txt Disallow: " + pathAndQuery, false);
        }

        pace(hostKey, intervalSeconds);
        try {
            return fetchOnce(url);
        } catch (NewsFetchException first) {
            if (!first.isTransientError()) {
                throw first;
            }
            log.warn("[news] 瞬时抓取失败，{}s 后重试 1 次：{} —— {}",
                    RETRY_DELAY_SECONDS, url, first.getMessage());
            try {
                sleeper.sleep(RETRY_DELAY_SECONDS * 1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new NewsFetchException("重试等待被中断", false, ie);
            }
            // 重试同样走节拍（对同 host 的第二次请求计入间隔）
            pace(hostKey, intervalSeconds);
            try {
                return fetchOnce(url);
            } catch (NewsFetchException second) {
                throw new NewsFetchException(second.getMessage() + "（重试 1 次后仍失败）",
                        second.isTransientError(), second);
            }
        }
    }

    /**
     * 单次抓取（无重试）：2xx 返回体；5xx/408/429 瞬时；其余 4xx 永久。
     * 重定向经 {@link RedirectGuard} 手动逐跳跟随并复校目标（O2/M4）——
     * 违例/超跳数按永久错误处理（重试无意义，同样的 Location 还会被拒）
     */
    private byte[] fetchOnce(String url) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .get()
                .build();
        try (Response response = redirectGuard.execute(httpClient, request)) {
            int code = response.code();
            if (code >= 200 && code < 300) {
                ResponseBody body = response.body();
                return body == null ? new byte[0] : body.bytes();
            }
            boolean transientError = (code >= 500 && code < 600) || code == 408 || code == 429;
            throw new NewsFetchException("HTTP " + code, transientError);
        } catch (IOException e) {
            throw new NewsFetchException("IO: " + e.getMessage(), true, e);
        } catch (AbstractException e) {
            throw new NewsFetchException("重定向拦截: " + e.errorMessage, false, e);
        }
    }

    /**
     * robots 规则（缓存；robots 请求本身也计入该 host 节拍）
     */
    private RobotsRules robotsFor(String url, String hostKey) {
        RobotsRules cached = robotsCache.get(hostKey);
        if (cached != null) {
            return cached;
        }
        pace(hostKey, MIN_INTERVAL_SECONDS);
        RobotsRules resolved = fetchRobots(NewsUrlNormalizer.robotsUrl(url));
        robotsCache.put(hostKey, resolved);
        return resolved;
    }

    /**
     * robots.txt 拉取：2xx 解析；4xx 允许全部（REP）；其他失败允许但记录（非 strict）。
     * 不自动跟随重定向（O2/M4）——3xx 落「其他失败」按允许处理，绝不因 robots 跳转多发一次
     * 未复校的出站请求；真正的逐跳复校发生在内容抓取的 RedirectGuard 里
     */
    private RobotsRules fetchRobots(String robotsUrl) {
        if (robotsUrl == null) {
            return ROBOTS_UNRESOLVED;
        }
        Request request = new Request.Builder()
                .url(robotsUrl)
                .header("User-Agent", userAgent)
                .get()
                .build();
        try (Response response = httpClient.newBuilder()
                .callTimeout(java.time.Duration.ofMillis(ROBOTS_TIMEOUT_MILLIS))
                .readTimeout(java.time.Duration.ofMillis(ROBOTS_TIMEOUT_MILLIS))
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
                .newCall(request).execute()) {
            int code = response.code();
            if (code >= 200 && code < 300) {
                ResponseBody body = response.body();
                String text = body == null ? "" : new String(body.bytes(), java.nio.charset.StandardCharsets.UTF_8);
                return RobotsTxtParser.parse(text, userAgent);
            }
            if (code >= 400 && code < 500) {
                return RobotsRules.allowAll();
            }
            log.warn("[news] robots.txt 拉取异常 HTTP {}（默认允许并记录）：{}", code, robotsUrl);
            return ROBOTS_UNRESOLVED;
        } catch (IOException e) {
            log.warn("[news] robots.txt 拉取失败（默认允许并记录）：{} —— {}", robotsUrl, e.getMessage());
            return ROBOTS_UNRESOLVED;
        }
    }

    /**
     * 同 host 节拍：距上次请求不足间隔则等待
     */
    private void pace(String hostKey, double intervalSeconds) {
        long intervalMillis = (long) Math.ceil(intervalSeconds * 1000);
        long now = clock.nowMillis();
        Long last = lastRequestAtMillis.get(hostKey);
        if (last != null) {
            long remaining = intervalMillis - (now - last);
            if (remaining > 0) {
                try {
                    sleeper.sleep(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new NewsFetchException("限速等待被中断", false, e);
                }
            }
        }
        lastRequestAtMillis.put(hostKey, clock.nowMillis());
    }

    /**
     * 可注入的等待（测试用假实现记录等待而不真正睡）
     */
    @FunctionalInterface
    interface Sleeper {

        void sleep(long millis) throws InterruptedException;
    }

    /**
     * 单调时钟（毫秒；测试用可推进的假时钟）
     */
    @FunctionalInterface
    interface MonotonicClock {

        long nowMillis();
    }
}
