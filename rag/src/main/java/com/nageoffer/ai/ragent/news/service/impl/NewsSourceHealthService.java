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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceHealthEventDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceHealthEventMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsSourceMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchOutcome;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

/**
 * 信源健康服务（#186：defer 豁免+停用原因三分+探活复归的记账与编排）
 *
 * <p><b>常规轮记账</b> {@link #recordFetchOutcome(NewsSourceDO, NewsFetchService.SourceFetchResult)}：
 * 按 {@link NewsFetchOutcome} 六类落 t_news_source 健康列——
 * <ul>
 * <li><b>有效两类</b>（有内容/有效空）：清零 consecutive_failures（仅非零时写）+
 * 落 last_outcome；</li>
 * <li><b>defer</b>：<b>零计数豁免</b>（#186 范围 1——不增不清零滞回，只落
 * last_outcome。旧缺陷：fetchCandidates 的 catch(Exception) 先 recordFailure 再重抛，
 * NewsFetchDeferredException 也被计入滞回，与「defer 不计失败」矛盾）；</li>
 * <li><b>结构失配/网络失败</b>：滞回 +1，连续 ≥3 自动隔离（enabled=false +
 * disabled_reason='auto' + isolated_time + 事件）；</li>
 * <li><b>策略禁止</b>：立即转停（disabled_reason='policy' + 事件）——robots/出站
 * 守卫拒绝不是源故障，重试与探活都无意义（政策禁止不因可达自动解禁）。</li>
 * </ul>
 *
 * <p><b>日级探活</b> {@link #probeSweep()}：仅对 disabled_reason='auto' 的禁用源
 * （人工停用/策略禁止永不探活——三类停用互不误复活），每源每 HKT 日至多一次：
 * 走 {@link NewsFetchService#fetch} 同一条纪律路径（robots 校验/共享 host 节拍/
 * 瞬时重试单点收口），<b>探活抓到的候选即弃不入库</b>——复归源的内容只经常规轮的
 * admitAll（#185 全站/单源日准入）与富化预算（#184）进入管线，不绕统一扩量门。
 *
 * <p><b>复归条件</b>：连续两次<b>有效完整成功</b>（第 1/2 类；HTTP 200 不是恢复
 * 充分条件——解析失配是探活失败）且相邻成功间隔 ≤48h（probe-success-window-hours，
 * 超窗视为不连续，streak 重起）；达成即 enabled=true + disabled_reason=NULL +
 * failures 清零 + recovered_time + 事件。可达且持续成功下两日两次日级探活 →
 * 复归 ≤48h。defer 不是失败也不是成功：不计尝试（probe_time 不推进）。
 */
@Slf4j
@Service
public class NewsSourceHealthService {

    /**
     * 滞回阈值：连续失败（结构失配/网络失败两类）≥3 自动隔离（沿既有范式，从
     * NewsFetchService 迁入——健康记账归本服务单点）
     */
    static final int FAILURE_THRESHOLD = 3;

    /**
     * 事件 detail 截断长度（事件表 VARCHAR(512)，防御异常消息超长）
     */
    private static final int DETAIL_MAX = 500;

    private final NewsSourceMapper sourceMapper;
    private final NewsSourceHealthEventMapper eventMapper;
    private final NewsFetchService fetchService;
    private final NewsFetchProperties properties;
    private final Supplier<Date> nowSupplier;

    @org.springframework.beans.factory.annotation.Autowired
    public NewsSourceHealthService(NewsSourceMapper sourceMapper,
                                   NewsSourceHealthEventMapper eventMapper,
                                   NewsFetchService fetchService,
                                   NewsFetchProperties properties) {
        this(sourceMapper, eventMapper, fetchService, properties, Date::new);
    }

    /**
     * 全参构造器（测试注入时钟）
     */
    NewsSourceHealthService(NewsSourceMapper sourceMapper,
                            NewsSourceHealthEventMapper eventMapper,
                            NewsFetchService fetchService,
                            NewsFetchProperties properties,
                            Supplier<Date> nowSupplier) {
        this.sourceMapper = sourceMapper;
        this.eventMapper = eventMapper;
        this.fetchService = fetchService;
        this.properties = properties;
        this.nowSupplier = nowSupplier;
    }

    /**
     * 探活扫描结果（日志与验收口径计数）
     *
     * @param probed   实际探活次数（日级节拍后）
     * @param recovered 其中复归源数
     */
    public record ProbeSweepResult(int probed, int recovered) {
    }

    /**
     * 常规轮结果记账（启用源；六类语义见类 javadoc）。同时镜像回传入的 DO，
     * 调用方（NewsFetchJob）可即时读到 enabled/failures 最新值。
     */
    public void recordFetchOutcome(NewsSourceDO source, NewsFetchService.SourceFetchResult result) {
        Date now = nowSupplier.get();
        NewsFetchOutcome outcome = result.outcome();
        // defer 豁免（#186 范围 1）：结构上不可能落到任何滞回写——仅记 last_outcome
        if (outcome.isValidSuccess()) {
            boolean failuresDirty = source.getConsecutiveFailures() != null
                    && source.getConsecutiveFailures() != 0;
            boolean outcomeDirty = !outcome.code().equals(source.getLastOutcome());
            if (failuresDirty || outcomeDirty) {
                var update = Wrappers.lambdaUpdate(NewsSourceDO.class)
                        .eq(NewsSourceDO::getId, source.getId())
                        .set(NewsSourceDO::getLastOutcome, outcome.code())
                        .set(NewsSourceDO::getLastOutcomeTime, now)
                        .set(NewsSourceDO::getUpdateTime, now);
                if (failuresDirty) {
                    update.set(NewsSourceDO::getConsecutiveFailures, 0);
                }
                sourceMapper.update(null, update);
                source.setConsecutiveFailures(0);
            }
            source.setLastOutcome(outcome.code());
            source.setLastOutcomeTime(now);
            return;
        }
        switch (outcome) {
            case DEFER -> {
                sourceMapper.update(null, baseOutcomeUpdate(source, outcome, now));
                source.setLastOutcome(outcome.code());
                source.setLastOutcomeTime(now);
            }
            case STRUCTURE_MISMATCH, NETWORK_FAILURE ->
                    recordHysteresisFailure(source, outcome, result.detail(), now);
            case POLICY_FORBIDDEN ->
                    disableForPolicy(source, result.detail(), now, outcome);
            default -> {
                // 六类已穷举（isValidSuccess 两类在上方 return）；防御新增枚举漏接线
                log.warn("[news] 源 {} 结果 {} 未接线记账（忽略）", source.getSourceKey(), outcome);
            }
        }
    }

    /**
     * 日级探活扫描：自动隔离源（disabled_reason='auto'）逐源探一次（每 HKT 日至多一次），
     * 两次有效完整成功（间隔 ≤48h）自动复归。人工停用/策略禁止不进本扫描。
     *
     * @return 探活次数与复归数
     */
    public ProbeSweepResult probeSweep() {
        Date now = nowSupplier.get();
        List<NewsSourceDO> candidates = sourceMapper.selectList(Wrappers.lambdaQuery(NewsSourceDO.class)
                .eq(NewsSourceDO::getEnabled, false)
                .eq(NewsSourceDO::getDisabledReason, NewsSourceDO.DISABLED_REASON_AUTO)
                .orderByAsc(NewsSourceDO::getSourceKey));
        if (candidates.isEmpty()) {
            return new ProbeSweepResult(0, 0);
        }
        int probed = 0;
        int recovered = 0;
        for (NewsSourceDO source : candidates) {
            if (probedToday(source, now)) {
                continue; // 日级节拍：每源每日至多一次（跨轮重复触发只补漏）
            }
            NewsFetchService.SourceFetchResult result = fetchService.fetch(source);
            probed++;
            if (recordProbeOutcome(source, result, now)) {
                recovered++;
            }
        }
        log.info("[news] 探活扫描：{} 个自动隔离源，本轮探活 {} 个，复归 {} 个",
                candidates.size(), probed, recovered);
        return new ProbeSweepResult(probed, recovered);
    }

    /**
     * 单源探活结果记账。返回是否触发复归。
     *
     * <p>语义：有效两类=streak +1（上一成功超 48h 窗口则重起 1），达阈值（默认 2）复归；
     * 结构失配/网络失败=streak 清零（解析失配不可凭成功复归——它本身就是探活失败）；
     * 策略禁止=转 'policy' 停用并退出探活；defer=不计尝试（probe_time 不推进，streak 不变）。
     */
    private boolean recordProbeOutcome(NewsSourceDO source, NewsFetchService.SourceFetchResult result,
                                       Date now) {
        NewsFetchOutcome outcome = result.outcome();
        if (outcome.isValidSuccess()) {
            long windowMillis = properties.effectiveProbeSuccessWindowHours() * 3600_000L;
            boolean continuous = source.getProbeTime() != null
                    && now.getTime() - source.getProbeTime().getTime() <= windowMillis;
            int streak = (continuous ? nvl(source.getProbeSuccesses()) : 0) + 1;
            if (streak >= properties.effectiveProbeRequiredSuccesses()) {
                recover(source, outcome, streak, now);
                return true;
            }
            sourceMapper.update(null, Wrappers.lambdaUpdate(NewsSourceDO.class)
                    .eq(NewsSourceDO::getId, source.getId())
                    .set(NewsSourceDO::getProbeSuccesses, streak)
                    .set(NewsSourceDO::getProbeTime, now)
                    .set(NewsSourceDO::getLastOutcome, outcome.code())
                    .set(NewsSourceDO::getLastOutcomeTime, now)
                    .set(NewsSourceDO::getUpdateTime, now));
            appendEvent(source, NewsSourceHealthEventDO.TYPE_PROBE_PASS, outcome,
                    "连续有效完整成功 " + streak + "/" + properties.effectiveProbeRequiredSuccesses()
                            + "（" + result.detail() + "）", now);
            mirror(source, streak, now, outcome);
            log.info("[news] 源 {} 探活通过（连续有效完整成功 {}/{}）：{}",
                    source.getSourceKey(), streak, properties.effectiveProbeRequiredSuccesses(), result.detail());
            return false;
        }
        switch (outcome) {
            case STRUCTURE_MISMATCH, NETWORK_FAILURE -> {
                sourceMapper.update(null, Wrappers.lambdaUpdate(NewsSourceDO.class)
                        .eq(NewsSourceDO::getId, source.getId())
                        .set(NewsSourceDO::getProbeSuccesses, 0)
                        .set(NewsSourceDO::getProbeTime, now)
                        .set(NewsSourceDO::getLastOutcome, outcome.code())
                        .set(NewsSourceDO::getLastOutcomeTime, now)
                        .set(NewsSourceDO::getUpdateTime, now));
                appendEvent(source, NewsSourceHealthEventDO.TYPE_PROBE_FAIL, outcome, result.detail(), now);
                mirror(source, 0, now, outcome);
                log.info("[news] 源 {} 探活失败（{}）：{}", source.getSourceKey(), outcome.code(), result.detail());
            }
            case POLICY_FORBIDDEN -> {
                // robots 已 Disallow/守卫拒绝：转策略停用退出探活（不因可达自动解禁）
                disableForPolicy(source, result.detail(), now, outcome);
                sourceMapper.update(null, Wrappers.lambdaUpdate(NewsSourceDO.class)
                        .eq(NewsSourceDO::getId, source.getId())
                        .set(NewsSourceDO::getProbeTime, now)
                        .set(NewsSourceDO::getUpdateTime, now));
                mirror(source, 0, now, outcome);
                source.setProbeTime(now);
            }
            case DEFER -> {
                // defer 零计数：不是失败也不是成功——不计尝试（probe_time 不推进），
                // 留待下一轮；源仍保持 'auto' 探活资格
                log.info("[news] 源 {} 探活 defer（本轮未探成，不计尝试不重置连续成功）：{}",
                        source.getSourceKey(), result.detail());
                source.setLastOutcome(outcome.code());
                source.setLastOutcomeTime(now);
            }
            default -> log.warn("[news] 源 {} 探活结果 {} 未接线（忽略）", source.getSourceKey(), outcome);
        }
        return false;
    }

    /**
     * 复归（两次有效完整成功达成）：enabled=true + disabled_reason=NULL + 滞回清零 +
     * recovered_time + 事件。<b>复归入统一门（#186 范围 5）</b>：本方法只翻开关——
     * 探活候选即弃未入库，复归源的新内容经下一常规轮 admitAll（#185 全站/单源日准入）
     * 与富化预算（#184）进入管线，无绕门路径。
     */
    private void recover(NewsSourceDO source, NewsFetchOutcome outcome, int streak, Date now) {
        sourceMapper.update(null, Wrappers.lambdaUpdate(NewsSourceDO.class)
                .eq(NewsSourceDO::getId, source.getId())
                .set(NewsSourceDO::getEnabled, true)
                .set(NewsSourceDO::getDisabledReason, null)
                .set(NewsSourceDO::getConsecutiveFailures, 0)
                .set(NewsSourceDO::getProbeSuccesses, streak)
                .set(NewsSourceDO::getProbeTime, now)
                .set(NewsSourceDO::getRecoveredTime, now)
                .set(NewsSourceDO::getLastOutcome, outcome.code())
                .set(NewsSourceDO::getLastOutcomeTime, now)
                .set(NewsSourceDO::getUpdateTime, now));
        appendEvent(source, NewsSourceHealthEventDO.TYPE_RECOVERED, outcome,
                "连续 " + streak + " 次有效完整成功（间隔 ≤" + properties.effectiveProbeSuccessWindowHours()
                        + "h），自动复归——内容经常规轮 #185 准入与 #184 预算进入管线", now);
        source.setEnabled(true);
        source.setDisabledReason(null);
        source.setConsecutiveFailures(0);
        source.setProbeSuccesses(streak);
        source.setProbeTime(now);
        source.setRecoveredTime(now);
        source.setLastOutcome(outcome.code());
        source.setLastOutcomeTime(now);
        log.info("[news] 源 {} 探活复归（连续 {} 次有效完整成功，≤{}h 窗口）：下一常规轮起进 #185 准入与 #184 预算",
                source.getSourceKey(), streak, properties.effectiveProbeSuccessWindowHours());
    }

    /**
     * 失败滞回 +1（结构失配/网络失败）；连续 ≥3 自动隔离（reason='auto'，探活对象）
     */
    private void recordHysteresisFailure(NewsSourceDO source, NewsFetchOutcome outcome,
                                         String detail, Date now) {
        int failures = nvl(source.getConsecutiveFailures()) + 1;
        boolean isolate = failures >= FAILURE_THRESHOLD;
        var update = Wrappers.lambdaUpdate(NewsSourceDO.class)
                .eq(NewsSourceDO::getId, source.getId())
                .set(NewsSourceDO::getConsecutiveFailures, failures)
                .set(NewsSourceDO::getLastOutcome, outcome.code())
                .set(NewsSourceDO::getLastOutcomeTime, now)
                .set(NewsSourceDO::getUpdateTime, now);
        if (isolate) {
            update.set(NewsSourceDO::getEnabled, false)
                    .set(NewsSourceDO::getDisabledReason, NewsSourceDO.DISABLED_REASON_AUTO)
                    .set(NewsSourceDO::getIsolatedTime, now)
                    .set(NewsSourceDO::getProbeSuccesses, 0);
        }
        sourceMapper.update(null, update);
        source.setConsecutiveFailures(failures);
        source.setLastOutcome(outcome.code());
        source.setLastOutcomeTime(now);
        if (isolate) {
            source.setEnabled(false);
            source.setDisabledReason(NewsSourceDO.DISABLED_REASON_AUTO);
            source.setIsolatedTime(now);
            source.setProbeSuccesses(0);
            appendEvent(source, NewsSourceHealthEventDO.TYPE_ISOLATED, outcome,
                    "连续 " + failures + " 次失败（" + outcome.code() + "）自动隔离：" + brief(detail), now);
            log.error("[news] 源 {} 连续 {} 次失败，自动隔离（disabled_reason=auto，进日级探活）：{}",
                    source.getSourceKey(), failures, detail);
        } else {
            log.warn("[news] 源 {} 抓取失败（连续 {} 次，{}）：{}", source.getSourceKey(), failures,
                    outcome.code(), detail);
        }
    }

    /**
     * 策略禁止转停（reason='policy'）：robots Disallow/出站守卫拒绝——立即停（无三败滞回：
     * 不是源故障）、退出探活（政策禁止不因可达自动解禁，复归=人工；robots 缓存有 TTL，
     * 人工复归后会重读 robots）
     */
    private void disableForPolicy(NewsSourceDO source, String detail, Date now, NewsFetchOutcome outcome) {
        sourceMapper.update(null, Wrappers.lambdaUpdate(NewsSourceDO.class)
                .eq(NewsSourceDO::getId, source.getId())
                .set(NewsSourceDO::getEnabled, false)
                .set(NewsSourceDO::getDisabledReason, NewsSourceDO.DISABLED_REASON_POLICY)
                .set(NewsSourceDO::getIsolatedTime, now)
                .set(NewsSourceDO::getProbeSuccesses, 0)
                .set(NewsSourceDO::getLastOutcome, outcome.code())
                .set(NewsSourceDO::getLastOutcomeTime, now)
                .set(NewsSourceDO::getUpdateTime, now));
        appendEvent(source, NewsSourceHealthEventDO.TYPE_POLICY_DISABLED, outcome, brief(detail), now);
        source.setEnabled(false);
        source.setDisabledReason(NewsSourceDO.DISABLED_REASON_POLICY);
        source.setIsolatedTime(now);
        source.setProbeSuccesses(0);
        source.setLastOutcome(outcome.code());
        source.setLastOutcomeTime(now);
        log.warn("[news] 源 {} 策略禁止转停（disabled_reason=policy，不探活不因可达解禁，复归=人工）：{}",
                source.getSourceKey(), detail);
    }

    /**
     * 事件流水（append-only，停止/复归/探活记录可查）
     */
    private void appendEvent(NewsSourceDO source, String eventType, NewsFetchOutcome outcome,
                             String detail, Date now) {
        try {
            eventMapper.insert(NewsSourceHealthEventDO.builder()
                    .sourceId(source.getId())
                    .eventType(eventType)
                    .outcome(outcome.code())
                    .detail(brief(detail))
                    .eventTime(now)
                    .build());
        } catch (Exception e) {
            // 审计流水不阻断主管线（下轮健康状态仍可从 t_news_source 读）
            log.error("[news] 源 {} 健康事件落库失败（不阻断）：{}", source.getSourceKey(), e.getMessage());
        }
    }

    /**
     * 仅落 last_outcome 的最小更新（defer 等不触碰滞回/开关的结果）
     */
    private com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<NewsSourceDO>
    baseOutcomeUpdate(NewsSourceDO source, NewsFetchOutcome outcome, Date now) {
        return Wrappers.lambdaUpdate(NewsSourceDO.class)
                .eq(NewsSourceDO::getId, source.getId())
                .set(NewsSourceDO::getLastOutcome, outcome.code())
                .set(NewsSourceDO::getLastOutcomeTime, now)
                .set(NewsSourceDO::getUpdateTime, now);
    }

    /**
     * 探活记账后镜像回 DO（调用方与后续轮次读到一致内存态）
     */
    private void mirror(NewsSourceDO source, int streak, Date now, NewsFetchOutcome outcome) {
        source.setProbeSuccesses(streak);
        source.setProbeTime(now);
        source.setLastOutcome(outcome.code());
        source.setLastOutcomeTime(now);
    }

    /**
     * 日级节拍：源今天（HKT）已探过则跳过（probe_time 与 now 同 HKT 日=已探）
     */
    private static boolean probedToday(NewsSourceDO source, Date now) {
        if (source.getProbeTime() == null) {
            return false;
        }
        LocalDate probedDay = NewsLlmBudgetService.todayDateKey(source.getProbeTime());
        LocalDate today = NewsLlmBudgetService.todayDateKey(now);
        return probedDay.equals(today);
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }

    private static String brief(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > DETAIL_MAX ? message.substring(0, DETAIL_MAX) : message;
    }
}
