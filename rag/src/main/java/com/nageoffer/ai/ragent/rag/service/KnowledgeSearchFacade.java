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

package com.nageoffer.ai.ragent.rag.service;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieval.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieval.channel.RetrievalScopeResolver;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.source.CitationContextEnricher;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索门面：Agent 模式下 rag 对外的唯一检索窄口
 * 改写 -> KB 意图解析 -> 歧义引导 -> 多通道检索 -> KB_ANSWER 合成，返回可直接引用的答案文本
 * 引用/来源装配定死不走，与 rag.citation.enabled 无关
 * 不带任何会话历史：主 Agent 已消解过指代，合成阶段也只依据本次证据
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeSearchFacade {

    private static final String EMPTY_RESULT = "未在知识库中检索到与该问题相关的内容。";

    /**
     * 来源摘录长度：只够认出文档，不搬运正文（file 型带官网下载页时放宽见 FILE_EXCERPT_CHARS）
     */
    private static final int SOURCE_EXCERPT_CHARS = 120;

    /**
     * file 型文档摘录上限（doc 32 决策一）：徽章展开即命中段落全文（安全截断 1500），
     * 全文另走官网下载页——站内不再要求用户下载 PDF 才能看来源
     */
    private static final int FILE_EXCERPT_CHARS = 1500;

    /**
     * 来源条数上限：工具块徽章是溯源入口不是检索结果页
     */
    private static final int MAX_SOURCES = 8;

    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final IntentGuidanceService guidanceService;
    private final RetrievalEngine retrievalEngine;
    private final CitationContextEnricher citationContextEnricher;
    private final RAGPromptService promptService;
    private final LLMService llmService;
    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    /**
     * 检索并合成答案，供主 Agent 的 search_knowledge 工具调用
     */
    public String search(String query) {
        return searchWithSources(query).answer();
    }

    /**
     * 检索并合成答案，同时带出结构化来源（docId 按纪律不进模型上下文，仅供前端徽章旁路消费）
     * 歧义引导与空检索路径 sources 为空
     */
    public KnowledgeSearchOutcome searchWithSources(String query) {
        // 不喂历史：主 Agent 手握完整对话，传进来的已是消解过、且被它有意收窄的查询
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(query, List.of());
        List<SubQuestionIntent> subIntents = filterKbOnly(intentResolver.resolve(rewriteResult));

        GuidanceDecision guidance = guidanceService.detectAmbiguity(
                rewriteResult.rewrittenQuestion(), subIntents);
        if (guidance.isPrompt()) {
            log.info("Agent 知识库检索命中歧义引导，跳过检索与答案合成, question={}",
                    rewriteResult.rewrittenQuestion());
            return new KnowledgeSearchOutcome(guidance.getPrompt(), List.of());
        }

        RetrievalContext retrievalCtx = retrievalEngine.retrieve(subIntents);
        if (!retrievalCtx.hasKb()) {
            return new KnowledgeSearchOutcome(EMPTY_RESULT, List.of());
        }

        // 工具不渲染角标，但内部 docId 一定要抹掉，否则会随工具结果漏进主 Agent 的可见文本
        String kbContext = citationContextEnricher.stripDocIdAnchors(retrievalCtx.getKbContext());

        PromptContext promptContext = PromptContext.builder()
                .kbContext(kbContext)
                .kbIntents(intentResolver.mergeKbIntents(subIntents))
                .eligibleIntentIds(retrievalCtx.getEligibleIntentIds())
                .build();
        List<ChatMessage> messages = promptService.buildStructuredMessages(
                promptContext, List.of(), rewriteResult.rewrittenQuestion(), rewriteResult.subQuestions(), false);

        String answer = llmService.chat(ChatRequest.builder()
                .messages(messages)
                .temperature(0D)
                .topP(1D)
                .thinking(false)
                .build());
        return new KnowledgeSearchOutcome(answer, collectSources(retrievalCtx));
    }

    /**
     * intentChunks 值展平，按 docId 去重（首见保序），首条命中 chunk 前缀作摘录，截 8 条。
     * 摘录同样抹锚点：它与 kbContext 同源，不抹就把内部 docId 换了个出口。
     * <p>
     * 来源两态（doc 32 决策一）：按文档元数据带出 sourceType+url——
     * url 型文档的 source_location 即官网原始页面（外链直跳）；
     * file 型文档的 source_location 为官网下载页（回填 SQL 维护，可空）。
     * 带 url 的 file 型摘录放宽到命中段落全文，站内不再要求下载 PDF 才能看来源。
     */
    private List<KnowledgeSearchSource> collectSources(RetrievalContext retrievalCtx) {
        Map<String, List<RetrievedChunk>> intentChunks = retrievalCtx.getIntentChunks();
        if (intentChunks == null || intentChunks.isEmpty()) {
            return List.of();
        }
        // 值=首条命中 chunk 的摘录+自带 docName（docName 双回落：文档元数据优先、chunk 自带兜底）
        Map<String, RetrievedChunk> firstChunkByDocId = new LinkedHashMap<>();
        for (List<RetrievedChunk> chunks : intentChunks.values()) {
            if (chunks == null) {
                continue;
            }
            for (RetrievedChunk chunk : chunks) {
                if (chunk == null || chunk.getDocId() == null || chunk.getDocId().isBlank()) {
                    continue;
                }
                if (firstChunkByDocId.containsKey(chunk.getDocId())) {
                    continue;
                }
                firstChunkByDocId.put(chunk.getDocId(), chunk);
                if (firstChunkByDocId.size() >= MAX_SOURCES) {
                    return buildSources(firstChunkByDocId);
                }
            }
        }
        return buildSources(firstChunkByDocId);
    }

    private List<KnowledgeSearchSource> buildSources(Map<String, RetrievedChunk> firstChunkByDocId) {
        if (firstChunkByDocId.isEmpty()) {
            return List.of();
        }
        Map<String, KnowledgeDocumentDO> docsById = new HashMap<>();
        try {
            knowledgeDocumentMapper.selectBatchIds(firstChunkByDocId.keySet())
                    .forEach(doc -> docsById.put(doc.getId(), doc));
        } catch (Exception e) {
            // 元数据富化失败不阻断来源输出：回落站内预览形态（无 url 即旧形态）
            log.warn("来源元数据富化失败，回落站内预览形态", e);
        }
        List<KnowledgeSearchSource> sources = new ArrayList<>(firstChunkByDocId.size());
        for (Map.Entry<String, RetrievedChunk> entry : firstChunkByDocId.entrySet()) {
            String docId = entry.getKey();
            RetrievedChunk chunk = entry.getValue();
            String text = chunk.getText() == null ? ""
                    : citationContextEnricher.stripDocIdAnchors(chunk.getText()).trim();
            KnowledgeDocumentDO doc = docsById.get(docId);
            String docName = doc != null && StrUtil.isNotBlank(doc.getDocName())
                    ? doc.getDocName()
                    : StrUtil.isNotBlank(chunk.getDocName()) ? chunk.getDocName() : docId;
            // 只认 http(s) 开头的 source_location：file 型未回填时该列可能是对象 key/空
            String url = doc != null && doc.getSourceLocation() != null
                    && doc.getSourceLocation().startsWith("http")
                    ? doc.getSourceLocation() : null;
            String sourceType = doc != null ? doc.getSourceType() : null;
            boolean fileWithUrl = "file".equals(sourceType) && url != null;
            int cap = fileWithUrl ? FILE_EXCERPT_CHARS : SOURCE_EXCERPT_CHARS;
            String excerpt = text.length() > cap ? text.substring(0, cap) + "…" : text;
            sources.add(new KnowledgeSearchSource(docId, docName, excerpt, sourceType, url));
        }
        return sources;
    }

    /**
     * 只保留 KB 意图：MCP 走原生工具，SYSTEM 由主 Agent 人设直接承担
     * <p>
     * 剩零意图的子问题照样往下走，不在此拦截：作用域判定只有 {@link RetrievalScopeResolver}
     * 一份，零意图在那里回落全局检索。拦在这里等于把工具调用否决两次——主 Agent 已判过一次「该查知识库」
     */
    private List<SubQuestionIntent> filterKbOnly(List<SubQuestionIntent> subIntents) {
        return subIntents.stream()
                .map(si -> new SubQuestionIntent(si.subQuestion(), NodeScoreFilters.kb(si.nodeScores())))
                .toList();
    }

    /**
     * 检索来源：docId 仅供站内原文路由，docName 是徽章标题，excerpt 是首条命中片段摘录；
     * sourceType（file/url）与 url（http 开头的 source_location）驱动前端两态渲染
     */
    public record KnowledgeSearchSource(String docId, String docName, String excerpt,
                                        String sourceType, String url) {
    }

    /**
     * 工具结果：answer 进模型上下文，sources 走旁路（stash -> 块 JSON），两者出口不同
     */
    public record KnowledgeSearchOutcome(String answer, List<KnowledgeSearchSource> sources) {
    }
}
