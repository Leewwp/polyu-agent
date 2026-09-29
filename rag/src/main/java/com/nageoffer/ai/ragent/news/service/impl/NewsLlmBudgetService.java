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
 * 资讯 LLM 预算护栏+最小付费回执（#184，父票 #181 §1 合同）
 *
 * <p><b>预算护栏</b>：日 ¥1.0 / 月 ¥15（rag.news.budget-* 外置可调），attempts=
 * 真实发出次数（含路由 fallback 与网关重试），经 {@link LlmAttemptScope} 在
 * ModelRoutingExecutor 每次真实发出前回调本服务记账——超预算的发出被
 * {@link LlmBudgetExhaustedException} 否决，且不污染模型健康（executor 特判豁免）。
 * 消耗持久化在 t_news_llm_receipt（write-ahead：发出前先记账，进程中途退出
 * 时已记部分保守保留——不承诺绝对不重复计费），重启不清零。
 *
 * <p><b>最小付费回执</b>：请求指纹=sha256(实际渲染提示词全文+模型+temperature/
 * topP/maxTokens)——提示词含 source_name/lang_raw/动态主题词表，内容哈希不充分。
 * 成功响应先落库再用（response_text）；同指纹重跑直接复用不重复付费；复用响应
 * 解析无效则清除并隔离（不无限复读）；同请求网关重试 ≤2（rag.news.llm-max-retries）。
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
     * 回执状态：预算耗尽降级（未发出，次日补偿后翻转）
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
     * @param request 完整请求（指纹取其渲染后提示词全文+参数）
     * @param tier    路由档位（资讯摘要=FAST，fallback 链 attempts 全程计数）
     * @return 响应内容+复用标记+解析无效回报句柄
     * @throws LlmBudgetExhaustedException 预算耗尽（调用方当日降级）
     */
    public LlmCall call(ChatRequest request, Tier tier) {
        String fingerprint = fingerprintOf(request, tier);
        NewsLlmReceiptDO row = findByFingerprint(fingerprint);
        if (row == null) {
            row = insertRow(fingerprint, request, tier);
        }
        if (STATUS_POISONED.equals(row.getStatus())) {
            // 不无限复读：同指纹模型输出持续无效已隔离，待内容或词表变化改变指纹后自动重来
            throw new IllegalStateException("资讯 LLM 回执已隔离（模型输出持续无效）：fingerprint=" + fingerprint);
        }
        if (StringUtils.hasText(row.getResponseText())) {
            log.info("[news][budget] 请求 {} 命中回执复用（历史 attempts={}），不重复付费", fingerprint, row.getAttempts());
            return new LlmCall(row.getResponseText(), true, () -> poisonReused(fingerprint));
        }
        int retriesUsed = 0;
        int maxRetries = properties.getLlmMaxRetries() >= 0 ? properties.getLlmMaxRetries() : 2;
        while (true) {
            Session session = openSession(row, fingerprint);
            try {
                String raw = LlmAttemptScope.callWithin(session, () -> llmService.chat(request, tier));
                // 先落库再用：业务解析/落库失败时同指纹下轮复用，不重复付费
                complete(fingerprint, session, raw);
                return new LlmCall(raw, false, () -> markParseFailed(fingerprint));
            } catch (LlmBudgetExhaustedException e) {
                degrade(fingerprint, session, e);
                throw e;
            } catch (Exception e) {
                retriesUsed++;
                boolean exhausted = retriesUsed > maxRetries;
                // retries 口径=已执行的重试数（不含首次），与「同请求重试 ≤2」合同一致
                fail(fingerprint, session, e, exhausted, Math.max(0, retriesUsed - 1));
                if (exhausted) {
                    throw e;
                }
                log.warn("[news][budget] 请求 {} 网关重试 {}/{} 后再试：{}", fingerprint, retriesUsed, maxRetries, e.getMessage());
            }
        }
    }

    // ==================== 会话：逐次发出记账+预算否决 ====================

    /**
     * 单次逻辑调用的记账会话：实现 {@link LlmAttemptScope.AttemptObserver}，
     * 由 ModelRoutingExecutor 在每次真实发出（含 fallback）前回调
     */
    private final class Session implements LlmAttemptScope.AttemptObserver {

        private final NewsLlmReceiptDO row;
        private final String fingerprint;
        private final BigDecimal costPerAttempt;
        private final BigDecimal dailyBase;
        private final BigDecimal monthlyBase;
        private final BigDecimal dailyLimit;
        private final BigDecimal monthlyLimit;

        private int attempts;
        private String lastTargetId;

        private Session(NewsLlmReceiptDO row, String fingerprint, BigDecimal costPerAttempt,
                        BigDecimal dailyBase, BigDecimal monthlyBase) {
            this.row = row;
            this.fingerprint = fingerprint;
            this.costPerAttempt = costPerAttempt;
            this.dailyBase = dailyBase;
            this.monthlyBase = monthlyBase;
            this.dailyLimit = BigDecimal.valueOf(properties.getBudgetDailyYuan());
            this.monthlyLimit = BigDecimal.valueOf(properties.getBudgetMonthlyYuan());
            this.attempts = row.getAttempts() == null ? 0 : row.getAttempts();
        }

        @Override
        public void beforeAttempt(ModelTarget target) {
            this.lastTargetId = target == null ? null : target.id();
            int nextAttempts = this.attempts + 1;
            // 本行成本=attempts × 单次封顶（5k 入+1k 出 flash 原价 ≈¥0.005）
            BigDecimal nextCost = costPerAttempt.multiply(BigDecimal.valueOf(nextAttempts));
            BigDecimal dailyAfter = dailyBase.add(nextCost);
            BigDecimal monthlyAfter = monthlyBase.add(nextCost);
            if (dailyAfter.compareTo(dailyLimit) > 0 || monthlyAfter.compareTo(monthlyLimit) > 0) {
                throw new LlmBudgetExhaustedException(String.format(
                        "资讯 LLM 预算耗尽：日 %.4f/%.2f 元，月 %.4f/%.2f 元（attempts=%d，本次发出被否决）",
                        dailyAfter, dailyLimit, monthlyAfter, monthlyLimit, nextAttempts));
            }
            this.attempts = nextAttempts;
            // write-ahead：真实发出前先记账（未知结果保守预留，重启不清零）
            receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                    .eq(NewsLlmReceiptDO::getId, row.getId())
                    .set(NewsLlmReceiptDO::getAttempts, nextAttempts)
                    .set(NewsLlmReceiptDO::getCostEstimate, nextCost)
                    .set(NewsLlmReceiptDO::getStatDate, todayKey())
                    .set(NewsLlmReceiptDO::getStatMonth, monthKey())
                    .set(NewsLlmReceiptDO::getServedModelId, lastTargetId)
                    .set(NewsLlmReceiptDO::getStatus, STATUS_PENDING)
                    .set(NewsLlmReceiptDO::getErrorBrief, null));
        }
    }

    private Session openSession(NewsLlmReceiptDO row, String fingerprint) {
        // 重开 会话须从库续读 attempts（上一会话 write-ahead 已计入，重试不得清零）
        NewsLlmReceiptDO fresh = findByFingerprint(fingerprint);
        NewsLlmReceiptDO current = fresh != null ? fresh : row;
        // 基数=当日/当月其它回执行成本合计（排除本行：本行成本按 attempts 重算后整体归属当日）
        BigDecimal costPerAttempt = BigDecimal.valueOf(properties.getBudgetCostPerAttemptYuan())
                .setScale(6, RoundingMode.HALF_UP);
        BigDecimal dailyBase = sumCost("stat_date", todayKey(), fingerprint);
        BigDecimal monthlyBase = sumCost("stat_month", monthKey(), fingerprint);
        return new Session(current, fingerprint, costPerAttempt, dailyBase, monthlyBase);
    }

    // ==================== 回执落库 ====================

    private NewsLlmReceiptDO findByFingerprint(String fingerprint) {
        return receiptMapper.selectOne(Wrappers.lambdaQuery(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                .last("LIMIT 1"));
    }

    private NewsLlmReceiptDO insertRow(String fingerprint, ChatRequest request, Tier tier) {
        NewsLlmReceiptDO row = NewsLlmReceiptDO.builder()
                .requestFingerprint(fingerprint)
                .statDate(todayKey())
                .statMonth(monthKey())
                .modelId(primaryModelId(request, tier))
                .attempts(0)
                .retries(0)
                .costEstimate(BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP))
                .status(STATUS_PENDING)
                .build();
        receiptMapper.insert(row);
        return row;
    }

    private void complete(String fingerprint, Session session, String raw) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                .set(NewsLlmReceiptDO::getResponseText, raw)
                .set(NewsLlmReceiptDO::getServedModelId, session.lastTargetId)
                .set(NewsLlmReceiptDO::getStatus, STATUS_SUCCESS)
                .set(NewsLlmReceiptDO::getErrorBrief, null));
    }

    private void degrade(String fingerprint, Session session, LlmBudgetExhaustedException e) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                .set(NewsLlmReceiptDO::getStatus, STATUS_DEGRADED)
                .set(NewsLlmReceiptDO::getErrorBrief, brief(e.getMessage())));
        log.warn("[news][budget] 降级事件：请求 {} 预算耗尽，当日仅入库不富化（次日按配额补偿）：{}",
                fingerprint, e.getMessage());
    }

    private void fail(String fingerprint, Session session, Exception e, boolean exhausted, int retriesUsed) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                .set(NewsLlmReceiptDO::getStatus, exhausted ? STATUS_FAILED : STATUS_PENDING)
                .set(NewsLlmReceiptDO::getRetries, retriesUsed)
                .set(NewsLlmReceiptDO::getErrorBrief, brief(e.getMessage())));
    }

    /**
     * 新鲜响应解析无效：保留 response_text 一次下轮复用（已付费），标记 FAILED
     */
    private void markParseFailed(String fingerprint) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                .set(NewsLlmReceiptDO::getStatus, STATUS_FAILED)
                .set(NewsLlmReceiptDO::getErrorBrief, "响应解析无效，保留一次复用"));
        log.warn("[news][budget] 请求 {} 响应解析无效，回执保留一次复用（下轮仍失败则隔离）", fingerprint);
    }

    /**
     * 复用响应解析无效：清除响应并隔离（POISONED），不再复读也不再重复付费
     */
    private void poisonReused(String fingerprint) {
        receiptMapper.update(null, Wrappers.lambdaUpdate(NewsLlmReceiptDO.class)
                .eq(NewsLlmReceiptDO::getRequestFingerprint, fingerprint)
                .set(NewsLlmReceiptDO::getResponseText, null)
                .set(NewsLlmReceiptDO::getStatus, STATUS_POISONED)
                .set(NewsLlmReceiptDO::getErrorBrief, "复用响应解析无效，已隔离不无限复读"));
        log.warn("[news][budget] 请求 {} 复用响应解析无效，回执已隔离（不无限复读）", fingerprint);
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
     * 按日/月键聚合回执成本（排除本请求行，基数口径见 {@link #openSession}）
     */
    private BigDecimal sumCost(String column, String key, String excludeFingerprint) {
        QueryWrapper<NewsLlmReceiptDO> wrapper = new QueryWrapper<NewsLlmReceiptDO>()
                .select("COALESCE(SUM(cost_estimate), 0) AS total_cost")
                .eq(column, key)
                .ne("request_fingerprint", excludeFingerprint);
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
