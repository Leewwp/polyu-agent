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
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 资讯 LLM 预算护栏+最小付费回执（#184，父票 #181 §1 合同+维护者六点修正 2026-09-29 定点修正轮）
 *
 * <p><b>预算护栏</b>：日 ¥1.0 / 月 ¥10（rag.news.budget-* 外置可调；资讯 LLM 专用
 * 独立额度，与全项目成本口径分开统计不混算）。attempts=真实发出次数（含路由
 * fallback 与网关重试），经 {@link LlmAttemptScope} 在 ModelRoutingExecutor 每次
 * 真实发出前回调本服务记账——超预算的发出被 {@link LlmBudgetExhaustedException}
 * 否决，且不污染模型健康（executor 特判豁免）。
 *
 * <p><b>逐次发出准入</b>（修正点1/2/4 并发口径）：每次真实发出（含同一次逻辑调用内
 * 的 fallback 换候选）在 {@code beforeAttempt} 内<b>现场</b>完成——按发出时刻重取
 * 周期行（跨 HKT 午夜/月末自动落新周期行，旧行不搬移）、重读日/月聚合基数、
 * 月额度=其它指纹当月合计+本指纹当月持久合计（含本会话此前已记账的发出）+本次增量，
 * 通过后在同一<b>串行准入锁</b>内 write-ahead 记账——单机串行准入（票面允许项），
 * 避免并发检查后超额；多实例部署须改 PG 原子预留（当前单实例拓扑不适用）。
 *
 * <p><b>成本上界</b>（修正点2/4 计价闭合）：单次发出成本=Tier.FAST 完整候选链
 * （配置来源 {@code ai.chat.tiers.fast.candidates}）中最贵已配价候选 × 完整请求限额
 * （输入=max-input-tokens+提示词开销、输出=summary-max-tokens；NewsEnrichService
 * 保证完整渲染请求在输入限额内，估算口径）。链内任一候选未配价、单价非法、候选链
 * 不可解析、或请求 thinking 模式与单价表（非思考档）不匹配——<b>拒绝付费准入</b>，
 * 不以已知最高价猜未知价。现行链 [qwen-flash, qwen-plus] 推导：
 * (4000+2000)×0.8+1024×2 per 1M ≈ ¥0.006848/次。
 *
 * <p><b>账本周期</b>（修正点3）：一行=(请求指纹, 发生日)——周期行在首次计入时落定
 * 周期键且<b>永不改写</b>；跨日/跨月重试在新的周期行续算，历史归属
 * （stat_date/stat_month）不被搬移。月额度=当月各行聚合，日额度=当日行聚合。
 * stat_date 为 PG DATE 列，实体用 {@link LocalDate} 原生映射（String 绑定在
 * PgJDBC 下双向不兼容，修正点6 本地 polyu-pg 实证）。
 *
 * <p><b>重试生命周期</b>（修正点4）：同指纹生命周期=初次逻辑调用+最多
 * llm-max-retries(默认2) 次逻辑重试（fallback 各自另计 attempt 不算重试）。
 * 历史重试已耗尽且无成功响应——<b>零新增请求</b>（入口直接拒绝，不赠送新「首次」）；
 * retries 只在重试<b>真实签发</b>（首次发出前 write-ahead）时计数，不把未实际执行
 * 的下一次重试提前算入；跨调度/重启从回执持久值续算。已存成功响应可继续免费复用。
 *
 * <p><b>最小付费回执</b>：请求指纹=sha256(实际渲染提示词全文+模型+temperature/
 * topP/maxTokens)——提示词含 source_name/lang_raw/动态主题词表，内容哈希不充分。
 * 成功响应先落库再用（response_text）；同指纹重跑直接复用不重复付费；复用响应
 * 解析无效则清除并隔离（不无限复读）；未知结果（超时/进程退出/响应未持久化）
 * write-ahead 发出前记账保守预留——不承诺绝对不重复计费。
 *
 * <p><b>超额降级</b>：预算耗尽/计价未闭合抛 {@link LlmBudgetExhaustedException}
 * 由补全批捕获——当日仅入库不富化（行保持 summary_en IS NULL），次日按配额自然
 * 补偿；被降级请求记 status=DEGRADED 供 admin/日志双口径核查。
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

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

    private final NewsLlmReceiptMapper receiptMapper;
    private final LLMService llmService;
    private final ModelSelector modelSelector;
    private final NewsFetchProperties properties;
    private final Supplier<Date> nowSupplier;

    /**
     * 单机串行准入锁（修正点4 并发口径，票面允许项）：周期行解析+聚合重读+预算检查
     * +write-ahead 记账在同一临界区内完成，避免并发检查后超额。仅约束本实例内并发
     * （当前单实例拓扑）；多实例部署须换 PG 原子预留。
     */
    private final Object admissionLock = new Object();

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
     * <p>重试口径=同指纹累计：本次可发逻辑调用数由持久 retries 续算；耗尽后
     * 零新增请求；retries 只在重试真实签发（发出前 write-ahead）时递增。
     *
     * @param request 完整请求（指纹取其渲染后提示词全文+参数）
     * @param tier    路由档位（资讯摘要=FAST，fallback 链 attempts 全程计数）
     * @return 响应内容+复用标记+解析无效回报句柄
     * @throws LlmBudgetExhaustedException 预算耗尽或计价未闭合（调用方当日降级）
     */
    public LlmCall call(ChatRequest request, Tier tier) {
        String fingerprint = fingerprintOf(request, tier);
        List<NewsLlmReceiptDO> history = findByFingerprint(fingerprint);
        if (history.stream().anyMatch(row -> STATUS_POISONED.equals(row.getStatus()))) {
            // 不无限复读：同指纹模型输出持续无效已隔离，待内容或词表变化改变指纹后自动重来
            throw new IllegalStateException("资讯 LLM 回执已隔离（模型输出持续无效）：fingerprint=" + fingerprint);
        }
        NewsLlmReceiptDO responded = history.stream()
                .filter(row -> StringUtils.hasText(row.getResponseText()))
                .findFirst().orElse(null);
        if (responded != null) {
            log.info("[news][budget] 请求 {} 命中回执复用（历史 attempts={}），不重复付费",
                    fingerprint, history.stream().mapToInt(row -> nvl(row.getAttempts())).sum());
            return new LlmCall(responded.getResponseText(), true, () -> poisonReused(responded));
        }
        // 计价闭合（修正点4）：完整候选链+模式+额度校验，未闭合拒绝付费准入（零发出）；
        // 与预算否决同族落 DEGRADED 事件行（admin/日志双口径可查）
        BigDecimal costPerAttempt;
        try {
            costPerAttempt = requirePricingClosure(request, tier);
        } catch (LlmBudgetExhaustedException e) {
            NewsLlmReceiptDO eventRow;
            synchronized (admissionLock) {
                eventRow = findOrCreatePeriodRow(fingerprint, todayDateKey(), monthKey());
            }
            degrade(eventRow, e);
            throw e;
        }
        // 重试生命周期（修正点3）：初次已消耗且重试预算耗尽 → 零新增请求
        int retriesTotal = history.stream().mapToInt(row -> nvl(row.getRetries())).sum();
        int maxRetries = properties.getLlmMaxRetries() >= 0 ? properties.getLlmMaxRetries() : 2;
        boolean initialConsumed = history.stream().anyMatch(row -> nvl(row.getAttempts()) > 0);
        if (initialConsumed && retriesTotal >= maxRetries) {
            throw new IllegalStateException(String.format(
                    "资讯 LLM 同指纹重试预算已耗尽（retries=%d/%d）：零新增请求，待内容/词表变化换指纹重来，fingerprint=%s",
                    retriesTotal, maxRetries, fingerprint));
        }
        int iterationsAllowed = initialConsumed ? maxRetries - retriesTotal : 1 + maxRetries - retriesTotal;
        Exception lastFailure = null;
        for (int iteration = 1; iteration <= iterationsAllowed; iteration++) {
            Session session = openTodaySession(fingerprint, costPerAttempt);
            boolean isRetry = initialConsumed || iteration > 1;
            if (isRetry) {
                // 重试签发标记：发出前随 write-ahead 一并记账；被预算否决（未发出）不计数
                session.markRetryPending();
            }
            try {
                String raw = LlmAttemptScope.callWithin(session, () -> llmService.chat(request, tier));
                // 先落库再用：业务解析/落库失败时同指纹下轮复用，不重复付费
                complete(session.currentRow(), session, raw);
                NewsLlmReceiptDO successRow = session.currentRow();
                return new LlmCall(raw, false, () -> markParseFailed(successRow));
            } catch (LlmBudgetExhaustedException e) {
                degrade(session.currentRow(), e);
                throw e;
            } catch (Exception e) {
                lastFailure = e;
                // 持久口径的重试耗尽判定：只统计真实签发过（write-ahead 已记账）的重试
                int persistedRetries = countRetries(fingerprint);
                boolean exhausted = persistedRetries >= maxRetries;
                fail(session.currentRow(), e, exhausted);
                if (exhausted) {
                    throw e;
                }
                log.warn("[news][budget] 请求 {} 第 {} 次逻辑调用失败（同指纹累计重试 {}/{}）后重试：{}",
                        fingerprint, iteration, persistedRetries, maxRetries, e.getMessage());
            }
        }
        // 迭代上限走满仍未成功（存在未实际发出即失败的调用，不计入重试）：上抛最后一次失败
        throw new IllegalStateException("资讯 LLM 重试迭代上限走满（未实际发出的调用不计重试）", lastFailure);
    }

    // ==================== 会话：逐次发出记账+预算否决 ====================

    /**
     * 单次逻辑调用的记账会话：实现 {@link LlmAttemptScope.AttemptObserver}，
     * 由 ModelRoutingExecutor 在每次真实发出（含 fallback）前回调。
     *
     * <p>每次回调在 {@link #admissionLock} 内<b>现场</b>完成（修正点1/2/4）：
     * 按发出时刻解析周期行（跨 HKT 午夜/月末落新行，旧行不搬移）→ 重读该行最新
     * 持久值与日/月聚合 → 预算检查（月额度含本会话此前已记账发出）→ write-ahead
     * 记账（attempts+1、行成本、PENDING、待签发重试计数）。
     */
    private final class Session implements LlmAttemptScope.AttemptObserver {

        private final String fingerprint;
        private final BigDecimal costPerAttempt;
        private final BigDecimal dailyLimit;
        private final BigDecimal monthlyLimit;

        private NewsLlmReceiptDO row;
        private String lastTargetId;
        private boolean retryPending;

        private Session(String fingerprint, NewsLlmReceiptDO todayRow, BigDecimal costPerAttempt) {
            this.fingerprint = fingerprint;
            this.row = todayRow;
            this.costPerAttempt = costPerAttempt;
            this.dailyLimit = BigDecimal.valueOf(properties.getBudgetDailyYuan());
            this.monthlyLimit = BigDecimal.valueOf(properties.getBudgetMonthlyYuan());
        }

        /**
         * 标记本次逻辑调用为重试：计数随下一次真实发出的 write-ahead 一并落库；
         * 被预算否决（未发出）则不计数（修正点3：不把未实际执行的重试算成已重试）
         */
        void markRetryPending() {
            this.retryPending = true;
        }

        NewsLlmReceiptDO currentRow() {
            return row;
        }

        @Override
        public void beforeAttempt(ModelTarget target) {
            this.lastTargetId = target == null ? null : target.id();
            synchronized (admissionLock) {
                // 修正点2：按真实发出时刻重取周期行——跨 HKT 午夜/月末自动切换新行，旧行不搬移。
                // 同时重读最新持久值：同一次逻辑调用内 fallback 的后续发出能看到此前已记账的 attempt
                LocalDate attemptDay = todayDateKey();
                String attemptMonth = monthKey();
                this.row = findOrCreatePeriodRow(fingerprint, attemptDay, attemptMonth);
                NewsLlmReceiptDO current = this.row;
                int nextAttempts = nvl(current.getAttempts()) + 1;
                // 本日行成本=当日 attempts × 单次上界（最贵候选×完整限额推导，见类 javadoc）
                BigDecimal nextRowCost = costPerAttempt.multiply(BigDecimal.valueOf(nextAttempts));
                BigDecimal dayBaseOther = sumCost("stat_date", attemptDay, fingerprint);
                BigDecimal monthBaseOther = sumCost("stat_month", attemptMonth, fingerprint);
                BigDecimal ownMonthCost = sumCost("stat_month", attemptMonth, "only:" + fingerprint);
                BigDecimal dailyAfter = dayBaseOther.add(nextRowCost);
                // 修正点1：月额度=其它指纹当月合计+本指纹当月持久合计（含本会话此前发出）+本次增量
                BigDecimal monthlyAfter = monthBaseOther.add(ownMonthCost).add(costPerAttempt);
                if (dailyAfter.compareTo(dailyLimit) > 0 || monthlyAfter.compareTo(monthlyLimit) > 0) {
                    throw new LlmBudgetExhaustedException(String.format(
                            "资讯 LLM 预算耗尽：日 %.6f/%.2f 元，月 %.6f/%.2f 元（本行 attempts=%d，本次发出被否决）",
                            dailyAfter, dailyLimit, monthlyAfter, monthlyLimit, nextAttempts));
                }
                // write-ahead：真实发出前先记账（未知结果保守预留，重启不清零）。
                // 周期键（stat_date/stat_month）随行落定不改写——跨日重试在新周期行续算
                var update = Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                        .eq(NewsLlmReceiptDO::getId, current.getId())
                        .set(NewsLlmReceiptDO::getAttempts, nextAttempts)
                        .set(NewsLlmReceiptDO::getCostEstimate, nextRowCost)
                        .set(NewsLlmReceiptDO::getServedModelId, lastTargetId)
                        .set(NewsLlmReceiptDO::getStatus, STATUS_PENDING)
                        .set(NewsLlmReceiptDO::getErrorBrief, null);
                if (retryPending) {
                    update.set(NewsLlmReceiptDO::getRetries, nvl(current.getRetries()) + 1);
                    // 重试计数按逻辑调用记一次（fallback 换候选只另计 attempt，不另计 retry）
                    this.retryPending = false;
                }
                receiptMapper.update(null, update);
            }
        }
    }

    /**
     * 打开当日周期行（无则新建）——行对象仅作初始指针，每次真实发出前在
     * {@link Session#beforeAttempt} 内按发出时刻重取（含跨午夜/月末切换）。
     * 建行在准入锁内串行（同指纹并发建行由锁+库唯一约束双兜底）
     */
    private Session openTodaySession(String fingerprint, BigDecimal costPerAttempt) {
        NewsLlmReceiptDO todayRow;
        synchronized (admissionLock) {
            todayRow = findOrCreatePeriodRow(fingerprint, todayDateKey(), monthKey());
        }
        return new Session(fingerprint, todayRow, costPerAttempt);
    }

    // ==================== 计价闭合（修正点4） ====================

    /**
     * 计价闭合校验：完整候选链全部已配价且单价合法、候选链可解析、请求模式与
     * 单价表（非思考档）匹配、日/月额度非负有限——任一不满足即拒绝付费准入
     * （抛 {@link LlmBudgetExhaustedException}，零发出、不污染模型健康），
     * 不以已知最高价猜未知价。
     *
     * @return 单次发出成本上界（链内最贵已配价候选 × 完整请求限额）
     */
    private BigDecimal requirePricingClosure(ChatRequest request, Tier tier) {
        if (Boolean.TRUE.equals(request.getThinking())) {
            throw closureRefusal(String.format(
                    "请求为思考模式而单价表仅覆盖非思考档（显式 thinking=false 口径），fingerprint 前置拒绝"));
        }
        double daily = properties.getBudgetDailyYuan();
        double monthly = properties.getBudgetMonthlyYuan();
        if (Double.isNaN(daily) || Double.isInfinite(daily) || daily < 0
                || Double.isNaN(monthly) || Double.isInfinite(monthly) || monthly < 0) {
            throw closureRefusal(String.format("额度配置非法：日 %.4f / 月 %.4f 元", daily, monthly));
        }
        List<ModelTarget> chain;
        try {
            chain = modelSelector.selectChatCandidates(false, tier);
        } catch (Exception e) {
            throw closureRefusal("候选链不可解析：" + e.getMessage());
        }
        if (chain == null || chain.isEmpty()) {
            throw closureRefusal(String.format("%s 档候选链为空（ai.chat.tiers.*.candidates）", tier));
        }
        var prices = properties.getBudgetModelPrices();
        long inputQuota = properties.effectiveInputQuotaTokens();
        long outputQuota = properties.effectiveSummaryMaxTokens();
        BigDecimal bound = BigDecimal.ZERO;
        for (ModelTarget target : chain) {
            String candidate = target == null ? null : target.id();
            NewsFetchProperties.ModelPrice price = candidate == null ? null : prices.get(candidate);
            if (price == null) {
                throw closureRefusal(String.format(
                        "候选 %s 未配价（rag.news.budget-model-prices，北京区非思考档口径；区域或模式不匹配同样视为未配价）",
                        candidate));
            }
            if (Double.isNaN(price.getInputYuanPerM()) || Double.isInfinite(price.getInputYuanPerM())
                    || price.getInputYuanPerM() < 0 || Double.isNaN(price.getOutputYuanPerM())
                    || Double.isInfinite(price.getOutputYuanPerM()) || price.getOutputYuanPerM() < 0) {
                throw closureRefusal(String.format(
                        "候选 %s 单价非法：输入 %.4f / 输出 %.4f 元每百万 tokens",
                        candidate, price.getInputYuanPerM(), price.getOutputYuanPerM()));
            }
            bound = bound.max(candidateCost(price, inputQuota, outputQuota));
        }
        return bound.setScale(6, RoundingMode.CEILING);
    }

    private static LlmBudgetExhaustedException closureRefusal(String reason) {
        return new LlmBudgetExhaustedException("资讯 LLM 计价未闭合，拒绝付费准入（不以已知最高价猜未知价）：" + reason);
    }

    private static BigDecimal candidateCost(NewsFetchProperties.ModelPrice price, long inputQuota, long outputQuota) {
        return BigDecimal.valueOf(inputQuota).multiply(BigDecimal.valueOf(price.getInputYuanPerM()))
                .add(BigDecimal.valueOf(outputQuota).multiply(BigDecimal.valueOf(price.getOutputYuanPerM())))
                .divide(BigDecimal.valueOf(1_000_000L), 8, RoundingMode.HALF_UP);
    }

    // ==================== 回执落库 ====================

    private List<NewsLlmReceiptDO> findByFingerprint(String fingerprint) {
        return receiptMapper.selectList(Wrappers.lambdaQuery(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                .orderByAsc(NewsLlmReceiptDO::getId));
    }

    /**
     * 同指纹全行 retries 合计（持久口径，跨调度/重启续算）
     */
    private int countRetries(String fingerprint) {
        return findByFingerprint(fingerprint).stream().mapToInt(row -> nvl(row.getRetries())).sum();
    }

    /**
     * 查找或新建周期行（指纹, 发生日）：周期键随行落定后永不改写；
     * 返回行持有最新持久值（真实库/可靠 fake store 均为独立查询结果）
     */
    private NewsLlmReceiptDO findOrCreatePeriodRow(String fingerprint, LocalDate day, String month) {
        NewsLlmReceiptDO row = receiptMapper.selectOne(Wrappers.lambdaQuery(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                .eq(NewsLlmReceiptDO::getStatDate, day)
                .last("LIMIT 1"));
        if (row == null) {
            row = NewsLlmReceiptDO.builder()
                    .requestFingerprint(fingerprint)
                    .statDate(day)
                    .statMonth(month)
                    .attempts(0)
                    .retries(0)
                    .costEstimate(BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP))
                    .status(STATUS_PENDING)
                    .build();
            receiptMapper.insert(row);
        }
        return row;
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
        log.warn("[news][budget] 降级事件：请求 {} 预算耗尽/计价未闭合，当日仅入库不富化（次日按配额补偿）：{}",
                row.getRequestFingerprint(), e.getMessage());
    }

    private void fail(NewsLlmReceiptDO row, Exception e, boolean exhausted) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getId, row.getId())
                .set(NewsLlmReceiptDO::getStatus, exhausted ? STATUS_FAILED : STATUS_PENDING)
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
     * 其余=排除本指纹（本指纹当日成本按行内 attempts 重算后加入）。
     * stat_date 键为 {@link LocalDate}（PG DATE 原生映射），stat_month 键为字符串。
     */
    private BigDecimal sumCost(Object column, Object key, String excludeFingerprint) {
        QueryWrapper<NewsLlmReceiptDO> wrapper = new QueryWrapper<NewsLlmReceiptDO>()
                .select("COALESCE(SUM(cost_estimate), 0) AS total_cost")
                .eq(String.valueOf(column), key);
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

    private LocalDate todayDateKey() {
        return todayDateKey(nowSupplier.get());
    }

    private String monthKey() {
        return monthKey(nowSupplier.get());
    }

    /**
     * HKT 日键——admin 预算口径与本服务共用；LocalDate 直连 PG DATE 列（修正点6）
     */
    static LocalDate todayDateKey(Date now) {
        return LocalDate.ofInstant(now.toInstant(), HKT_ZONE);
    }

    /**
     * HKT 月键（yyyy-MM，stat_month VARCHAR(7) 字符串列）
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
