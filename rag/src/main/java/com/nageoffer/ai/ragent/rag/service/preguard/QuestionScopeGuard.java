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

import com.nageoffer.ai.ragent.rag.config.RAGClassifierProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.AnswerLanguageRules;
import com.nageoffer.ai.ragent.rag.service.preguard.QuestionScopeRules.Decision;
import com.nageoffer.ai.ragent.rag.service.preguard.QuestionScopeRules.RuleVerdict;
import com.nageoffer.ai.ragent.rag.service.preguard.ScopeJudgeClient.Vote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 回答前分类器守卫（#430）：Agent 链与 RAG 链共享的前置判定组件
 *
 * <p>混合形态：确定性规则先行（高精度拒答、默认放行），仅规则不确定带走一次轻量 LLM 判定
 * （单票经济 / 双票共识，{@code rag.classifier.llm-votes} 可配）。LLM 层 fail-open：
 * 超时/报错/解析失败/双票不一致一律放行（误拒优先于漏拒）。守卫自身任何意外异常也放行。</p>
 *
 * <p>决策日志为结构化一行（decision/reason/uncertain/lang/latencyMs/questionChars），
 * <b>不含问句全文</b>；fail-open 的放行决策打 WARN。先例=AnonymousTrialGuard（rag 模块
 * 共享守卫，两链依赖零环）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QuestionScopeGuard {

    private static final String DECISION_LOG_MARKER = "回答前分类器决策";

    private final RAGClassifierProperties properties;
    private final ScopeJudgeClient scopeJudgeClient;

    /**
     * 对用户问题作回答前判定；flag 关闭时零开销放行（不判定、不打日志）
     */
    public GuardDecision check(String question) {
        long startNanos = System.nanoTime();
        try {
            if (!Boolean.TRUE.equals(properties.getEnabled())) {
                return GuardDecision.allow("flag-off", null);
            }
            String lang = AnswerLanguageRules.detect(question);
            RuleVerdict verdict = QuestionScopeRules.evaluate(question);
            if (verdict.decision() == Decision.ALLOW) {
                logDecision("allow", verdict.reasonFamily(), false, lang, startNanos, question);
                return GuardDecision.allow(verdict.reasonFamily(), lang);
            }
            if (verdict.decision() == Decision.REJECT) {
                logDecision("reject", verdict.reasonFamily(), false, lang, startNanos, question);
                return GuardDecision.reject(verdict.reasonFamily(), lang);
            }
            return resolveUncertain(question, lang, startNanos);
        } catch (Exception e) {
            // 守卫整体 fail-open：任何意外都不能挡住用户提问
            log.error("回答前分类器守卫异常，fail-open 放行", e);
            return GuardDecision.allow("guard-error", null);
        }
    }

    /**
     * 不确定带：LLM 判定票数按配置（1=单票、2=双票共识）；串行出票，最坏等待=票数×单票超时
     */
    private GuardDecision resolveUncertain(String question, String lang, long startNanos) {
        int votes = resolveVoteCount();
        List<Vote> results = new ArrayList<>(votes);
        for (int i = 0; i < votes; i++) {
            results.add(scopeJudgeClient.vote(question));
        }
        if (results.contains(Vote.FAILED)) {
            // 超时/报错/解析失败：放行 + WARN（fail-open 契约）
            logDecision("allow", "llm-fail-open", true, lang, startNanos, question, true);
            return GuardDecision.allow("llm-fail-open", lang);
        }
        boolean allOutOfScope = results.stream().allMatch(vote -> vote == Vote.OUT_OF_SCOPE);
        if (allOutOfScope) {
            logDecision("reject", "llm-consensus", true, lang, startNanos, question);
            return GuardDecision.reject("llm-consensus", lang);
        }
        boolean allInScope = results.stream().allMatch(vote -> vote == Vote.IN_SCOPE);
        if (allInScope) {
            logDecision("allow", "llm-consensus", true, lang, startNanos, question);
            return GuardDecision.allow("llm-consensus", lang);
        }
        // 双票不一致：放行（误拒优先于漏拒）
        logDecision("allow", "llm-disagreement", true, lang, startNanos, question);
        return GuardDecision.allow("llm-disagreement", lang);
    }

    /**
     * 票数上界=2（双票共识）；超上界配置钳制到 2 并打 WARN 可见化，行为不变
     */
    private int resolveVoteCount() {
        Integer configured = properties.getLlmVotes();
        if (configured == null || configured < 1) {
            return 1;
        }
        int effective = Math.min(configured, 2);
        if (effective < configured) {
            log.warn("回答前分类器票数配置超上界已钳制: configured={}, effective={}", configured, effective);
        }
        return effective;
    }

    private void logDecision(String decision, String reason, boolean uncertain, String lang,
                             long startNanos, String question) {
        logDecision(decision, reason, uncertain, lang, startNanos, question, false);
    }

    private void logDecision(String decision, String reason, boolean uncertain, String lang,
                             long startNanos, String question, boolean warn) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        int questionChars = question == null ? 0 : question.length();
        String line = "{}: decision={}, reason={}, uncertain={}, lang={}, latencyMs={}, questionChars={}";
        if (warn) {
            log.warn(line, DECISION_LOG_MARKER, decision, reason, uncertain, lang, latencyMs, questionChars);
        } else {
            log.info(line, DECISION_LOG_MARKER, decision, reason, uncertain, lang, latencyMs, questionChars);
        }
    }
}
