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
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemTopicDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicAliasDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsTopicDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemTopicMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsTopicAliasMapper;
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 资讯 LLM 补全服务（#185：待富化选题 → 守卫 → 发布资格落库；#187：内容哈希
 * 零调用复用）：对 pending 条目做详情抓取 → <b>内容哈希复用查证</b>（#187 判重
 * 三合同之二：sha256(规范化标题+正文摘录) 相同且供体 summary_source=llm → 零调用
 * 复用双语摘要/分类/主题，两行独立保留=逐源证据；与 #184 回执边界=复用先于预算
 * 服务，跨源同内容也复用，#184 指纹只管同渲染请求）→ 单次 LLM 调用（预算护栏内）
 * → 双语标题/摘要 + 固定 8 类 + 主题标签 → <b>写作守卫</b>
 * （{@link NewsWritingGuard}，零调用）→ 过守卫即落 status=published +
 * eligible_time（发布门 180s 从此起算，查询侧统一判据）。
 *
 * <p><b>选题口径</b>（#185）：status=pending AND summary_en IS NULL，按 id 升序
 * FIFO（最老先富化——TTL 公平+确定性，重启不重排）；超龄（fetch_time 早于
 * now-pending-ttl-hours）条目不选题（终态转移归采集侧 expireOverduePending）。
 * 批上限 rag.news.enrich-batch（默认 20）×3 轮/日与全站日准入 60 匹配。
 *
 * <p><b>守卫拒绝与零调用回退</b>（#185）：守卫拒绝的新鲜响应回报无效（回执
 * 保留一次免费复用）后本轮隔离留 pending；复用响应再拒或回执终态
 * （POISONED 隔离/重试预算耗尽）→ <b>明示零调用回退</b>（标题派生双语摘要，
 * summary_source=fallback）——可解释、可审计、不触发无限付费重试（衔接 #184
 * 预算护栏 DEGRADED 路径：预算延期不回退，留 pending 等 TTL 或次日配额）。
 *
 * <p>双语策略：官网 sitemap 条目入库已就地双语，LLM 只做压缩与繁转简；
 * 单语条目（media-releases/RSS/events）由 LLM 补译另一语。YouTube 观看页
 * 无法正文抽取（JS 重页面），仅以标题生成摘要（RSS media:description 未持久化，
 * 摘要质量受限，记为已知限制）。
 *
 * <p>主题受控词表内嵌 prompt（二开纪律：提示词外置 prompt/news-summary.st）；
 * 词表命中按 slug/中英名回链 t_news_item_topic；未命中先过<b>别名账拦截</b>
 * （#202：merged 别名回链目标 curated 主题、rejected 别名跳过，已治理名称
 * 不再进提案面——防再提），仍未命中才以 curated=false 提案入库
 * （topic_group=PROPOSED），目录接口只取 curated=true 天然不展示，处置走
 * #202 三轨治理（merge/promote/reject，admin 批量审核端点）。
 *
 * <p>逐条隔离：单条 LLM/抓取失败只记日志留待下轮（行保持 summary_en IS NULL），
 * 不阻断批内其余条目；批上限防长事务与 LLM 花费失控。
 *
 * <p><b>提示词版本可追溯</b>（#185）：prompt_version=sha256(模板全文) 前 12 位，
 * 随每次富化/回退落行——改词即版本变化，只影响新资料，历史行不自动重算。
 *
 * <p><b>输出上限口径限制</b>（#184 修正点5，2026-09-29）：maxTokens=
 * rag.news.summary-max-tokens（默认 1024）为<b>可调配置</b>。字符/4 估算口径
 * <b>不构成 qwen 系真实分词的硬上界证明</b>——未提供适用模型真实分词或生成
 * token 证据前，不认定 1024 必然容纳双语长度合同的极限载荷；若模型输出被
 * maxTokens 截断，产生的不完整 JSON 走解析失败受控降级（不发布、不无限重试，
 * 见预算服务 POISONED/FAILED 生命周期），必要时上调该配置并同步成本模型
 * （预算服务的输出限额取同一配置推导）。
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
    /**
     * 别名账 Mapper（#202 防再提）：提案消费点词表未命中后、建提案行前查一次——
     * 命中 merged 别名回链目标 curated 主题、命中 rejected 别名跳过（不建行不挂关联）。
     * 测试便捷构造器传 null=零拦截（行为与 #202 前完全一致）
     */
    private final NewsTopicAliasMapper aliasMapper;
    private final NewsHttpFetchClient httpFetchClient;
    private final HtmlDocumentParser htmlDocumentParser;
    private final NewsLlmBudgetService llmBudgetService;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ObjectMapper objectMapper;
    private final NewsFetchProperties fetchProperties;
    private final Supplier<Date> nowSupplier;

    /**
     * 提示词模板版本缓存（进程内；模板随部署变更，加载器自身亦有缓存）
     */
    private volatile String cachedPromptVersion;

    @Value("${rag.news.enrich-batch:20}")
    private int enrichBatch;

    @Autowired
    public NewsEnrichService(NewsItemMapper itemMapper,
                             NewsSourceMapper sourceMapper,
                             NewsTopicMapper topicMapper,
                             NewsItemTopicMapper itemTopicMapper,
                             NewsTopicAliasMapper aliasMapper,
                             NewsHttpFetchClient httpFetchClient,
                             HtmlDocumentParser htmlDocumentParser,
                             NewsLlmBudgetService llmBudgetService,
                             PromptTemplateLoader promptTemplateLoader,
                             NewsFetchProperties fetchProperties) {
        this(itemMapper, sourceMapper, topicMapper, itemTopicMapper, aliasMapper, httpFetchClient,
                htmlDocumentParser, llmBudgetService, promptTemplateLoader, new ObjectMapper(), fetchProperties, Date::new);
    }

    /**
     * 测试便捷构造器（无别名账=零拦截，行为与 #202 前一致）
     */
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
        this(itemMapper, sourceMapper, topicMapper, itemTopicMapper, null, httpFetchClient,
                htmlDocumentParser, llmBudgetService, promptTemplateLoader, objectMapper, fetchProperties, Date::new);
    }

    /**
     * 测试便捷构造器（注入时钟，无别名账）
     */
    NewsEnrichService(NewsItemMapper itemMapper,
                      NewsSourceMapper sourceMapper,
                      NewsTopicMapper topicMapper,
                      NewsItemTopicMapper itemTopicMapper,
                      NewsHttpFetchClient httpFetchClient,
                      HtmlDocumentParser htmlDocumentParser,
                      NewsLlmBudgetService llmBudgetService,
                      PromptTemplateLoader promptTemplateLoader,
                      ObjectMapper objectMapper,
                      NewsFetchProperties fetchProperties,
                      Supplier<Date> nowSupplier) {
        this(itemMapper, sourceMapper, topicMapper, itemTopicMapper, null, httpFetchClient,
                htmlDocumentParser, llmBudgetService, promptTemplateLoader, objectMapper, fetchProperties, nowSupplier);
    }

    /**
     * 全参构造器（测试注入时钟+别名账——#202 拦截路径的时间旅行与拦截桩）
     */
    NewsEnrichService(NewsItemMapper itemMapper,
                      NewsSourceMapper sourceMapper,
                      NewsTopicMapper topicMapper,
                      NewsItemTopicMapper itemTopicMapper,
                      NewsTopicAliasMapper aliasMapper,
                      NewsHttpFetchClient httpFetchClient,
                      HtmlDocumentParser htmlDocumentParser,
                      NewsLlmBudgetService llmBudgetService,
                      PromptTemplateLoader promptTemplateLoader,
                      ObjectMapper objectMapper,
                      NewsFetchProperties fetchProperties,
                      Supplier<Date> nowSupplier) {
        this.itemMapper = itemMapper;
        this.sourceMapper = sourceMapper;
        this.topicMapper = topicMapper;
        this.itemTopicMapper = itemTopicMapper;
        this.aliasMapper = aliasMapper;
        this.httpFetchClient = httpFetchClient;
        this.htmlDocumentParser = htmlDocumentParser;
        this.llmBudgetService = llmBudgetService;
        this.promptTemplateLoader = promptTemplateLoader;
        this.objectMapper = objectMapper;
        this.fetchProperties = fetchProperties;
        this.nowSupplier = nowSupplier;
    }

    /**
     * 补全批（#185 选题口径）：status=pending 且缺摘要、未超龄（TTL 内）的条目，
     * id 升序 FIFO 取批（默认 20）；返回 LLM 成功条数（零调用回退另计日志），
     * 单条失败隔离留待下轮。
     * 预算耗尽（#184）非故障：当日降级=剩余条目仅入库不富化（保持
     * pending+summary_en IS NULL），次日按配额自然补偿或届龄转 expired——
     * 预算延期不得绕发布门，也不走零调用回退
     */
    public int enrichPendingItems() {
        int batch = enrichBatch > 0 ? enrichBatch : 20;
        Date now = nowSupplier.get();
        Date ttlFloor = new Date(now.getTime() - fetchProperties.effectivePendingTtlHours() * 3600L * 1000L);
        List<NewsItemDO> pending = itemMapper.selectList(Wrappers.lambdaQuery(NewsItemDO.class)
                .eq(NewsItemDO::getStatus, NewsItemStatus.PENDING)
                .isNull(NewsItemDO::getSummaryEn)
                .ge(NewsItemDO::getFetchTime, ttlFloor)
                .orderByAsc(NewsItemDO::getId)
                .last("LIMIT " + batch));
        if (pending.isEmpty()) {
            return 0;
        }
        int enriched = 0;
        int degraded = 0;
        int fallback = 0;
        int isolated = 0;
        for (int i = 0; i < pending.size(); i++) {
            NewsItemDO item = pending.get(i);
            try {
                enrichOne(item);
                enriched++;
            } catch (LlmBudgetExhaustedException budget) {
                // 剩余条目（含当前被否决条）当日不再发出——降级事件已由预算服务记 t_news_llm_receipt
                degraded = pending.size() - i - isolated - fallback;
                log.warn("[news][budget] 条目 {} 起资讯 LLM 预算耗尽：本批剩余 {} 条当日降级为仅入库不富化"
                        + "（次日按配额补偿，summary_en IS NULL 常驻待补查询）", item.getId(), degraded);
                break;
            } catch (FallbackAppliedException fallbackApplied) {
                // enrichOne 内部已落明示零调用回退（守卫/回执终态），算处理完成非失败
                fallback++;
            } catch (Exception e) {
                isolated++;
                log.warn("[news] 条目 {} LLM 补全失败（留待下轮）：{}", item.getId(), e.getMessage());
            }
        }
        log.info("[news] LLM 补全批完成：批 {} 条，LLM 成功 {} 条，零调用回退 {} 条，预算降级 {} 条",
                pending.size(), enriched, fallback, degraded);
        return enriched;
    }

    /**
     * 单条补全：详情正文（YouTube 跳过）→ <b>内容哈希复用查证</b>（#187 判重三合同
     * 之二：同规范化内容已有 LLM 摘要 → 零调用复用，保留本条独立行=逐源证据）→
     * 渲染外置提示词（<b>完整渲染请求输入闭合</b>，#184 修正点4：整段 prompt 含
     * 模板+动态词表+标题+正文，超出输入限额先压缩正文再送出）→ Tier.FAST 预算
     * 护栏内调用（maxTokens 透传+请求指纹回执）→ JSON 解析 → 写作守卫（零调用）→
     * 双语字段与分类落库（published+eligible_time）→ 主题回链/提案
     *
     * <p>守卫/回执终态处理见类 javadoc「守卫拒绝与零调用回退」——回退在本方法内
     * 落库后抛 {@link FallbackAppliedException} 通知批循环（非失败、非 LLM 成功）
     */
    void enrichOne(NewsItemDO item) {
        NewsSourceDO source = item.getSourceId() == null ? null : sourceMapper.selectById(item.getSourceId());
        String platform = source != null && source.getPlatform() != null ? source.getPlatform() : "unknown";
        boolean skipContent = "youtube".equals(platform);
        String content = skipContent ? null : fetchDetailText(item.getUrl());
        String contentHash = contentHash(item, content);
        if (contentHash != null) {
            NewsItemDO donor = findReusableEnrichment(contentHash, item.getId());
            if (donor != null) {
                applyReusedPayload(item, donor, contentHash);
                log.info("[news] 条目 {} 命中内容哈希复用（#187 零调用）：donor=条目 {}，hash={}",
                        item.getId(), donor.getId(), contentHash);
                return;
            }
        }
        String titleLine = titleLine(item);
        String prompt = renderPromptWithinInputQuota(Map.of(
                "source_name", platform,
                "lang_raw", item.getLangRaw() == null ? "en" : item.getLangRaw(),
                "title_line", titleLine,
                "content_block", content == null ? "（无正文可用，仅标题）" : content,
                "topic_vocab", renderVocab()));
        int maxTokens = fetchProperties.effectiveSummaryMaxTokens();
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(ChatMessage.user(prompt)))
                .temperature(0.2D)
                .topP(0.3D)
                .thinking(false)
                .maxTokens(maxTokens)
                .build();
        NewsLlmBudgetService.LlmCall call;
        try {
            call = llmBudgetService.call(request, Tier.FAST);
        } catch (IllegalStateException terminal) {
            // 回执终态（POISONED 隔离/同指纹重试预算耗尽）：同指纹零新增请求——
            // 明示零调用回退收尾，不无限等内容变化也不无限付费重试
            applyFallback(item, "回执终态：" + terminal.getMessage());
            throw new FallbackAppliedException();
        }
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
        try {
            NewsWritingGuard.enforce(payload, titleLine, content);
        } catch (NewsWritingGuard.RejectionException rejection) {
            // 守卫拒绝同样走回执无效回报（新鲜响应保留一次免费复用；复用响应隔离）
            call.reportInvalid();
            if (call.reused()) {
                // 复用响应仍不过守卫=同指纹无望，明示零调用回退（不无限复读不无限重试）
                applyFallback(item, "守卫拒绝（复用响应）：" + rejection.getMessage());
                throw new FallbackAppliedException();
            }
            throw rejection;
        }
        applyPayload(item, payload, contentHash);
        log.info("[news] 条目 {} LLM 补全成功：category={}，topics={}，reused={}，prompt_version={}",
                item.getId(), payload.category(), payload.topics(), call.reused(), currentPromptVersion());
    }

    // ==================== 内容哈希复用（#187 判重三合同之二） ====================

    /**
     * 内容哈希=sha256(规范化双语标题 + 分隔 + 规范化正文摘录)——规范化=去全部空白
     * +拉丁小写化（标点保留参与判重：只对<b>确切重复</b>承诺复用，不承诺相似复用，
     * 不承诺节省比例）。无正文（YouTube 跳过正文/抓取失败）或标题全空返回 null——
     * 标题单独不构成确切重复证据（CLU-026：标题全同受众不同的极端负例）
     */
    static String contentHash(NewsItemDO item, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String title = ((item.getTitleZh() == null ? "" : item.getTitleZh())
                + "||" + (item.getTitleEn() == null ? "" : item.getTitleEn())).strip();
        if (title.isBlank() || title.equals("||")) {
            return null;
        }
        return NewsUrlNormalizer.sha256Hex(normalize(title) + "\n||\n" + normalize(content));
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * 复用供体查找：同 content_hash 且 summary_source='llm'（fallback=标题派生回退
     * 不作供体——复用它等于变相标题猜测）的最早一行；供体与本条各自保留独立行
     * （逐源证据：URL/信源/时间均不合并），只复用富化产物
     */
    private NewsItemDO findReusableEnrichment(String contentHash, Long selfId) {
        List<NewsItemDO> candidates = itemMapper.selectList(Wrappers.lambdaQuery(NewsItemDO.class)
                .eq(NewsItemDO::getContentHash, contentHash)
                .eq(NewsItemDO::getSummarySource, NewsItemStatus.SUMMARY_SOURCE_LLM)
                .orderByAsc(NewsItemDO::getId));
        for (NewsItemDO candidate : candidates) {
            if (!candidate.getId().equals(selfId)
                    && NewsItemStatus.SUMMARY_SOURCE_LLM.equals(candidate.getSummarySource())
                    && candidate.getSummaryZh() != null && !candidate.getSummaryZh().isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 零调用复用落库（#187）：供体的双语标题/摘要/分类/prompt_version 原样复用，
     * 主题关联随供体复制（保留本条独立行=逐源证据）；summary_source=llm（产物
     * 确为 LLM 输出）+ eligible_time=now 走正常发布门。与 #184 边界：本路径在
     * 预算服务之前返回，不产生请求指纹与回执（跨源同内容复用 #184 指纹做不到
     * ——指纹含 source_name/动态词表，同渲染请求才复用）
     */
    private void applyReusedPayload(NewsItemDO item, NewsItemDO donor, String contentHash) {
        for (NewsItemTopicDO link : itemTopicMapper.selectList(Wrappers.lambdaQuery(NewsItemTopicDO.class)
                .eq(NewsItemTopicDO::getItemId, donor.getId()))) {
            linkIfAbsent(item.getId(), link.getTopicId());
        }
        itemMapper.update(null, Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getId, item.getId())
                .eq(NewsItemDO::getStatus, NewsItemStatus.PENDING)
                .set(NewsItemDO::getTitleZh, firstNonBlank(donor.getTitleZh(), item.getTitleZh()))
                .set(NewsItemDO::getTitleEn, firstNonBlank(donor.getTitleEn(), item.getTitleEn()))
                .set(NewsItemDO::getSummaryZh, donor.getSummaryZh())
                .set(NewsItemDO::getSummaryEn, donor.getSummaryEn())
                .set(NewsItemDO::getCategory, donor.getCategory() == null ? "other" : donor.getCategory())
                .set(NewsItemDO::getStatus, NewsItemStatus.PUBLISHED)
                .set(NewsItemDO::getEligibleTime, nowSupplier.get())
                .set(NewsItemDO::getSummarySource, NewsItemStatus.SUMMARY_SOURCE_LLM)
                .set(NewsItemDO::getPromptVersion, donor.getPromptVersion())
                .set(NewsItemDO::getContentHash, contentHash));
    }

    /**
     * 明示零调用回退（#185）：守卫拒绝（复用响应）或回执终态时，以标题派生双语
     * 摘要落发布资格——summary_source=fallback 标记可解释可审计，eligible_time
     * 置 now 走正常发布门；无提示词参与（prompt_version 保持 NULL）
     */
    void applyFallback(NewsItemDO item, String reason) {
        String headlineZh = firstNonBlank(item.getTitleZh(), item.getTitleEn(), item.getUrl());
        String headlineEn = firstNonBlank(item.getTitleEn(), item.getTitleZh(), item.getUrl());
        itemMapper.update(null, Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getId, item.getId())
                .eq(NewsItemDO::getStatus, NewsItemStatus.PENDING)
                .set(NewsItemDO::getStatus, NewsItemStatus.PUBLISHED)
                .set(NewsItemDO::getEligibleTime, nowSupplier.get())
                .set(NewsItemDO::getSummarySource, NewsItemStatus.SUMMARY_SOURCE_FALLBACK)
                .set(NewsItemDO::getSummaryZh, "原文标题（AI 摘要暂缺）：" + headlineZh)
                .set(NewsItemDO::getSummaryEn, "Source headline (AI summary unavailable): " + headlineEn));
        log.warn("[news] 条目 {} 转明示零调用回退（summary_source=fallback）：{}", item.getId(), reason);
    }

    /**
     * 详情正文抽取（HtmlDocumentParser 复用位——注意 Block 不保留 href，
     * 列表发现用 jsoup 直选，正文抽取走本解析器结构化输出）；
     * 正文先按字符 6000 与 token 估算 ≤ rag.news.max-input-tokens 双重截断，
     * 完整渲染请求的输入闭合（模板+词表+标题+正文合计 ≤ max-input-tokens+开销）
     * 由 {@link #renderPromptWithinInputQuota} 收口
     */
    String fetchDetailText(String url) {
        byte[] body = httpFetchClient.get(url);
        ParsedDocument document = htmlDocumentParser.parseStructured(body, "text/html", Map.of());
        return truncateByTokenEstimate(renderPlainText(document, MAX_CONTENT_CHARS), fetchProperties.effectiveMaxInputTokens());
    }

    /**
     * 完整渲染请求的输入闭合（#184 修正点4）：校验对象=<b>整段渲染后 prompt</b>
     * （模板指令+动态主题词表+标题行+正文），而非仅正文——超出输入限额
     * （max-input-tokens + budget-prompt-overhead-tokens，与成本上界推导同口径）时
     * 压缩正文块并重渲染，静态部分（模板+词表+标题）本身超限时拒绝付费准入。
     * token 数为估算口径（CJK/全角 1 token、其余 4 字符 1 token），不宣称分词硬上界
     */
    String renderPromptWithinInputQuota(Map<String, String> baseSlots) {
        int inputQuota = inputQuotaTokens();
        Map<String, String> slots = new LinkedHashMap<>(baseSlots);
        String prompt = promptTemplateLoader.render(PROMPT_PATH, slots);
        for (int guard = 0; estimatePromptTokens(prompt) > inputQuota && guard < 4; guard++) {
            String contentBlock = slots.get("content_block");
            long contentTokens = estimatePromptTokens(contentBlock);
            long staticTokens = estimatePromptTokens(prompt) - contentTokens;
            long contentBudget = inputQuota - staticTokens;
            if (contentBudget <= 0) {
                throw new IllegalStateException(String.format(
                        "资讯提示词静态部分（模板+主题词表+标题）超过输入限额 %d token（估算口径），拒绝付费准入", inputQuota));
            }
            slots.put("content_block", truncateByTokenEstimate(contentBlock, (int) Math.min(contentBudget, Integer.MAX_VALUE)));
            prompt = promptTemplateLoader.render(PROMPT_PATH, slots);
        }
        if (estimatePromptTokens(prompt) > inputQuota) {
            throw new IllegalStateException("资讯提示词无法压缩到输入限额内（估算口径），拒绝付费准入");
        }
        return prompt;
    }

    /**
     * 输入限额（token 估算口径）= 正文输入上限 + 提示词开销预算——单一事实源
     * {@link NewsFetchProperties#effectiveInputQuotaTokens()}（与预算服务成本上界
     * 推导共用，保证成本口径「完整输入限额被执行」成立）
     */
    private int inputQuotaTokens() {
        return fetchProperties.effectiveInputQuotaTokens();
    }

    /**
     * 完整 prompt 的 token 估算（向上取整的保守口径）：CJK/全角字符按 1 token、
     * 其余按 4 字符 1 token——<b>估算口径不构成模型真实分词的硬上界证明</b>
     * （#184 修正点5：无分词器依赖的保守预算控制，真实超限由输出侧受控降级兜底）
     */
    static long estimatePromptTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        long quarter = 0L;
        for (int i = 0; i < text.length(); i++) {
            quarter += isFullWidth(text.charAt(i)) ? 4L : 1L;
        }
        return (quarter + 3) / 4;
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
     * 双语字段/分类/主题落库（#185：过守卫即落发布资格——status=published +
     * eligible_time=now + summary_source=llm + prompt_version 随行；#187 加随行
     * content_hash 供后续确切重复零调用复用）：category 越界落 other；标题 fallback
     * 保持已有值。
     * 顺序=先主题后条目更新：主题/提案失败时条目行保持 pending+summary_en IS NULL，
     * 下一轮整体干净重试（避免"摘要已落、链接丢失"的半程态）；条目更新限定
     * status=pending（已回退/已下架的行不被覆写）
     */
    void applyPayload(NewsItemDO item, NewsSummaryPayload payload, String contentHash) {
        linkTopics(item.getId(), payload.topics());
        String category = payload.category() == null || !ALLOWED_CATEGORIES.contains(payload.category().strip())
                ? "other"
                : payload.category().strip();
        String titleZh = firstNonBlank(payload.title_zh(), item.getTitleZh());
        String titleEn = firstNonBlank(payload.title_en(), item.getTitleEn());
        itemMapper.update(null, Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getId, item.getId())
                .eq(NewsItemDO::getStatus, NewsItemStatus.PENDING)
                .set(NewsItemDO::getTitleZh, titleZh)
                .set(NewsItemDO::getTitleEn, titleEn)
                .set(NewsItemDO::getSummaryZh, blankToNull(payload.summary_zh()))
                .set(NewsItemDO::getSummaryEn, blankToNull(payload.summary_en()))
                .set(NewsItemDO::getCategory, category)
                .set(NewsItemDO::getStatus, NewsItemStatus.PUBLISHED)
                .set(NewsItemDO::getEligibleTime, nowSupplier.get())
                .set(NewsItemDO::getSummarySource, NewsItemStatus.SUMMARY_SOURCE_LLM)
                .set(NewsItemDO::getPromptVersion, currentPromptVersion())
                .set(NewsItemDO::getContentHash, contentHash));
    }

    /**
     * 提示词模板版本（#185 可追溯）：sha256(模板全文) 前 12 位，进程内缓存——
     * 改词即版本变化，只影响新资料；历史行不因版本变化自动重算
     */
    String currentPromptVersion() {
        String version = cachedPromptVersion;
        if (version == null) {
            version = NewsUrlNormalizer.sha256Hex(promptTemplateLoader.load(PROMPT_PATH)).substring(0, 12);
            cachedPromptVersion = version;
        }
        return version;
    }

    /**
     * 主题回链：词表命中（slug/中文名/英文名忽略大小写）→ 关联行；未命中 →
     * <b>别名账拦截</b>（#202 防再提，最小接线：只加消费侧查证，提案生成逻辑
     * 零改动）——命中 merged 别名直接回链目标 curated 主题（不落新行）、命中
     * rejected 别名跳过（不建提案不挂关联）；仍未命中 → curated=false 提案入库
     * （slug 由内容哈希生成保证幂等复用）→ 关联行。
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
                AliasHit alias = lookupAlias(token.strip());
                if (alias.hit()) {
                    // 命中即终局：merged→目标（不可解析时 null=跳过）；rejected→null=跳过——均不再建提案
                    topic = alias.target();
                } else {
                    topic = createProposal(token.strip());
                }
            }
            if (topic != null && topic.getId() != null && linked.add(topic.getId())) {
                linkIfAbsent(itemId, topic.getId());
            }
        }
    }

    /**
     * 别名账拦截（#202）：词表未命中的 token 查别名账（normalizeKey 同一口径）。
     * 返回 {@link AliasHit#MISS}=未命中（照旧走 createProposal）；
     * {@code hit=true}=命中（<b>终局，不再建提案行</b>）——merged 别名 target=目标
     * curated 主题（目标异常缺失/停用时保守跳过不回退建提案），rejected 别名
     * target=null（不建提案不挂关联）。hit 与 target 分离承载正是为了区分
     * 「未命中该建提案」与「命中 rejected 该跳过」两种 null。
     */
    private AliasHit lookupAlias(String token) {
        if (aliasMapper == null) {
            return AliasHit.MISS;
        }
        String key = NewsTopicAliasDO.normalizeKey(token);
        if (key == null) {
            return AliasHit.MISS;
        }
        NewsTopicAliasDO alias = aliasMapper.selectOne(Wrappers.lambdaQuery(NewsTopicAliasDO.class)
                .eq(NewsTopicAliasDO::getAliasKey, key)
                .last("LIMIT 1"));
        if (alias == null) {
            return AliasHit.MISS;
        }
        if (alias.getTargetTopicId() == null) {
            // rejected：命中即终局——不建提案行不挂关联（09-30 polyu 再提类问题的解）
            log.info("[news] 主题标签 {} 命中 rejected 别名，跳过不建提案（#202 幂等拦截）", token);
            return new AliasHit(true, null);
        }
        NewsTopicDO target = topicMapper.selectById(alias.getTargetTopicId());
        if (target == null || !NewsTopicDO.STATUS_ACTIVE.equals(target.getStatus())) {
            // merged 目标异常缺失/停用：保守跳过（不回退建提案——该名称已治理）
            log.warn("[news] 主题标签 {} 命中 merged 别名但目标 {} 不可用，跳过（#202）",
                    token, alias.getTargetTopicId());
            return new AliasHit(true, null);
        }
        log.info("[news] 主题标签 {} 命中 merged 别名，回链目标主题 {}（#202 幂等拦截）",
                token, target.getSlug());
        return new AliasHit(true, target);
    }

    /**
     * 别名拦截结果：hit=是否命中别名账（命中即终局）；target=merged 目标主题
     * （rejected/不可解析时 null）
     */
    private record AliasHit(boolean hit, NewsTopicDO target) {
        static final AliasHit MISS = new AliasHit(false, null);
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
        return firstNonBlank(candidate, fallback, null);
    }

    private static String firstNonBlank(String candidate, String fallback, String secondFallback) {
        if (candidate != null && !candidate.isBlank()) {
            return candidate.strip();
        }
        if (fallback != null && !fallback.isBlank()) {
            return fallback.strip();
        }
        return secondFallback;
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

    /**
     * 批内控制流信号：enrichOne 已内部落明示零调用回退（非 LLM 成功、非失败）——
     * 批循环据此单独计数（不进 enriched 也不进 isolated）
     */
    static final class FallbackAppliedException extends RuntimeException {
        FallbackAppliedException() {
            super("fallback-applied", null, false, false);
        }
    }
}
