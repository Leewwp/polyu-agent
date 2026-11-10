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

package com.nageoffer.ai.ragent.rag.service.preguard;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.config.RAGClassifierProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 不确定带的轻量 LLM 判定默认实现：快速档一次问答，严格 YES/NO 输出，
 * 超时/报错/解析失败一律 FAILED（由守卫按 fail-open 放行）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmScopeJudgeClient implements ScopeJudgeClient {

    /**
     * 判定系统提示：范围=PolyU 校园/机构/学生生活语境；输出仅 YES/NO 单词。
     * 不含任何探针样例，判定口径与规则层语义一致
     */
    private static final String JUDGE_SYSTEM_PROMPT = """
            You are a scope classifier for the campus Q&A system of The Hong Kong Polytechnic University (PolyU).

            Decide whether the user's question is IN SCOPE or OUT OF SCOPE for this system.

            IN SCOPE means: anything about PolyU itself, or typical university campus life and student \
            services (e.g. tuition, visas, course add/drop, hostels, the library, campus cards, exams, \
            credits, graduation, campus facilities), even when the university is not named explicitly. \
            Greetings, chitchat, and follow-up questions in an ongoing campus conversation are IN SCOPE.

            OUT OF SCOPE means: questions answerable only from general world knowledge (e.g. famous people, \
            works, geography, history, science, news, cooking, entertainment, general coding) with no campus \
            or PolyU connection.

            Answer with exactly one word on a single line: YES if IN SCOPE, NO if OUT OF SCOPE. \
            When unsure, answer YES.""";

    private final LLMService llmService;
    private final RAGClassifierProperties properties;

    private static final java.util.regex.Pattern YES_TOKEN = java.util.regex.Pattern.compile("\\bYES\\b");
    private static final java.util.regex.Pattern NO_TOKEN = java.util.regex.Pattern.compile("\\bNO\\b");

    // 有界固定池：LLM 慢时线程数不随并发判定数无限增长（cached 池的风险）；池满后排队的
    // 任务由调用方 future.get(timeout) 超时取消，按既有 FAILED fail-open 语义放行
    private final ExecutorService judgeExecutor = Executors.newFixedThreadPool(8, new ThreadFactory());

    @Override
    public Vote vote(String question) {
        long timeoutMs = properties.getLlmTimeoutMs() != null ? properties.getLlmTimeoutMs() : 3000L;
        Future<String> future = judgeExecutor.submit(() -> llmService.chat(buildRequest(question), Tier.FAST));
        try {
            String raw = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            return parse(raw);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("范围判定票被中断，按 fail-open 处理", e);
            return Vote.FAILED;
        } catch (Exception e) {
            future.cancel(true);
            log.warn("范围判定票失败（超时/报错），按 fail-open 处理", e);
            return Vote.FAILED;
        }
    }

    private ChatRequest buildRequest(String question) {
        return ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(JUDGE_SYSTEM_PROMPT),
                        ChatMessage.user(question)))
                .temperature(0.0)
                .build();
    }

    /**
     * 解析判定输出：剥围栏后按词边界取首个 YES/NO；两者都出现或都不出现视为解析失败
     */
    static Vote parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Vote.FAILED;
        }
        String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw).toUpperCase(java.util.Locale.ROOT);
        java.util.regex.Matcher yes = YES_TOKEN.matcher(cleaned);
        java.util.regex.Matcher no = NO_TOKEN.matcher(cleaned);
        boolean hasYes = yes.find();
        boolean hasNo = no.find();
        if (hasYes && !hasNo) {
            return Vote.IN_SCOPE;
        }
        if (hasNo && !hasYes) {
            return Vote.OUT_OF_SCOPE;
        }
        if (hasYes && hasNo) {
            // 两者都出现：按首个位置定序，同位（如连写）视为解析失败
            return yes.start() < no.start() ? Vote.IN_SCOPE
                    : no.start() < yes.start() ? Vote.OUT_OF_SCOPE : Vote.FAILED;
        }
        return Vote.FAILED;
    }

    @PreDestroy
    void shutdownExecutor() {
        judgeExecutor.shutdownNow();
    }

    /**
     * 守卫判定线程：守护线程不阻 JVM 退出，命名带序号便于排查
     */
    private static final class ThreadFactory implements java.util.concurrent.ThreadFactory {

        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "scope-judge-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
