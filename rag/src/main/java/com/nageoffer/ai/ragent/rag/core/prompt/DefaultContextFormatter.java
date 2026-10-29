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

package com.nageoffer.ai.ragent.rag.core.prompt;

import com.nageoffer.ai.ragent.rag.core.prompt.PromptFenceSanitizer;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunkKey;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CONTEXT_FORMAT_PATH;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultContextFormatter implements ContextFormatter {

    private final PromptTemplateLoader templateLoader;

    /**
     * as-of 判定基准日来源（#413）：注入而非直读系统时钟，判定可固定可测；
     * 生产实现=HKT 当前日期（{@link HktAsOfDateSource}），与系统提示词 CurrentDateFact 同源
     */
    private final Supplier<LocalDate> asOfDateSource;

    private final RAGConfigProperties ragConfigProperties;

    @Override
    public String formatKbContext(List<NodeScore> kbIntents,
                                  Set<String> eligibleIntentIds,
                                  List<RetrievedChunk> rerankedChunks,
                                  int contextTopK) {
        if (CollUtil.isEmpty(rerankedChunks)) {
            return "";
        }

        List<NodeScore> eligibleIntents = eligibleIntents(kbIntents, eligibleIntentIds);
        String branch = switch (eligibleIntents.size()) {
            case 0 -> "无可用意图";
            case 1 -> "单意图";
            default -> "多意图";
        };
        log.info("检索归因 - 提示词分支: {}, 可用意图: {}",
                branch,
                eligibleIntents.stream().map(ns -> ns.getNode().getName()).toList());
        if (eligibleIntents.isEmpty()) {
            return formatChunksWithoutIntent(rerankedChunks, contextTopK);
        }
        if (eligibleIntents.size() > 1) {
            return formatMultiIntentContext(eligibleIntents, rerankedChunks, contextTopK);
        }
        return formatSingleIntentContext(eligibleIntents.get(0), rerankedChunks, contextTopK);
    }

    private List<NodeScore> eligibleIntents(List<NodeScore> kbIntents,
                                            Set<String> eligibleIntentIds) {
        if (CollUtil.isEmpty(kbIntents) || CollUtil.isEmpty(eligibleIntentIds)) {
            return List.of();
        }
        return kbIntents.stream()
                .filter(nodeScore -> nodeScore != null && nodeScore.getNode() != null)
                .filter(nodeScore -> eligibleIntentIds.contains(nodeScore.getNode().getId()))
                .toList();
    }

    /**
     * 格式化单意图上下文
     */
    private String formatSingleIntentContext(NodeScore nodeScore, List<RetrievedChunk> rerankedChunks, int topK) {
        String snippet = StrUtil.emptyIfNull(nodeScore.getNode().getPromptSnippet()).trim();
        String docBlocks = renderChunksGroupedByDoc(distinctChunks(rerankedChunks), topK);
        return renderKbSection(renderSnippetRules(snippet), docBlocks);
    }

    /**
     * 格式化多意图上下文
     */
    private String formatMultiIntentContext(List<NodeScore> kbIntents,
                                            List<RetrievedChunk> rerankedChunks,
                                            int topK) {
        // 1. 合并所有意图的回答规则
        List<String> snippets = kbIntents.stream()
                .map(ns -> ns.getNode().getPromptSnippet())
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();

        String snippetSection = "";
        if (!snippets.isEmpty()) {
            String numberedRules = IntStream.range(0, snippets.size())
                    .mapToObj(i -> (i + 1) + ". " + snippets.get(i))
                    .collect(Collectors.joining("\n"));
            snippetSection = renderSnippetRules(numberedRules);
        }

        // 2. 合并所有意图的文档片段（按 chunk id 去重，保持相关性顺序）
        List<RetrievedChunk> allChunks = distinctChunks(rerankedChunks);

        if (allChunks.isEmpty()) {
            return snippetSection;
        }

        String docBlocks = renderChunksGroupedByDoc(allChunks, topK);
        return renderKbSection(snippetSection, docBlocks);
    }

    private String formatChunksWithoutIntent(List<RetrievedChunk> rerankedChunks, int topK) {
        List<RetrievedChunk> chunks = distinctChunks(rerankedChunks);
        if (chunks.isEmpty()) {
            return "";
        }

        String docBlocks = renderChunksGroupedByDoc(chunks, topK);
        return renderKbSection("", docBlocks);
    }

    private List<RetrievedChunk> distinctChunks(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> distinct = new LinkedHashMap<>();
        chunks.forEach(chunk -> distinct.putIfAbsent(RetrievedChunkKey.of(chunk), chunk));
        return new ArrayList<>(distinct.values());
    }

    private String renderKbSection(String snippetSection, String docBlocks) {
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-section", Map.of(
                "snippet_section", snippetSection,
                "doc_blocks", docBlocks
        ));
    }

    private String renderSnippetRules(String snippet) {
        if (StrUtil.isBlank(snippet)) {
            return "";
        }
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "snippet-rules", Map.of("rules", snippet));
    }

    /**
     * 按文档聚合渲染 chunk 列表
     * <p>
     * 文档之间按相关性排序（各文档首个命中块在原列表中的顺序，即该文档最佳块的排名），
     * 文档内部按 {@code chunkIndex} 升序还原原文顺序；docId 缺失的块各自单独成组、留在原位
     */
    private String renderChunksGroupedByDoc(List<RetrievedChunk> chunks, int topK) {
        long limit = topK > 0 ? topK : Long.MAX_VALUE;
        List<RetrievedChunk> limited = chunks.stream().limit(limit).toList();
        if (limited.isEmpty()) {
            return "";
        }

        // 按 docId 分组：LinkedHashMap 保持首次出现顺序 = 文档间的相关性排序；docId 为空的块各自单独成组
        LinkedHashMap<String, List<RetrievedChunk>> groups = new LinkedHashMap<>();
        int anonymousSeq = 0;
        for (RetrievedChunk chunk : limited) {
            String key = StrUtil.isNotBlank(chunk.getDocId()) ? chunk.getDocId() : "__nodoc__" + (anonymousSeq++);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(chunk);
        }

        return groups.values().stream()
                .map(this::renderDocBlock)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 渲染单个文档块：组内按序号排序后拼接，带日期标记与内部 docId 作为锚点
     * <p>
     * 刻意不注入文档标题：标题一旦进入上下文，模型就会写出"出自《XX》"之类的归因表述，
     * 而提示词层面的禁令压不住。资料之间的区分交给 {@code ref} 编号，文档名只在前端来源列表展示
     * <p>
     * #327 日期标记：每条证据块必须带日期（有日期显日期、无日期显「日期未知」），模型据此判断条目新旧；
     * {@code data-ragent-date} 刻意放在 {@code data-ragent-doc-id} 前导位——CitationContextEnricher
     * 的锚点正则要求 docId 收尾，前导属性在抹 docId / 注入 ref 编号时原样保留，日期标记才不会两处口径
     * <p>
     * #413 as-of：开关开启时带日期证据在块首注入〔日期事实〕行——状态由 {@link AsOfFacts} 按
     * 注入的基准日确定性算好（已过期/进行中/未开始+截至声明），模型只做语言表达、不做日期推断；
     * 无日期证据维持「日期未知」降级、不注入事实行；组级日期事实歧义时同样不注入
     * （守卫口径见 {@link #renderAsOfFact}，宁缺事实行、不产虚构判定）
     */
    private String renderDocBlock(List<RetrievedChunk> group) {
        List<RetrievedChunk> ordered = group.stream()
                .sorted(Comparator.comparing(RetrievedChunk::getChunkIndex,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        String chunks = joinDocBody(ordered);
        boolean asofEnabled = Boolean.TRUE.equals(ragConfigProperties.getAsofEnabled());
        String date = sanitizeAttribute(resolveDateMarker(group, asofEnabled));
        String docId = sanitizeAttribute(resolveDocId(group));
        String asofFact = asofEnabled ? renderAsOfFact(group) : "";
        if (StrUtil.isNotBlank(docId)) {
            return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-doc-block", Map.of(
                    "date", date,
                    "doc_id", docId,
                    "asof_fact", asofFact,
                    "chunks", chunks
            ));
        }
        return templateLoader.renderSection(CONTEXT_FORMAT_PATH, "kb-doc-block-anonymous", Map.of(
                "date", date,
                "asof_fact", asofFact,
                "chunks", chunks
        ));
    }

    /**
     * 无日期证据的统一标记：组装层不猜日期，模型侧口径唯一
     */
    private static final String DATE_UNKNOWN_MARKER = "日期未知";

    /**
     * 文档块的日期标记取值：组内首见可解析 sourceDate（检索序，即证据最佳块的日期），全组无日期回落「日期未知」。
     * 同文档多块日期不一致时不合成区间、不逐块各标——文档是引用与展示的最小单位，标记也随文档一个口径
     * <p>
     * 取值过 {@link #parseDate} 校验：畸形值按无日期降级（不透传原文进标记）；LocalDate 序列化即 ISO 日，
     * 与上游 yyyy-MM-dd 口径归一
     * <p>
     * #413 as-of 开关开启时：全组无发布日期但有活动截止日期的证据回落到截止日期（条目级日期也是
     * 该证据的真实日期，如「报名截止 2026-10-20」），其语义由块内〔日期事实〕行说明（组级日期
     * 歧义时事实行守歧义不渲染，见 {@link #renderAsOfFact}——标记是展示口径、仍按首见值）；
     * 开关关闭时维持既有口径（仅发布日期，其余「日期未知」）
     */
    private String resolveDateMarker(List<RetrievedChunk> group, boolean asofEnabled) {
        LocalDate source = parseDate(firstNonBlank(group, RetrievedChunk::getSourceDate));
        if (source != null) {
            return source.toString();
        }
        if (asofEnabled) {
            LocalDate end = parseDate(firstNonBlank(group, RetrievedChunk::getActivityEndDate));
            if (end != null) {
                return end.toString();
            }
        }
        return DATE_UNKNOWN_MARKER;
    }

    /**
     * 组内首见非空值（检索序）：与日期标记同款取值口径，两族日期各自独立解析
     */
    private String firstNonBlank(List<RetrievedChunk> group, Function<RetrievedChunk, String> getter) {
        return group.stream()
                .map(getter)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    /**
     * 渲染本文档块的 as-of 事实行（含结尾换行，随块首注入）；无日期事实（双 null 或值畸形）返回空串
     * <p>
     * 畸形日期值按无日期处理（上游 #411 写入已是 yyyy-MM-dd，此处守的是读回链路的意外形态）
     * <p>
     * #411 语义守卫（组级歧义不渲染，#413）：截止日期是条目级事实、不跨条目
     * 传播——组内截止日（或发布日）取值不唯一、或事实行的双日期输入无法溯源自同一块
     * （跨块拼组合出没有任何单块持有的判定）时，单一事实行无法对整块正文成立，返回
     * 空串守歧义：宁缺事实行、不产虚构判定（同组多截止日时，首块的过期判定不得盖在
     * 含未过期截止日的正文上）。日期标记属性不受本守卫影响，仍按组首见值
     */
    private String renderAsOfFact(List<RetrievedChunk> group) {
        LocalDate source = parseDate(firstNonBlank(group, RetrievedChunk::getSourceDate));
        LocalDate end = parseDate(firstNonBlank(group, RetrievedChunk::getActivityEndDate));
        if (source == null && end == null) {
            return "";
        }
        if (groupDateFactsAmbiguous(group, source, end)) {
            return "";
        }
        String fact = AsOfFacts.factLine(asOfDateSource.get(), source, end);
        return fact == null ? "" : PromptFenceSanitizer.neutralize(fact) + "\n";
    }

    /**
     * 组级日期事实歧义判定（#411「截止=条目级不传播」语义）：
     * <ul>
     *   <li>任一日期族组内可解析取值不唯一（不同块给出不同截止日/发布日）——单一
     *       事实行只能陈述其中一个日期，对持有另一日期的正文即误导；</li>
     *   <li>双日期齐备但没有任何一块同时持有这对日期——事实行输入系 {@link #firstNonBlank}
     *       各取其一跨块拼成，该组合判定不属于任何真实条目。</li>
     * </ul>
     * 无日期块不参与投票（条目缺日期=无事实可冲突，非歧义源）
     */
    private static boolean groupDateFactsAmbiguous(List<RetrievedChunk> group, LocalDate source, LocalDate end) {
        if (distinctParsedDates(group, RetrievedChunk::getSourceDate).size() > 1
                || distinctParsedDates(group, RetrievedChunk::getActivityEndDate).size() > 1) {
            return true;
        }
        if (source != null && end != null) {
            return group.stream().noneMatch(chunk ->
                    source.equals(parseDate(chunk.getSourceDate())) && end.equals(parseDate(chunk.getActivityEndDate())));
        }
        return false;
    }

    /**
     * 组内某日期族的全部可解析取值（畸形值按无日期处理、不入集）
     */
    private static Set<LocalDate> distinctParsedDates(List<RetrievedChunk> group, Function<RetrievedChunk, String> getter) {
        return group.stream()
                .map(getter)
                .map(DefaultContextFormatter::parseDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static LocalDate parseDate(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 组内拼接文本：同文档的块按 index 排好后用换行顺次拼接
     * <p>
     * M10：chunk 原文过围栏中和——含 {@code </content>} 或伪造 {@code <rules>} 的外部抓取内容
     * 不再能逃逸 <content> 围栏改写回答行为
     */
    private String joinDocBody(List<RetrievedChunk> ordered) {
        return ordered.stream()
                .map(RetrievedChunk::getText)
                .map(StrUtil::emptyIfNull)
                .filter(text -> !text.isEmpty())
                .map(PromptFenceSanitizer::neutralize)
                .collect(Collectors.joining("\n"));
    }

    /**
     * 清洗属性值里会破坏伪标签结构的字符（引号、尖括号）
     */
    private String sanitizeAttribute(String value) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        return value.replaceAll("[\"<>]", "").trim();
    }

    private String resolveDocId(List<RetrievedChunk> group) {
        return group.stream()
                .map(RetrievedChunk::getDocId)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse("");
    }

}
