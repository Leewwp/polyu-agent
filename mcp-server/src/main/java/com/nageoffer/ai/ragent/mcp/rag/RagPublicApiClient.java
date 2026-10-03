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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

/**
 * {@link RagPublicApi} 的 RestClient 实现：mcp-server 对 rag 的唯一数据通道。
 *
 * <p>超时（连接 3s / 读 8s）：工具调用在 Agent 同步链路上，rag 不可达时必须快速失败
 * 成结构化错误，而不是挂住对话。业务失败（信封 code 非 0）的 message 是 rag 侧
 * 面向用户的文案（如「主题不存在」），包成 {@link McpToolException} 原样给模型；
 * 传输层异常的原文只进日志——里面的连接串/超栈交给模型只会变成转述噪音。
 */
@Slf4j
@Component
public class RagPublicApiClient implements RagPublicApi {

    private static final ParameterizedTypeReference<Envelope<NewsPage>> NEWS_PAGE =
            new ParameterizedTypeReference<>() {
            };

    private static final ParameterizedTypeReference<Envelope<KeyDateBoard>> KEY_DATE_BOARD =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient restClient;

    public RagPublicApiClient(RestClient.Builder builder,
                              @Value("${ragent.rag.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(8));
        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    @Override
    public NewsPage searchNews(String keyword, String topic, LocalDate dateFrom, LocalDate dateTo,
                               int page, int size) {
        Envelope<NewsPage> envelope = get(NEWS_PAGE, spec -> spec
                .uri(uriBuilder -> uriBuilder.path("/public/news/mcp-search")
                        .queryParamIfPresent("q", Optional.ofNullable(keyword))
                        .queryParamIfPresent("topic", Optional.ofNullable(topic))
                        .queryParamIfPresent("from", Optional.ofNullable(dateFrom).map(Object::toString))
                        .queryParamIfPresent("to", Optional.ofNullable(dateTo).map(Object::toString))
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build()));
        return envelope.data();
    }

    @Override
    public KeyDateBoard keyDateBoard() {
        Envelope<KeyDateBoard> envelope = get(KEY_DATE_BOARD, spec -> spec
                .uri("/public/calendar/key-dates"));
        return envelope.data();
    }

    /**
     * 统一取数出口：HTTP 200 才解信封；404=功能开关关闭/未部署（可给模型的已知形态），
     * 其余传输层异常转译前只留日志
     */
    private <T> Envelope<T> get(ParameterizedTypeReference<Envelope<T>> type, GetSpecCustomizer customizer) {
        Envelope<T> envelope;
        try {
            envelope = customizer.customize(restClient.get()).retrieve().body(type);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new McpToolException("查询的服务当前未开放（功能开关关闭或未部署），请告知用户该功能暂不可用");
            }
            log.error("rag 公开查询面调用失败, status={}, body={}", e.getStatusCode().value(),
                    e.getResponseBodyAsString(), e);
            throw new IllegalStateException("rag 查询面 HTTP " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            log.error("rag 公开查询面不可达", e);
            throw new IllegalStateException("rag 查询面不可达", e);
        }
        if (envelope == null) {
            throw new IllegalStateException("rag 查询面返回空信封");
        }
        if (!envelope.ok()) {
            // 业务失败：message 按 rag 侧约定是面向用户的文案，可原样透传
            throw new McpToolException(envelope.message() == null ? "查询失败" : envelope.message());
        }
        return envelope;
    }

    @FunctionalInterface
    private interface GetSpecCustomizer {

        RestClient.RequestHeadersSpec<?> customize(RestClient.RequestHeadersUriSpec<?> spec);
    }
}
