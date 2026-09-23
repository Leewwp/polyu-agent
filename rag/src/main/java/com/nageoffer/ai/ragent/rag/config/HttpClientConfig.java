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

import com.nageoffer.ai.ragent.rag.security.GuardedDns;
import com.nageoffer.ai.ragent.rag.security.IngestionUrlGuard;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * HTTP 客户端配置类。
 *
 * <p>两个 bean 的分类语义（issue #125 收编）：{@code syncHttpClient}＝受信调用专用
 * （infra-ai 全家、LightRag 回环、WebSearchChannel 配置端点、Feishu token 面、MinerU
 * API 基址）——受信是承重分类：LightRag 默认 127.0.0.1:9621，GuardedDns 会拒绝回环，
 * 给它「补守卫」会改崩图引擎；{@code guardedHttpClient}＝承载不可信 URL 的出站抓取面
 * （URL/资讯/飞书内容/MinerU 预签名），连接层经 {@link GuardedDns} 复校解析地址。
 * 派生配方（newBuilder().dns(...)）只允许存在于本类。
 */
@Configuration
public class HttpClientConfig {

    /**
     * 流式 HTTP 客户端（Primary）：模型流式读不限时是功能语义，永不挂守卫
     */
    @Bean
    @Primary
    public OkHttpClient streamingHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(60))
                .readTimeout(Duration.ZERO)
                .callTimeout(Duration.ZERO)
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 受信同步 HTTP 客户端：内网/配置端点专用（bean 名不动，注入点零 churn）
     */
    @Bean
    public OkHttpClient syncHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(45))
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * 守卫式同步 HTTP 客户端：由 syncHttpClient 派生（共享连接池/线程池），
     * 连接层挂 {@link GuardedDns}（#101 DNS 重绑定连接级复校）。urlGuard 是无条件
     * 组件，无循环依赖
     */
    @Bean
    public OkHttpClient guardedHttpClient(IngestionUrlGuard urlGuard) {
        return syncHttpClient().newBuilder().dns(new GuardedDns(urlGuard)).build();
    }
}
