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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OkHttpClient 注入面绊网（issue #125，MapperScanPurityTest 同精神的源码扫描门）
 *
 * <p>两条规则：
 * <ol>
 *   <li><b>注入归类</b>：rag+infra-ai main 里引用 OkHttpClient 的类 ⊆ 白名单——
 *       受信=infra-ai 全家+LightRagClient（回环必须受信，GuardedDns 会拒绝 127.0.0.1，
 *       给它「补守卫」会改崩图引擎）+WebSearchChannel（配置端点）+FeishuFetcher（token 面）
 *       +MinerUClient（API 基址）；守卫=HttpClientHelper+NewsHttpFetchClient+MinerUClient
 *       （预签名面）；设施=HttpClientConfig（bean 工厂）+RedirectGuard（守卫内部 noFollow 派生）。
 *       未归类的新注入在 CI 即红——某次出站调用受不受护从此不再取决于构造器粘没粘配方。</li>
 *   <li><b>禁自建</b>：rag main 不许在 HttpClientConfig 之外 new OkHttpClient——
 *       绕过 bean 工厂即绕过守卫分类（现状零违例，面向未来）。</li>
 * </ol>
 * mcp-server（独立进程配置性 URL）与测试代码不在扫描面。
 */
class OkHttpClientInjectionTripwireTest {

    /**
     * rag main 内允许引用 OkHttpClient 的类（受信注入点 + 守卫注入点 + 设施），包路径形式
     */
    private static final Set<String> RAG_ALLOWED = Set.of(
            "com/nageoffer/ai/ragent/rag/config/HttpClientConfig.java",
            "com/nageoffer/ai/ragent/rag/security/RedirectGuard.java",
            "com/nageoffer/ai/ragent/ingestion/util/HttpClientHelper.java",
            "com/nageoffer/ai/ragent/ingestion/strategy/fetcher/FeishuFetcher.java",
            "com/nageoffer/ai/ragent/core/parser/mineru/MinerUClient.java",
            "com/nageoffer/ai/ragent/rag/core/graph/LightRagClient.java",
            "com/nageoffer/ai/ragent/rag/core/retrieval/channel/WebSearchChannel.java",
            "com/nageoffer/ai/ragent/news/fetch/NewsHttpFetchClient.java");

    @Test
    void okhttp注入点必须全部归类() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : ragMainSources()) {
            String packagePath = relativize(source);
            if (RAG_ALLOWED.contains(packagePath)) {
                continue;
            }
            if (Files.readString(source).contains("OkHttpClient")) {
                offenders.add(packagePath + "（引用 OkHttpClient 但未归类：受信请加白名单并注明端点性质，"
                        + "不可信 URL 请注入 guardedHttpClient）");
            }
        }
        assertThat(offenders)
                .as("rag main 中引用 OkHttpClient 的类必须显式归类（issue #125 注入面绊网）；"
                        + "infra-ai 全家为受信白名单不在扫描面")
                .isEmpty();
    }

    @Test
    void rag主代码禁止在HttpClientConfig之外自建OkHttpClient() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : ragMainSources()) {
            String packagePath = relativize(source);
            if (packagePath.equals("com/nageoffer/ai/ragent/rag/config/HttpClientConfig.java")) {
                continue;
            }
            if (Files.readString(source).contains("new OkHttpClient")) {
                offenders.add(packagePath + "（自建 OkHttpClient 绕过 bean 工厂的守卫分类）");
            }
        }
        assertThat(offenders)
                .as("rag main 禁自建 OkHttpClient——一律注入 sync/guardedHttpClient bean（issue #125）")
                .isEmpty();
    }

    private List<Path> ragMainSources() throws IOException {
        Path ragMain = moduleDir().resolve("src/main/java");
        assertThat(ragMain).as("rag main 源码目录必须存在（工作目录=%s）", moduleDir()).exists();
        try (Stream<Path> walk = Files.walk(ragMain)) {
            List<Path> sources = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList();
            assertThat(sources).isNotEmpty();
            return sources;
        }
    }

    private Path moduleDir() {
        return Path.of("").toAbsolutePath();
    }

    private String relativize(Path source) {
        return moduleDir().resolve("src/main/java").relativize(source.normalize()).toString();
    }
}
