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

package com.nageoffer.ai.ragent.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Elasticsearch 客户端配置
 */
@Slf4j
@Configuration
@ConditionalOnClass(ElasticsearchClient.class)
@ConditionalOnProperty(name = "rag.keyword.type", havingValue = "es")
public class EsClientConfig {

    @Bean
    public ElasticsearchClient elasticsearchClient(KeywordProperties keywordProperties) {
        String uris = keywordProperties.getEs().getUris();
        URI[] hosts = Arrays.stream(StringUtils.commaDelimitedListToStringArray(uris))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(URI::create)
                .toArray(URI[]::new);

        Rest5ClientBuilder builder = Rest5Client.builder(hosts);
        String username = keywordProperties.getEs().getUsername();
        // B2（2026-11-12 升级窗）：ES 开 xpack security 后以 basic auth 直连（http 单节点内网形态，
        // TLS 见 compose 侧显式关闭）；username 留空=不挂凭据，未开 security 的栈向后兼容
        if (StringUtils.hasText(username)) {
            builder.setDefaultHeaders(new Header[]{basicAuthHeader(username, keywordProperties.getEs().getPassword())});
            log.info("Elasticsearch 关键词检索客户端已启用 basic auth, user={}", username);
        }
        Rest5Client restClient = builder.build();
        Rest5ClientTransport transport = new Rest5ClientTransport(restClient, new JacksonJsonpMapper());
        log.info("Elasticsearch 关键词检索客户端已初始化, uris={}", uris);
        return new ElasticsearchClient(transport);
    }

    /**
     * Authorization: Basic 头（RFC 7617）；包内可见供单测锚定编码口径
     */
    static Header basicAuthHeader(String username, String password) {
        String credentials = username + ":" + password;
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return new BasicHeader("Authorization", "Basic " + encoded);
    }
}
