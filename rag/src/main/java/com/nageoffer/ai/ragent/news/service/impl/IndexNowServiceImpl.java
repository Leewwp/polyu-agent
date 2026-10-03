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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.service.IndexNowService;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link IndexNowService} 实现：OkHttp POST（守卫式客户端——外网出站统一经
 * {@code guardedHttpClient}，GuardedDns 连接级复校，与资讯抓取同纪律）。
 *
 * <p>退避状态为进程内单键（volatile 时间戳）：IndexNow 无账号体系、无会话语义，
 * 429 的正确响应是降频而非精确记账；24h 抑制窗对齐日报日更节奏。重启即重置
 * （下次自然触发点重试，无害——IndexNow 幂等受理重复提交）。
 */
@Slf4j
@Service
public class IndexNowServiceImpl implements IndexNowService {

    /**
     * 429 退避窗（毫秒）=24h：日更内容的最小合理重试节奏
     */
    static final long BACKOFF_MS = 24L * 60 * 60 * 1000;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final NewsFetchProperties properties;
    private final ObjectMapper objectMapper;
    private final Supplier<Long> clock;

    /**
     * 429 退避截止时间戳（epoch ms；0=无退避）
     */
    private volatile long backoffUntilMs = 0L;

    @Autowired
    public IndexNowServiceImpl(@Qualifier("guardedHttpClient") OkHttpClient httpClient,
                               NewsFetchProperties properties) {
        this(httpClient, properties, new ObjectMapper(), System::currentTimeMillis);
    }

    IndexNowServiceImpl(OkHttpClient httpClient,
                        NewsFetchProperties properties,
                        ObjectMapper objectMapper,
                        Supplier<Long> clock) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void submitSiteUrls(List<String> sitePaths) {
        if (!properties.isIndexnowEnabled()) {
            log.debug("[news][indexnow] 开关关闭，跳过提交");
            return;
        }
        String key = properties.effectiveIndexnowKey();
        if (key.isEmpty()) {
            log.warn("[news][indexnow] 未配置 key（rag.news.indexnow-key），跳过提交");
            return;
        }
        if (backoffUntilMs > clock.get()) {
            log.info("[news][indexnow] 429 退避窗内（余 {}s），跳过本次提交",
                    (backoffUntilMs - clock.get()) / 1000);
            return;
        }
        String site = properties.effectiveSiteBaseUrl();
        List<String> urls = toCanonicalUrls(site, sitePaths);
        if (urls.isEmpty()) {
            log.debug("[news][indexnow] 无有效本站 URL，跳过提交");
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("host", hostOf(site));
        body.put("key", key);
        body.put("keyLocation", site + "/" + key + ".txt");
        body.put("urlList", urls);
        try {
            Request request = new Request.Builder()
                    .url(properties.effectiveIndexnowEndpoint())
                    .post(RequestBody.create(objectMapper.writeValueAsString(body), JSON))
                    .build();
            try (Response response = httpClient.newCall(request).execute()) {
                int code = response.code();
                if (code == 200 || code == 202) {
                    log.info("[news][indexnow] 提交受理（HTTP {}，{} 条本站 URL）", code, urls.size());
                } else if (code == 429) {
                    backoffUntilMs = clock.get() + BACKOFF_MS;
                    log.warn("[news][indexnow] 429 限流：进入 24h 退避窗（{} 条未受理）", urls.size());
                } else {
                    log.warn("[news][indexnow] 提交未受理（HTTP {}，{} 条）——下一自然触发点重试",
                            code, urls.size());
                }
            }
        } catch (IOException e) {
            log.warn("[news][indexnow] 提交传输失败（{}）——不影响主流程，下一自然触发点重试",
                    e.getMessage());
        }
    }

    /**
     * canonical 白名单：相对路径拼站点前缀；绝对 URL 仅当匹配本站前缀放行——
     * 外部新闻原文 URL 在此被结构性丢弃（票面红线）
     */
    private static List<String> toCanonicalUrls(String site, List<String> sitePaths) {
        List<String> urls = new ArrayList<>();
        if (sitePaths == null) {
            return urls;
        }
        for (String path : sitePaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            String trimmed = path.strip();
            String absolute = trimmed.startsWith("http://") || trimmed.startsWith("https://")
                    ? trimmed : site + (trimmed.startsWith("/") ? trimmed : "/" + trimmed);
            if (!absolute.startsWith(site + "/") && !absolute.equals(site)) {
                log.warn("[news][indexnow] 丢弃非本站 URL（红线：不提交外部原文）：{}", trimmed);
                continue;
            }
            urls.add(absolute);
        }
        return urls;
    }

    private static String hostOf(String site) {
        String rest = site.startsWith("https://") ? site.substring("https://".length())
                : site.startsWith("http://") ? site.substring("http://".length()) : site;
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }
}
