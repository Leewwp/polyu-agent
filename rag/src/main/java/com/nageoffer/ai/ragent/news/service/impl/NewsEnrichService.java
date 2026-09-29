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

package com.nageoffer.ai.ragent.news.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.model.LlmBudgetExhaustedException;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsHttpFetchClient;
import com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer;
import com.nageoffer.ai.ragent.core.parser.HtmlDocumentParser;
import com.nageoffer.ai.ragent.core.parser.model.Block;
import com.nageoffer.ai.ragent.core.parser.model.HeadingBlock;
import com.nageoffer.ai.ragent.core.parser.model.HtmlTableBlock;
import com.nageoffer.ai.ragent.core.parser.model.ImageBlock;
import com.nageoffer.ai.ragent.core.parser.model.ListBlock;
import com.nageoffer.ai.ragent.core.parser.model.ParsedDocument;
import com.nageoffer.ai.ragent.core.parser.model.ParagraphBlock;
import com.nageoffer.ai.ragent.core.parser.model.TableBlock;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 资讯 LLM 补全服务：对缺摘要的已发布条目做
 * 详情抓取 → 单次 LLM 调用 → 双语标题/摘要 + 固定 8 类 + 主题标签落库。
 *
 * <p>双语策略：官网 sitemap 条目入库已就地双语，LLM 只做压缩与繁转简；
 * 单语条目（media-releases/RSS/events）由 LLM 补译另一语。YouTube 观看页
 * 无法正文抽取（JS 重页面），仅以标题生成摘要（RSS media:description 未持久化，
 * 摘要质量受限，记为已知限制）。
 *
 * <p>主题受控词表内嵌 prompt（二开纪律：提示词外置 prompt/news-summary.st）；
 * 词表命中按 slug/中英名回链 t_news_item_topic；未命中新词以 curated=false
 * 提案入库（topic_group=PROPOSED），目录接口只取 curated=true 天然不展示，
 * 人工抽检时顺带人工审（合并/转正/丢弃）。
 *
 * <p>逐条隔离：单条 LLM/抓取失败只记日志留待下轮（行保持 summary_en IS NULL），
 * 不阻断批内其余条目；批上限防长事务与 LLM 花费失控。
 */
@Slf4j
@Service
public class NewsEnrichService {

    /**
     * 提示词模板（外置，二开纪律）
     */
    static final String PROMPT_PATH = "prompt/news-summary.st";

    /**
     * 固定 8 类 + other 兜底（禁止自由标签）
     */
    static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "admission", "scholarship", "research", "campus", "event",
            "career", "exchange", "admin", "other");

    /**
     * AI 新提案主题的分组占位（目录按 curated=true 过滤，本组永不展示；转正时人工归组）
     */
    static final String PROPOSAL_GROUP = "PROPOSED";

    /**
     * 单条主题标签上限（topics[] 1–4 个）
     */
    static final int MAX_TOPICS = 4;

    /**
     * 送 LLM 的正文截断上限（字符）
     */
    static final int MAX_CONTENT_CHARS = 6000;

    private final NewsItemMapper itemMapper;
    private final NewsSourceMapper sourceMapper;
    private final NewsTopicMapper topicMapper;
    private final NewsItemTopicMapper itemTopicMapper;
    private final NewsHttpFetchClient httpFetchClient;
    private final HtmlDocumentParser htmlDocumentParser;
    private final NewsLlmBudgetService llmBudgetService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ObjectMapper objectMapper;
    private final NewsFetchProperties fetchProperties;

    @Value("${rag.news.enrich-batch:20}")
    private int enrichBatch;

    @Autowired
    public NewsEnrichService(NewsItemMapper itemMapper,
                             NewsSourceMapper sourceMapper,
                             NewsTopicMapper topicMapper,
                             NewsItemTopicMapper itemTopicMapper,
                             NewsHttpFetchClient httpFetchClient,
                             HtmlDocumentParser htmlDocumentParser,
                             NewsLlmBudgetService llmBudgetService,
                             PromptTemplateLoader promptTemplateLoader,
                             NewsFetchProperties fetchProperties) {
        this(itemMapper, sourceMapper, topicMapper, itemTopicMapper, httpFetchClient,
                htmlDocumentParser, llmBudgetService, promptTemplateLoader, new ObjectMapper(), fetchProperties);
    }

    NewsEnrichService(NewsItemMapper itemMapper,
                      NewsSourceMapper sourceMapper,
                      NewsTopicMapper topicMapper,
                      NewsItemTopicMapper itemTopicMapper,
                      NewsHttpFetchClient httpFetchClient,
                      HtmlDocumentParser htmlDocumentParser,
                      NewsLlmBudgetService llmBudgetService,
                      PromptTemplateLoader promptTemplateLoader,
                      ObjectMapper objectMapper,
                      NewsFetchProperties fetchProperties) {
        this.itemMapper = itemMapper;
        this.sourceMapper = sourceMapper;
        this.topicMapper = topicMapper;
        this.itemTopicMapper = itemTopicMapper;
        this.httpFetchClient = httpFetchClient;
        this.htmlDocumentParser = htmlDocumentParser;
        this.llmBudgetService = llmBudgetService;
        this.promptTemplateLoader = promptTemplateLoader;
        this.objectMapper = objectMapper;
        this.fetchProperties = fetchProperties;
    }

    /**
     * 补全批：处理 summary_en 为空的已发布条目（抓取入库行天然缺摘要）；
     * 返回成功补全条数，单条失败隔离留待下轮。
     * 预算耗尽（#184）非故障：当日降级=剩余条目仅入库不富化（保持
     * summary_en IS NULL），次日按配额自然补偿——待补条目常驻本查询
     */
    public int enrichPendingItems() {
        int batch = enrichBatch > 0 ? enrichBatch : 20;
        List<NewsItemDO> pending = itemMapper.selectList(Wrappers.lambdaQuery(NewsItemDO.class)
                .eq(NewsItemDO::getStatus, "published")
                .isNull(NewsItemDO::getSummaryEn)
                .last("LIMIT " + batch));
        if (pending.isEmpty()) {
            return 0;
        }
        int enriched = 0;
        int degraded = 0;
        int isolated = 0;
        for (int i = 0; i < pending.size(); i++) {
            NewsItemDO item = pending.get(i);
            try {
                enrichOne(item);
                enriched++;
            } catch (LlmBudgetExhaustedException budget) {
                // 剩余条目（含当前被否决条）当日不再发出——降级事件已由预算服务记 t_news_llm_receipt
                degraded = pending.size() - i - isolated;
                log.warn("[news][budget] 条目 {} 起资讯 LLM 预算耗尽：本批剩余 {} 条当日降级为仅入库不富化"
                        + "（次日按配额补偿，summary_en IS NULL 常驻待补查询）", item.getId(), degraded);
                break;
            } catch (Exception e) {
                isolated++;
                log.warn("[news] 条目 {} LLM 补全失败（留待下轮）：{}", item.getId(), e.getMessage());
            }
        }
        log.info("[news] LLM 补全批完成：批 {} 条，成功 {} 条，预算降级 {} 条", pending.size(), enriched, degraded);
        return enriched;
    }

    /**
     * 单条补全：详情正文（YouTube 跳过）→ 渲染外置提示词 → Tier.FAST 预算护栏内调用
     * （maxTokens 透传+请求指纹回执）→ JSON 解析 → 双语字段与分类落库 → 主题回链/提案
     */
    void enrichOne(NewsItemDO item) {
        NewsSourceDO source = item.getSourceId() == null ? null : sourceMapper.selectById(item.getSourceId());
        String platform = source != null && source.getPlatform() != null ? source.getPlatform() : "unknown";
        boolean skipContent = "youtube".equals(platform);
        String content = skipContent ? null : fetchDetailText(item.getUrl());
        String prompt = promptTemplateLoader.render(PROMPT_PATH, Map.of(
                "source_name", platform,
                "lang_raw", item.getLangRaw() == null ? "en" : item.getLangRaw(),
                "title_line", titleLine(item),
                "content_block", content == null ? "（无正文可用，仅标题）" : content,
                "topic_vocab", renderVocab()));
        int maxTokens = fetchProperties.getSummaryMaxTokens() > 0 ? fetchProperties.getSummaryMaxTokens() : 1024;
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.2D)
                .topP(0.3D)
                .thinking(false)
                .maxTokens(maxTokens)
                .build();
        NewsLlmBudgetService.LlmCall call = llmBudgetService.call(request, Tier.FAST);
        NewsSummaryPayload payload;
        try {
            payload = parsePayload(call.content());
        } catch (RuntimeException e) {
            // 无效响应回报：新鲜响应保留一次下轮复用（已付费），复用响应清除并隔离不无限复读
            call.reportInvalid();
            throw e;
        }
        // T22 分段兜底：提示词已要求分段，模型偶发输出整段单块（样本实测 1/6）时按同规则补齐
        payload = applyParagraphFallback(payload);
        applyPayload(item, payload);
        log.info("[news] 条目 {} LLM 补全成功：category={}，topics={}，reused={}",
                item.getId(), payload.category(), payload.topics(), call.reused());
    }

    /**
     * 详情正文抽取（HtmlDocumentParser 复用位——注意 Block 不保留 href，
     * 列表发现用 jsoup 直选，正文抽取走本解析器结构化输出）；
     * 双重截断（#184）：字符上限沿用 6000，再按 token 估算 ≤ rag.news.max-input-tokens
     */
    String fetchDetailText(String url) {
        byte[] body = httpFetchClient.get(url);
        ParsedDocument document = htmlDocumentParser.parseStructured(body, "text/html", Map.of());
        int maxTokens = fetchProperties.getMaxInputTokens() > 0 ? fetchProperties.getMaxInputTokens() : 4000;
        return truncateByTokenEstimate(renderPlainText(document, MAX_CONTENT_CHARS), maxTokens);
    }

    /**
     * 输入 token 估算截断（#184 输入上限合同）：CJK/全角字符按 1 token、
     * 其余按 4 字符 1 token 估算，超预算即截断——无分词器依赖的保守口径
     */
    static String truncateByTokenEstimate(String text, int maxTokens) {
        if (text == null || maxTokens <= 0) {
            return text;
        }
        long quarterBudget = (long) maxTokens * 4L;
        long quarterUsed = 0L;
        int cut = text.length();
        for (int i = 0; i < text.length(); i++) {
            quarterUsed += isFullWidth(text.charAt(i)) ? 4L : 1L;
            if (quarterUsed > quarterBudget) {
                cut = i;
                break;
            }
        }
        return cut < text.length() ? text.substring(0, cut) : text;
    }

    /**
     * 是否按 1 token 计的字符：CJK 统一表意/扩展 A/兼容表意/全角形式与 CJK 标点
     */
    static boolean isFullWidth(char c) {
        return (c >= 0x2E80 && c <= 0x9FFF)      // CJK 部首~统一表意（含假名与 CJK 标点）
                || (c >= 0xF900 && c <= 0xFAFF)  // CJK 兼容表意
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角形式
    }

    /**
     * Block → 纯文本（标题/段落取 text，列表取条目拼接，表格取表头+行拼接，
     * 图片/代码/HTML 表格跳过）；达到 maxChars 即截断
     */
    static String renderPlainText(ParsedDocument document, int maxChars) {
        if (document == null || document.blocks() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Block block : document.blocks()) {
            if (sb.length() >= maxChars) {
                break;
            }
            appendBlock(sb, block);
        }
        if (sb.length() == 0) {
            return null;
        }
        String text = sb.toString().strip();
        return text.length() > maxChars ? text.substring(0, maxChars) : text;
    }

    private static void appendBlock(StringBuilder sb, Block block) {
        if (block instanceof HeadingBlock heading) {
            appendLine(sb, heading.text());
        } else if (block instanceof ParagraphBlock paragraph) {
            appendLine(sb, paragraph.text());
        } else if (block instanceof ListBlock list) {
            appendLine(sb, String.join("；", list.items()));
        } else if (block instanceof TableBlock table) {
            appendLine(sb, String.join(" ", table.headers()));
            table.rows().forEach(row -> appendLine(sb, String.join(" ", row)));
        }
        // ImageBlock / CodeBlock / HtmlTableBlock：对摘要无文本价值，跳过
    }

    private static void appendLine(StringBuilder sb, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append('\n');
        }
        sb.append(text.strip());
    }

    /**
     * 解析 LLM 输出：剥代码围栏/前后缀后取首个 JSON 对象；非法 JSON 抛出（调用方隔离）
     */
    NewsSummaryPayload parsePayload(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("LLM 输出为空");
        }
        String text = raw.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM 输出不含 JSON 对象");
        }
        try {
            NewsSummaryPayload payload = objectMapper.readValue(
                    text.substring(start, end + 1), NewsSummaryPayload.class);
            if (payload.category() == null || payload.category().isBlank()) {
                throw new IllegalArgumentException("category 缺失");
            }
            return payload;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("LLM 输出 JSON 解析失败：" + e.getMessage(), e);
        }
    }

    /**
     * 双语字段/分类/主题落库：category 越界落 other；标题 fallback 保持已有值。
     * 顺序=先主题后条目更新：主题/提案失败时条目行保持 summary_en IS NULL，
     * 下一轮整体干净重试（避免"摘要已落、链接丢失"的半程态）
     */
    void applyPayload(NewsItemDO item, NewsSummaryPayload payload) {
        linkTopics(item.getId(), payload.topics());
        String category = payload.category() == null || !ALLOWED_CATEGORIES.contains(payload.category().strip())
                ? "other"
                : payload.category().strip();
        String titleZh = firstNonBlank(payload.title_zh(), item.getTitleZh());
        String titleEn = firstNonBlank(payload.title_en(), item.getTitleEn());
        itemMapper.update(null, Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getId, item.getId())
                .set(NewsItemDO::getTitleZh, titleZh)
                .set(NewsItemDO::getTitleEn, titleEn)
                .set(NewsItemDO::getSummaryZh, blankToNull(payload.summary_zh()))
                .set(NewsItemDO::getSummaryEn, blankToNull(payload.summary_en()))
                .set(NewsItemDO::getCategory, category));
    }

    /**
     * 主题回链：词表命中（slug/中文名/英文名忽略大小写）→ 关联行；未命中新词 →
     * curated=false 提案入库（slug 由内容哈希生成保证幂等复用）→ 关联行。
     * 替换语义（2026-09-13 定稿·重生成同轮）：先清本条目既有关联再按本次输出回链——
     * 首轮入库时无既有关联（delete 为空操作零影响），重跑/重生成时旧标签（含误标）
     * 不残留成「旧∪新」并集（09-14 实测：追加语义下 id=129 服务学习旧 ai 标签重生成后仍在）。
     */
    void linkTopics(Long itemId, List<String> requested) {
        itemTopicMapper.delete(Wrappers.lambdaQuery(NewsItemTopicDO.class).eq(NewsItemTopicDO::getItemId, itemId));
        if (requested == null || requested.isEmpty()) {
            return;
        }
        Map<String, NewsTopicDO> vocab = loadVocab();
        Set<Long> linked = new LinkedHashSet<>();
        for (String token : requested) {
            if (linked.size() >= MAX_TOPICS) {
                break;
            }
            if (token == null || token.isBlank()) {
                continue;
            }
            NewsTopicDO topic = matchVocab(vocab, token.strip());
            if (topic == null) {
                topic = createProposal(token.strip());
            }
            if (topic != null && topic.getId() != null && linked.add(topic.getId())) {
                linkIfAbsent(itemId, topic.getId());
            }
        }
    }

    private Map<String, NewsTopicDO> loadVocab() {
        Map<String, NewsTopicDO> vocab = new LinkedHashMap<>();
        for (NewsTopicDO topic : topicMapper.selectList(Wrappers.lambdaQuery(NewsTopicDO.class)
                .eq(NewsTopicDO::getStatus, "active"))) {
            if (topic.getSlug() != null) {
                vocab.put(topic.getSlug().toLowerCase(Locale.ROOT), topic);
            }
            if (topic.getNameZh() != null) {
                vocab.putIfAbsent(topic.getNameZh(), topic);
            }
            if (topic.getNameEn() != null) {
                vocab.putIfAbsent(topic.getNameEn().toLowerCase(Locale.ROOT), topic);
            }
        }
        return vocab;
    }

    private NewsTopicDO matchVocab(Map<String, NewsTopicDO> vocab, String token) {
        NewsTopicDO hit = vocab.get(token.toLowerCase(Locale.ROOT));
        return hit != null ? hit : vocab.get(token);
    }

    /**
     * 新词提案：slug=prop-<sha256 前 12 位>（同词幂等复用），curated=false 不进目录
     */
    private NewsTopicDO createProposal(String token) {
        String slug = "prop-" + NewsUrlNormalizer.sha256Hex(token).substring(0, 12);
        NewsTopicDO existing = topicMapper.selectOne(Wrappers.lambdaQuery(NewsTopicDO.class)
                .eq(NewsTopicDO::getSlug, slug).last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }
        boolean asciiOnly = token.chars().allMatch(c -> c < 128);
        NewsTopicDO proposal = NewsTopicDO.builder()
                .slug(slug)
                // name_zh 列 NOT NULL：英文新词以原文占位（curated=false 不展示，转正时人工归化）
                .nameZh(token)
                .nameEn(asciiOnly ? token : null)
                .topicGroup(PROPOSAL_GROUP)
                .curated(false)
                .status("active")
                .build();
        topicMapper.insert(proposal);
        log.info("[news] 新主题提案入库：{}（slug={}，curated=false，待晨报审）", token, slug);
        return proposal;
    }

    private void linkIfAbsent(Long itemId, Long topicId) {
        Long exists = itemTopicMapper.selectCount(Wrappers.lambdaQuery(NewsItemTopicDO.class)
                .eq(NewsItemTopicDO::getItemId, itemId)
                .eq(NewsItemTopicDO::getTopicId, topicId));
        if (exists == null || exists == 0) {
            itemTopicMapper.insert(NewsItemTopicDO.builder()
                    .itemId(itemId).topicId(topicId).build());
        }
    }

    private String renderVocab() {
        List<String> lines = new ArrayList<>();
        for (NewsTopicDO topic : topicMapper.selectList(Wrappers.lambdaQuery(NewsTopicDO.class)
                .eq(NewsTopicDO::getStatus, "active")
                .eq(NewsTopicDO::getCurated, true)
                .orderByAsc(NewsTopicDO::getTopicGroup)
                .orderByAsc(NewsTopicDO::getId))) {
            lines.add(topic.getSlug() + " | " + nullSafe(topic.getNameZh()) + " | " + nullSafe(topic.getNameEn()));
        }
        return String.join("\n", lines);
    }

    private String titleLine(NewsItemDO item) {
        if (item.getTitleEn() != null && item.getTitleZh() != null) {
            return item.getTitleEn() + " / " + item.getTitleZh();
        }
        return item.getTitleEn() != null ? item.getTitleEn()
                : (item.getTitleZh() != null ? item.getTitleZh() : item.getUrl());
    }

    private static String firstNonBlank(String candidate, String fallback) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate.strip();
        }
        return fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 分段兜底（T22）：双语摘要经 {@link NewsSummaryParagrapher#reflow}——
     * 未分段（不含换行）时插入段间空行，模型自带分段或句子过少时原样保留
     */
    static NewsSummaryPayload applyParagraphFallback(NewsSummaryPayload payload) {
        if (payload == null) {
            return null;
        }
        return new NewsSummaryPayload(payload.title_zh(), payload.title_en(),
                NewsSummaryParagrapher.reflow(payload.summary_zh()),
                NewsSummaryParagrapher.reflow(payload.summary_en()),
                payload.category(), payload.topics());
    }

    /**
     * LLM JSON 载荷（snake_case 与提示词输出约定一致，Jackson 默认字段名绑定）
     */
    record NewsSummaryPayload(String title_zh,
                              String title_en,
                              String summary_zh,
                              String summary_en,
                              String category,
                              List<String> topics) {
    }
}
