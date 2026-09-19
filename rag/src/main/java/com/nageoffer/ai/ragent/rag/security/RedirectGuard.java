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

import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

/**
 * 出站抓取的重定向守卫（O2/M4）：关闭客户端自动跟随，手动逐跳跟随并对每个
 * 跳转目标复用 {@link IngestionUrlGuard#validateOutboundTarget} 复校
 * （scheme 仍限 http/https、内网/元数据地址拒绝）——公网 URL 302 跳内网 ES
 * 或云元数据的旁路在此收口；跳数上限防重定向环挂死任务。
 *
 * <p>消费者：文档抓取链（HttpClientHelper → HttpUrlFetcher/RemoteFileFetcher/
 * FeishuFetcher）与资讯抓取链（NewsHttpFetchClient）。初始 URL 的入口校验
 * （IngestionSourceValidationAdvice）不变；本类只管「跟随过程中冒出来的新地址」。
 *
 * <p>注意 OkHttp 陷阱：http↔https 协议跳转由 followSslRedirects 独立控制，
 * 默认 true——只关 followRedirects 关不住协议跳转，须两开关同关。
 * 每次调用以 newBuilder 派生（连接池/调度器共享，零复制成本），调用方传入的
 * 原客户端配置不受影响。
 */
@Component
@RequiredArgsConstructor
public class RedirectGuard {

    /**
     * 重定向跳数上限（SPEC 口径：3）
     */
    static final int MAX_REDIRECTS = 3;

    private static final Set<Integer> REDIRECT_CODES = Set.of(300, 301, 302, 303, 307, 308);

    private final IngestionUrlGuard ingestionUrlGuard;

    /**
     * 执行请求并手动跟随重定向（≤{@value #MAX_REDIRECTS} 跳，逐跳复校目标）。
     * 返回非 3xx 的最终响应（调用方负责关闭）；违例/超跳数抛 ServiceException。
     */
    public Response execute(OkHttpClient client, Request request) throws IOException {
        OkHttpClient noFollowClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        Response response = noFollowClient.newCall(request).execute();
        HttpUrl current = request.url();
        int hops = 0;
        while (REDIRECT_CODES.contains(response.code())) {
            if (hops >= MAX_REDIRECTS) {
                response.close();
                throw new ServiceException("重定向超过跳数上限 " + MAX_REDIRECTS + "，疑似重定向环: " + current);
            }
            String location = response.header("Location");
            response.close();
            HttpUrl target = resolveRedirectTarget(current, location);
            ingestionUrlGuard.validateOutboundTarget(target.toString());
            hops++;
            current = target;
            response = noFollowClient.newCall(request.newBuilder().url(target).build()).execute();
        }
        return response;
    }

    private HttpUrl resolveRedirectTarget(HttpUrl base, String location) {
        if (location == null || location.isBlank()) {
            throw new ServiceException("重定向响应缺少 Location 头: " + base);
        }
        HttpUrl target = base.resolve(location);
        if (target == null) {
            throw new ServiceException("重定向 Location 无法解析: " + location);
        }
        return target;
    }
}
