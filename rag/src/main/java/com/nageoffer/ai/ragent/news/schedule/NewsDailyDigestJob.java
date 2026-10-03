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

package com.nageoffer.ai.ragent.news.schedule;

import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.service.IndexNowService;
import com.nageoffer.ai.ragent.news.service.NewsDailyDigestService;
import com.nageoffer.ai.ragent.news.service.impl.NewsDailyDigestTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * 资讯日报定时生成任务（#212，父票 #182 r3 §日报——P2-a 出口）
 *
 * <p><b>调度时刻=08:40 HKT</b>（cron 外置 rag.news.digest-cron，6 段 cron 沿
 * NewsFetchJob 判例）：08:00 采集轮（fetch-cron 三轮之首）已收尾、08:30 探活
 * （probe-cron）也已让位——三者错峰不抢行；窗口 [D-1 08:00, D 08:00) 此刻
 * 已闭合，迟到富化中（pending）的窗口条目按冻结的迟到数据规则不回捞
 * （见 NewsDailyDigestServiceImpl 类 javadoc）。
 *
 * <p><b>漏跑补齐</b>：每轮检查近 {@code rag.news.digest-backfill-days}（默认 2，
 * 当日+昨日）个 HKT 日期，缺失的补生成（幂等：已存在的刊零动作——自动重建
 * 会覆盖导语回执复用语义，重建只由显式 rebuildForDate 触发）。单日宕机场景
 * 次日自动补上昨日刊；跨多日漏跑由维护者显式补跑。
 *
 * <p>flag rag.news.enabled 关（默认）时本组件不装配，无任何调度行为。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.news.enabled", havingValue = "true")
public class NewsDailyDigestJob {

    private final NewsDailyDigestService digestService;
    private final NewsFetchProperties properties;
    private final IndexNowService indexNowService;

    public NewsDailyDigestJob(NewsDailyDigestService digestService, NewsFetchProperties properties,
                              IndexNowService indexNowService) {
        this.digestService = digestService;
        this.properties = properties;
        this.indexNowService = indexNowService;
    }

    /**
     * 日刊生成+漏跑补齐：08:40 HKT（窗口 [D-1 08:00, D 08:00) 闭合后）
     */
    @Scheduled(cron = "${rag.news.digest-cron:0 40 8 * * *}")
    public void generateDailyDigest() {
        LocalDate today = ZonedDateTime.now(NewsDailyDigestTemplates.HKT_ZONE).toLocalDate();
        int backfillDays = properties.effectiveDigestBackfillDays();
        int built = 0;
        for (int offset = backfillDays - 1; offset >= 0; offset--) {
            LocalDate date = today.minusDays(offset);
            try {
                if (digestService.generateIfMissing(date)) {
                    built++;
                    submitToIndexNow(date);
                }
            } catch (Exception e) {
                // 逐日期隔离：单日失败不阻断其余日期补齐（下一轮调度再试）
                log.error("[news][daily] 日报 {} 生成失败（不影响其余日期）：{}", date, e.getMessage(), e);
            }
        }
        if (built > 0) {
            log.info("[news][daily] 本轮日报调度：新生成 {} 刊（检查近 {} 日）", built, backfillDays);
        }
    }

    /**
     * IndexNow 提交钩（#213）：新刊生成后提交 /daily 与 /daily/{date} 两个本站
     * canonical URL——调度层调用（generateIfMissing 事务已提交，外网调用不入事务）；
     * 失败由服务内吞噬（收录=观察项，票面非验收门）。条目详情页不随刊提交
     * （入口由 sitemap 覆盖，避免逐条 ping 噪音）
     */
    private void submitToIndexNow(LocalDate digestDate) {
        try {
            indexNowService.submitSiteUrls(java.util.List.of("/daily", "/daily/" + digestDate));
        } catch (Exception e) {
            log.warn("[news][daily] 日报 {} 的 IndexNow 提交钩失败（不影响出刊）：{}", digestDate, e.getMessage());
        }
    }
}
