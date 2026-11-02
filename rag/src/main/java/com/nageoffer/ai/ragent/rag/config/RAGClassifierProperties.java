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

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 回答前分类器守卫配置（#430）
 *
 * <p>flag 语义=<b>两链共享守卫</b>：{@code rag.classifier.enabled} 同时控制
 * Agent 链（AgentChatServiceImpl.streamChat，生产用户路径）与 RAG 链
 * （RAGChatServiceImpl.streamChat）的回答前判定，env 覆盖名 {@code RAG_CLASSIFIER_ENABLED}。</p>
 *
 * <p>施加与回退=现役 Spring/env 机制（先例 rag.news.enabled/RAG_NEWS_ENABLED）：
 * 生产施加=deploy env 加变量+滚动重启，回退=删变量重启；不经 upgrades/init_data。</p>
 */
@Data
@Configuration
@ConfigurationProperties("rag.classifier")
public class RAGClassifierProperties {

    /**
     * 回答前分类器守卫开关：默认关；开启后两链入口对开放域无关直答题确定性拒答
     */
    private Boolean enabled = false;

    /**
     * 不确定带 LLM 判定票数：1=单票（严格 prompt+fail-open，经济）；
     * 2=双票共识（不一致=放行）。选型已按 dev 集实测定案：frozen44 uncertain
     * 带占比 1/44=2.3%（占比小→双票压方差），默认维持 2；
     * 改默认值属规则框架变更须回维护者裁决
     */
    private Integer llmVotes = 2;

    /**
     * 单票 LLM 判定超时上界（毫秒）：超时=放行+WARN（fail-open，误拒优先于漏拒）；
     * 双票为串行判定，最坏等待 2×本值——延迟预算上界随 #444 预注册冻结
     */
    private Long llmTimeoutMs = 3000L;
}
