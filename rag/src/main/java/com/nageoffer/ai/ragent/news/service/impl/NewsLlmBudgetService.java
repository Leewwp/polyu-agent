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

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.enums.Tier;
import com.nageoffer.ai.ragent.infra.model.LlmAttemptScope;
import com.nageoffer.ai.ragent.infra.model.LlmBudgetExhaustedException;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import com.nageoffer.ai.ragent.news.dao.entity.NewsLlmReceiptDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsLlmReceiptMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.fetch.NewsUrlNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 资讯 LLM 预算护栏+最小付费回执（#184，父票 #181 §1 合同+维护者六点修正 2026-09-29）
 *
 * <p><b>预算护栏</b>：日 ¥1.0 / 月 ¥10（rag.news.budget-* 外置可调；资讯 LLM 专用
 * 独立额度，与全项目成本口径分开统计不混算）。attempts=真实发出次数（含路由
 * fallback 与网关重试），经 {@link LlmAttemptScope} 在 ModelRoutingExecutor 每次
 * 真实发出前回调本服务记账——超预算的发出被 {@link LlmBudgetExhaustedException}
 * 否决，且不污染模型健康（executor 特判豁免）。
 *
 * <p><b>成本上界</b>（维护者修正点2）：单次发出成本=Tier.FAST 完整候选链（含
 * fallback，配置来源 {@code ai.chat.tiers.fast.candidates}）中最贵已配价候选 ×
 * 完整请求限额（输入=max-input-tokens+提示词开销、输出=summary-max-tokens），
 * 单价表外置 {@code rag.news.budget-model-prices}（百炼北京区列表价非思考档）。
 * 现行链 [qwen-flash, qwen-plus] 推导：(4000+2000)×0.8+1024×2 per 1M ≈ ¥0.006848/次。
 *
 * <p><b>账本周期</b>（维护者修正点3）：一行=(请求指纹, 发生日)——日账本行在首次
 * 计入时落定周期键且<b>永不改写</b>；跨日/跨月重试在新的周期行续算，历史归属
 * （stat_date/stat_month）不被搬移。月额度=当月各行聚合，日额度=当日行聚合。
 *
 * <p><b>重试累计</b>（维护者修正点4）：「同请求重试 ≤2」按<b>同指纹累计</b>口径——
 * 回执行持久化 retries 跨调用、跨调度、重启后累加判定，耗尽即不再重试（单次
 * 发出失败后直接上抛）。
 *
 * <p><b>最小付费回执</b>：请求指纹=sha256(实际渲染提示词全文+模型+temperature/
 * topP/maxTokens)——提示词含 source_name/lang_raw/动态主题词表，内容哈希不充分。
 * 成功响应先落库再用（response_text）；同指纹重跑直接复用不重复付费；复用响应
 * 解析无效则清除并隔离（不无限复读）；未知结果（超时/进程退出/响应未持久化）
 * write-ahead 发出前记账保守预留——不承诺绝对不重复计费。
 *
 * <p><b>超额降级</b>：预算耗尽抛 {@link LlmBudgetExhaustedException} 由补全批
 * 捕获——当日仅入库不富化（行保持 summary_en IS NULL），次日按配额自然补偿；
 * 被降级请求记 status=DEGRADED 供 admin/日志双口径核查。
 */
@Slf4j
@Service
public class NewsLlmBudgetService {

    /**
     * 回执状态：发出中
     */
    static final String STATUS_PENDING = "PENDING";

    /**
     * 回执状态：已回执（响应已落库可复用）
     */
    static final String STATUS_SUCCESS = "SUCCESS";

    /**
     * 回执状态：预算耗尽降级（未发出或当次被否决，次日补偿后翻转）
     */
    static final String STATUS_DEGRADED = "DEGRADED";

    /**
     * 回执状态：重试耗尽/解析失败（response_text 保留时仍可复用一次）
     */
    static final String STATUS_FAILED = "FAILED";

    /**
     * 回执状态：复用响应解析无效已隔离（不无限复读，待内容/词表变化换指纹重来）
     */
    static final String STATUS_POISONED = "POISONED";

    /**
     * 资讯管线统一时区（HKT +08:00，「今天」切日=HKT 00:00，沿 NewsFetchJob 先例）
     */
    static final ZoneId HKT_ZONE = ZoneId.of("Asia/Hong_Kong");

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final NewsLlmReceiptMapper receiptMapper;
    private final LLMService llmService;
    private final ModelSelector modelSelector;
    private final NewsFetchProperties properties;
    private final Supplier<Date> nowSupplier;

    /**
     * 未配价候选的 WARN 去重（每 id 一次）
     */
    private final Set<String> unpricedCandidateWarned = ConcurrentHashMap.newKeySet();

    @Autowired
    public NewsLlmBudgetService(NewsLlmReceiptMapper receiptMapper,
                                LLMService llmService,
                                ModelSelector modelSelector,
                                NewsFetchProperties properties) {
        this(receiptMapper, llmService, modelSelector, properties, Date::new);
    }

    /**
     * 全参构造器（测试注入时钟，NewsFetchJob 先例同源）
     */
    NewsLlmBudgetService(NewsLlmReceiptMapper receiptMapper,
                         LLMService llmService,
                         ModelSelector modelSelector,
                         NewsFetchProperties properties,
                         Supplier<Date> nowSupplier) {
        this.receiptMapper = receiptMapper;
        this.llmService = llmService;
        this.modelSelector = modelSelector;
        this.properties = properties;
        this.nowSupplier = nowSupplier;
    }

    /**
     * 预算护栏内完成一次资讯 LLM 调用（含回执复用与网关重试）
     *
     * <p>重试口径=同指纹累计：本次可重试次数 = llm-max-retries − 历史已重试总数
     * （回执持久化续算，跨调度/重启有效）；耗尽后单次失败即上抛不再重试
     *
     * @param request 完整请求（指纹取其渲染后提示词全文+参数）
     * @param tier    路由档位（资讯摘要=FAST，fallback 链 attempts 全程计数）
     * @return 响应内容+复用标记+解析无效回报句柄
     * @throws LlmBudgetExhaustedException 预算耗尽（调用方当日降级）
     */
    public LlmCall call(ChatRequest request, Tier tier) {
        String fingerprint = fingerprintOf(request, tier);
        List<NewsLlmReceiptDO> history = findByFingerprint(fingerprint);
        NewsLlmReceiptDO responded = history.stream()
                .filter(row -> StringUtils.hasText(row.getResponseText()))
                .findFirst().orElse(null);
        if (history.stream().anyMatch(row -> STATUS_POISONED.equals(row.getStatus()))) {
            // 不无限复读：同指纹模型输出持续无效已隔离，待内容或词表变化改变指纹后自动重来
            throw new IllegalStateException("资讯 LLM 回执已隔离（模型输出持续无效）：fingerprint=" + fingerprint);
        }
        if (responded != null) {
            log.info("[news][budget] 请求 {} 命中回执复用（历史 attempts={}），不重复付费",
                    fingerprint, history.stream().mapToInt(row -> nvl(row.getAttempts())).sum());
            NewsLlmReceiptDO reusable = responded;
            return new LlmCall(reusable.getResponseText(), true, () -> poisonReused(reusable));
        }
        // 重试预算=同指纹累计口径（修正点4）
        int retriesTotal = history.stream().mapToInt(row -> nvl(row.getRetries())).sum();
        int maxRetries = properties.getLlmMaxRetries() >= 0 ? properties.getLlmMaxRetries() : 2;
        int retriesBudget = maxRetries - retriesTotal;
        int retriesUsed = 0;
        while (true) {
            Session session = openTodaySession(fingerprint);
            try {
                String raw = LlmAttemptScope.callWithin(session, () -> llmService.chat(request, tier));
                // 先落库再用：业务解析/落库失败时同指纹下轮复用，不重复付费
                complete(session.row, session, raw);
                return new LlmCall(raw, false, () -> markParseFailed(session.row));
            } catch (LlmBudgetExhaustedException e) {
                degrade(session.row, e);
                throw e;
            } catch (Exception e) {
                retriesUsed++;
                boolean exhausted = retriesUsed > Math.max(0, retriesBudget);
                // 当日行内 retries 也随每次重试递增（跨日续算在新周期行重新累计，总和口径不变）
                fail(session.row, e, exhausted, session.rowRetries + 1);
                if (exhausted) {
                    throw e;
                }
                log.warn("[news][budget] 请求 {} 网关重试 {}/{}（同指纹累计）后再试：{}",
                        fingerprint, retriesTotal + retriesUsed, maxRetries, e.getMessage());
            }
        }
    }

    // ==================== 会话：逐次发出记账+预算否决 ====================

    /**
     * 单次逻辑调用的记账会话：实现 {@link LlmAttemptScope.AttemptObserver}，
     * 由 ModelRoutingExecutor 在每次真实发出（含 fallback）前回调。
     * 账本挂<b>当日的周期行</b>——周期键在 insert 时落定，之后永不改写（修正点3）
     */
    private final class Session implements LlmAttemptScope.AttemptObserver {

        private final NewsLlmReceiptDO row;
        private final BigDecimal costPerAttempt;
        private final BigDecimal dayBase;
        private final BigDecimal monthBaseExcludingOwn;
        private final BigDecimal ownMonthCost;
        private final BigDecimal dailyLimit;
        private final BigDecimal monthlyLimit;

        private int attempts;
        private int rowRetries;
        private String lastTargetId;

        private Session(NewsLlmReceiptDO row, BigDecimal costPerAttempt,
                        BigDecimal dayBase, BigDecimal monthBaseExcludingOwn, BigDecimal ownMonthCost) {
            this.row = row;
            this.costPerAttempt = costPerAttempt;
            this.dayBase = dayBase;
            this.monthBaseExcludingOwn = monthBaseExcludingOwn;
            this.ownMonthCost = ownMonthCost;
            this.dailyLimit = BigDecimal.valueOf(properties.getBudgetDailyYuan());
            this.monthlyLimit = BigDecimal.valueOf(properties.getBudgetMonthlyYuan());
            this.attempts = nvl(row.getAttempts());
            this.rowRetries = nvl(row.getRetries());
        }

        @Override
        public void beforeAttempt(ModelTarget target) {
            this.lastTargetId = target == null ? null : target.id();
            int nextAttempts = this.attempts + 1;
            // 本日行成本=当日 attempts × 单次上界（最贵候选×完整限额推导，见类 javadoc）
            BigDecimal nextRowCost = costPerAttempt.multiply(BigDecimal.valueOf(nextAttempts));
            BigDecimal dailyAfter = dayBase.add(nextRowCost);
            // 当月归属=其它指纹当月合计 + 本指纹当月全部周期行（含本日行）+ 本次增量
            BigDecimal monthlyAfter = monthBaseExcludingOwn.add(ownMonthCost).add(costPerAttempt);
            if (dailyAfter.compareTo(dailyLimit) > 0 || monthlyAfter.compareTo(monthlyLimit) > 0) {
                throw new LlmBudgetExhaustedException(String.format(
                        "资讯 LLM 预算耗尽：日 %.4f/%.2f 元，月 %.4f/%.2f 元（当日 attempts=%d，本次发出被否决）",
                        dailyAfter, dailyLimit, monthlyAfter, monthlyLimit, nextAttempts));
            }
            this.attempts = nextAttempts;
            // write-ahead：真实发出前先记账（未知结果保守预留，重启不清零）。
            // 周期键（stat_date/stat_month）随行落定不改写——跨日重试在新周期行续算
            receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                    .eq(NewsLlmReceiptDO::getId, row.getId())
                    .set(NewsLlmReceiptDO::getAttempts, nextAttempts)
                    .set(NewsLlmReceiptDO::getCostEstimate, nextRowCost)
                    .set(NewsLlmReceiptDO::getServedModelId, lastTargetId)
                    .set(NewsLlmReceiptDO::getStatus, STATUS_PENDING)
                    .set(NewsLlmReceiptDO::getErrorBrief, null));
        }
    }

    /**
     * 打开当日周期行（无则新建）并聚合预算基数
     */
    private Session openTodaySession(String fingerprint) {
        String today = todayKey();
        String month = monthKey();
        BigDecimal costPerAttempt = costPerAttemptUpperBound();
        NewsLlmReceiptDO todayRow = receiptMapper.selectOne(Wrappers.lambdaQuery(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                .eq(NewsLlmReceiptDO::getStatDate, today)
                .last("LIMIT 1"));
        if (todayRow == null) {
            todayRow = NewsLlmReceiptDO.builder()
                    .requestFingerprint(fingerprint)
                    .statDate(today)
                    .statMonth(month)
                    .attempts(0)
                    .retries(0)
                    .costEstimate(BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP))
                    .status(STATUS_PENDING)
                    .build();
            receiptMapper.insert(todayRow);
        }
        BigDecimal dayBase = sumCost("stat_date", today, fingerprint);
        BigDecimal monthBaseExcludingOwn = sumCost("stat_month", month, fingerprint);
        BigDecimal ownMonthCost = sumCost("stat_month", month, "only:" + fingerprint);
        return new Session(todayRow, costPerAttempt, dayBase, monthBaseExcludingOwn, ownMonthCost);
    }

    // ==================== 成本上界（修正点2） ====================

    /**
     * 单次发出成本上界：Tier.FAST 完整候选链（含 fallback）中最贵已配价候选 ×
     * 完整请求限额（输入=max-input-tokens+提示词开销；输出=summary-max-tokens）。
     * 链内未配价候选按表内最贵单价兜底并 WARN（保守，不静默）。
     * 现行推导（qwen-plus 最贵）：(4000+2000)×0.8+1024×2 per 1M ≈ ¥0.006848/次
     */
    BigDecimal costPerAttemptUpperBound() {
        long inputQuota = Math.max(0, properties.getMaxInputTokens()) + Math.max(0, properties.getBudgetPromptOverheadTokens());
        long outputQuota = Math.max(0, properties.getSummaryMaxTokens());
        List<ModelTarget> chain;
        try {
            chain = modelSelector.selectChatCandidates(false, Tier.FAST);
        } catch (Exception e) {
            log.debug("[news][budget] 候选链解析失败，按全表单价推导：{}", e.getMessage());
            chain = List.of();
        }
        List<String> candidates = chain == null ? List.of() : chain.stream()
                .filter(Objects::nonNull).map(ModelTarget::id).filter(Objects::nonNull).toList();
        var prices = properties.getBudgetModelPrices();
        BigDecimal fallback = prices.values().stream()
                .map(price -> candidateCost(price, inputQuota, outputQuota))
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.valueOf(0.006848D));
        BigDecimal bound = BigDecimal.ZERO;
        for (String candidate : candidates) {
            NewsFetchProperties.ModelPrice price = prices.get(candidate);
            if (price == null) {
                if (unpricedCandidateWarned.add(candidate)) {
                    log.warn("[news][budget] 候选 {} 未配价（rag.news.budget-model-prices），按表内最贵单价兜底推导上界", candidate);
                }
                bound = fallback;
                continue;
            }
            bound = bound.max(candidateCost(price, inputQuota, outputQuota));
        }
        return (candidates.isEmpty() ? fallback : bound).setScale(6, RoundingMode.CEILING);
    }

    private static BigDecimal candidateCost(NewsFetchProperties.ModelPrice price, long inputQuota, long outputQuota) {
        BigDecimal cost = BigDecimal.valueOf(inputQuota).multiply(BigDecimal.valueOf(price.getInputYuanPerM()))
                .add(BigDecimal.valueOf(outputQuota).multiply(BigDecimal.valueOf(price.getOutputYuanPerM())))
                .divide(BigDecimal.valueOf(1_000_000L), 8, RoundingMode.HALF_UP);
        return cost;
    }

    // ==================== 回执落库 ====================

    private List<NewsLlmReceiptDO> findByFingerprint(String fingerprint) {
        return receiptMapper.selectList(Wrappers.lambdaQuery(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                .orderByAsc(NewsLlmReceiptDO::getId));
    }

    private void complete(NewsLlmReceiptDO row, Session session, String raw) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getId, row.getId())
                .set(NewsLlmReceiptDO::getResponseText, raw)
                .set(NewsLlmReceiptDO::getServedModelId, session.lastTargetId)
                .set(NewsLlmReceiptDO::getStatus, STATUS_SUCCESS)
                .set(NewsLlmReceiptDO::getErrorBrief, null));
    }

    private void degrade(NewsLlmReceiptDO row, LlmBudgetExhaustedException e) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getId, row.getId())
                .set(NewsLlmReceiptDO::getStatus, STATUS_DEGRADED)
                .set(NewsLlmReceiptDO::getErrorBrief, brief(e.getMessage())));
        log.warn("[news][budget] 降级事件：请求 {} 预算耗尽，当日仅入库不富化（次日按配额补偿）：{}",
                row.getRequestFingerprint(), e.getMessage());
    }

    private void fail(NewsLlmReceiptDO row, Exception e, boolean exhausted, int rowRetriesAfter) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getId, row.getId())
                .set(NewsLlmReceiptDO::getStatus, exhausted ? STATUS_FAILED : STATUS_PENDING)
                .set(NewsLlmReceiptDO::getRetries, rowRetriesAfter)
                .set(NewsLlmReceiptDO::getErrorBrief, brief(e.getMessage())));
    }

    /**
     * 新鲜响应解析无效：保留 response_text 一次下轮复用（已付费），标记 FAILED
     */
    private void markParseFailed(NewsLlmReceiptDO row) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getId, row.getId())
                .set(NewsLlmReceiptDO::getStatus, STATUS_FAILED)
                .set(NewsLlmReceiptDO::getErrorBrief, "响应解析无效，保留一次复用"));
        log.warn("[news][budget] 请求 {} 响应解析无效，回执保留一次复用（下轮仍失败则隔离）",
                row.getRequestFingerprint());
    }

    /**
     * 复用响应解析无效：清除响应并隔离（POISONED），不再复读也不再重复付费
     */
    private void poisonReused(NewsLlmReceiptDO row) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getId, row.getId())
                .set(NewsLlmReceiptDO::getResponseText, null)
                .set(NewsLlmReceiptDO::getStatus, STATUS_POISONED)
                .set(NewsLlmReceiptDO::getErrorBrief, "复用响应解析无效，已隔离不无限复读"));
        log.warn("[news][budget] 请求 {} 复用响应解析无效，回执已隔离（不无限复读）",
                row.getRequestFingerprint());
    }

    // ==================== 指纹与聚合 ====================

    /**
     * 请求指纹：实际渲染提示词全文（含 source_name/lang_raw/动态主题词表等模板
     * 渲染结果）+模型（档位主选 id）+影响输出参数（thinking/temperature/topP/
     * maxTokens）——仅内容哈希不充分（父票 B2 合同）
     */
    String fingerprintOf(ChatRequest request, Tier tier) {
        String promptSource = request.getMessages() == null ? "" : request.getMessages().stream()
                .map(message -> (message.getRole() == null ? "?" : message.getRole()) + ":"
                        + (message.getContent() == null ? "" : message.getContent()))
                .collect(Collectors.joining("\n---\n"));
        String source = String.join("|",
                "v1",
                promptSource,
                primaryModelId(request, tier),
                String.valueOf(request.getThinking()),
                Objects.toString(request.getTemperature(), ""),
                Objects.toString(request.getTopP(), ""),
                Objects.toString(request.getMaxTokens(), ""));
        return NewsUrlNormalizer.sha256Hex(source);
    }

    /**
     * 档位主选模型 id（配置期口径，作指纹成分；候选链换主选时指纹自然失效不复用旧响应）
     */
    private String primaryModelId(ChatRequest request, Tier tier) {
        try {
            List<ModelTarget> targets = modelSelector.selectChatCandidates(
                    Boolean.TRUE.equals(request.getThinking()), tier);
            if (targets != null && !targets.isEmpty() && targets.get(0) != null) {
                return targets.get(0).id();
            }
        } catch (Exception e) {
            log.debug("[news][budget] 主选模型解析失败，指纹以 unrouted 兜底：{}", e.getMessage());
        }
        return "unrouted";
    }

    /**
     * 按日/月键聚合回执成本：excludeFingerprint=null 全量；「only:fp」仅本指纹；
     * 其余=排除本指纹（本指纹当日成本按行内 attempts 重算后加入）
     */
    private BigDecimal sumCost(String column, String key, String excludeFingerprint) {
        QueryWrapper<NewsLlmReceiptDO> wrapper = new QueryWrapper<NewsLlmReceiptDO>()
                .select("COALESCE(SUM(cost_estimate), 0) AS total_cost")
                .eq(column, key);
        if (excludeFingerprint == null) {
            // 全量
        } else if (excludeFingerprint.startsWith("only:")) {
            wrapper.eq("request_fingerprint", excludeFingerprint.substring(5));
        } else {
            wrapper.ne("request_fingerprint", excludeFingerprint);
        }
        List<Object> values = receiptMapper.selectObjs(wrapper);
        if (values == null || values.isEmpty() || values.get(0) == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(values.get(0)));
    }

    private String todayKey() {
        return todayKey(nowSupplier.get());
    }

    private String monthKey() {
        return monthKey(nowSupplier.get());
    }

    /**
     * HKT 日键（yyyy-MM-dd）——admin 预算口径与本服务共用
     */
    static String todayKey(Date now) {
        return LocalDate.ofInstant(now.toInstant(), HKT_ZONE).format(DAY_FORMAT);
    }

    /**
     * HKT 月键（yyyy-MM）
     */
    static String monthKey(Date now) {
        return LocalDate.ofInstant(now.toInstant(), HKT_ZONE).format(MONTH_FORMAT);
    }

    private static String brief(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * 调用结果：内容+是否回执复用+解析无效回报句柄
     *
     * <p>调用方解析失败时执行 {@link #reportInvalid()}——新鲜响应保留一次复用
     * （已付费不浪费），复用响应则清除并隔离（不无限复读）
     */
    public static final class LlmCall {

        private final String content;
        private final boolean reused;
        private final Runnable invalidReporter;

        LlmCall(String content, boolean reused, Runnable invalidReporter) {
            this.content = content;
            this.reused = reused;
            this.invalidReporter = invalidReporter;
        }

        public String content() {
            return content;
        }

        public boolean reused() {
            return reused;
        }

        public void reportInvalid() {
            invalidReporter.run();
        }
    }
}
