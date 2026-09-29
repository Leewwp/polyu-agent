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

package com.nageoffer.ai.ragent.calendar.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateSourceDO;
import com.nageoffer.ai.ragent.calendar.dao.mapper.KeyDateSourceMapper;
import com.nageoffer.ai.ragent.calendar.parse.AcademicCalendarParser;
import com.nageoffer.ai.ragent.calendar.parse.AssessmentResultsParser;
import com.nageoffer.ai.ragent.calendar.parse.CalendarHtmlTables;
import com.nageoffer.ai.ragent.calendar.parse.CalendarPageParser;
import com.nageoffer.ai.ragent.calendar.parse.ExamTimetableParser;
import com.nageoffer.ai.ragent.calendar.parse.FeePaymentAnnualParser;
import com.nageoffer.ai.ragent.calendar.parse.SourceGate;
import com.nageoffer.ai.ragent.calendar.parse.TimetableExamResultsParser;
import com.nageoffer.ai.ragent.calendar.sync.KeyDateSyncService;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchDeferredException;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchException;
import com.nageoffer.ai.ragent.news.fetch.NewsHttpFetchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 校历日级同步任务（合同§6：每源日级抓取，共享 host 节拍，串行，无新调度框架）。
 *
 * <p>轮内逐源：人工停用（enabled=0）直接跳过（不探测不复活）→ auto_isolated
 * 隔离期只读探测（抓取解析但<b>不发布</b>候选，两次完整后恢复）→ 完整候选
 * 原子发布 / 退化零事件写。HTTP 只读复用 {@link NewsHttpFetchClient} 的 URL
 * 守卫/robots/节拍能力（#180 §4 串行契约的只读复用例外；不并改该文件）。
 *
 * <p>flag rag.calendar.enabled 关（默认）时本组件不装配——生产启停归维护者
 * （先例 RAG_NEWS_ENABLED）。verifier 源（cal-exam-timetable）零事件写径：
 * 只记录跨源校验 discrepancy。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.calendar.enabled", havingValue = "true")
public class KeyDateSyncJob {

    /**
     * 五源解析器注册表（顺序=合同§2 表序）
     */
    private static final Map<String, CalendarPageParser> PARSERS = Map.of(
            "cal-academic-calendar", new AcademicCalendarParser(),
            "cal-fee-payment-annual", new FeePaymentAnnualParser(),
            "cal-timetable-exam-results", new TimetableExamResultsParser(),
            "cal-exam-timetable", new ExamTimetableParser(),
            "cal-assessment-results", new AssessmentResultsParser());

    private final KeyDateSourceMapper sourceMapper;
    private final KeyDateSyncService syncService;
    private final NewsHttpFetchClient fetchClient;

    @Autowired
    public KeyDateSyncJob(KeyDateSourceMapper sourceMapper,
                          KeyDateSyncService syncService,
                          NewsHttpFetchClient fetchClient) {
        this.sourceMapper = sourceMapper;
        this.syncService = syncService;
        this.fetchClient = fetchClient;
    }

    /**
     * 日级同步；cron 外置 rag.calendar.sync-cron（默认每日 07:30 HKT 服务器时区）
     */
    @Scheduled(cron = "${rag.calendar.sync-cron:0 30 7 * * *}")
    public void syncAllSources() {
        List<KeyDateSourceDO> sources = sourceMapper.selectList(new LambdaQueryWrapper<KeyDateSourceDO>()
                .eq(KeyDateSourceDO::getEnabled, "1"));
        if (sources.isEmpty()) {
            log.info("[calendar] 无启用校历源，本轮跳过");
            return;
        }
        log.info("[calendar] 同步轮启动：{} 个启用源", sources.size());
        for (KeyDateSourceDO source : sources) {
            try {
                syncSource(source);
            } catch (NewsFetchDeferredException deferred) {
                log.info("[calendar] 源 {} 本轮 defer（守卫客户端节拍豁免）：{}",
                        source.getSourceKey(), deferred.getMessage());
            } catch (NewsFetchException e) {
                // 抓取失败=退化路径（零事件写、保留最后完整版本、状态机计退化）
                log.warn("[calendar] 源 {} 抓取失败（按退化轮处理）：{}", source.getSourceKey(), e.getMessage());
                syncService.applyDegraded(source, List.of("抓取失败：" + e.getMessage()));
            } catch (Exception e) {
                log.error("[calendar] 源 {} 同步异常（隔离计失败轮，不阻断其余源）",
                        source.getSourceKey(), e);
                syncService.applyDegraded(source, List.of("同步异常：" + e.getMessage()));
            }
        }
    }

    /**
     * 单源同步：抓取 → 解析+门禁 → 探测/退化/发布分流 → 校验片段核对
     * （包外可见=调度分流测试直测）
     */
    public void syncSource(KeyDateSourceDO source) {
        CalendarPageParser parser = PARSERS.get(source.getSourceKey());
        if (parser == null) {
            log.error("[calendar] 源 {} 无注册解析器，跳过（词表/解析器合同未覆盖）", source.getSourceKey());
            return;
        }
        boolean probeOnly = "auto_isolated".equals(source.getAutoState());
        String html = new String(fetchClient.get(source.getSourceUrl()), StandardCharsets.UTF_8);
        SourceGate.GateResult gate = SourceGate.evaluate(parser,
                CalendarHtmlTables.mainRows(html), CalendarHtmlTables.visibleText(html));

        if (probeOnly) {
            // 隔离期只读探测：不发布候选（两次完整后恢复，下一轮正常发布）
            syncService.recordProbe(source, gate.degraded());
            log.info("[calendar] 源 {} 隔离期探测（degraded={}，不发布）", source.getSourceKey(), gate.degraded());
            return;
        }
        if (gate.degraded()) {
            syncService.applyDegraded(source, gate.reasons());
            return;
        }
        String coverageAy = CalendarPageParser.pageAy(source.getSourceKey(),
                CalendarHtmlTables.visibleText(html), CalendarHtmlTables.mainRows(html));
        if ("verifier".equals(source.getRole())) {
            // 纯校验源零写径：只记录 discrepancy
            List<String[]> disc = syncService.recordDiscrepancies(source, gate.candidates());
            log.info("[calendar] 校验源 {} 核对毕：{} 条 discrepancy", source.getSourceKey(), disc.size());
            syncService.recordProbe(source, false);
            return;
        }
        syncService.applyComplete(source, coverageAy, gate.events());
        if (gate.candidates().stream().anyMatch(c -> c.getDisposition() == com.nageoffer.ai.ragent.calendar.model.Disposition.VERIFY)) {
            syncService.recordDiscrepancies(source, gate.candidates());
        }
    }
}
