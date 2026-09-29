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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.news.controller.vo.NewsLlmBudgetStatusVO;
import com.nageoffer.ai.ragent.news.controller.vo.NewsPipelineStatusVO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemStatus;
import com.nageoffer.ai.ragent.news.dao.entity.NewsLlmReceiptDO;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsItemMapper;
import com.nageoffer.ai.ragent.news.dao.mapper.NewsLlmReceiptMapper;
import com.nageoffer.ai.ragent.news.fetch.NewsFetchProperties;
import com.nageoffer.ai.ragent.news.service.NewsAdminService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 资讯管理面服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsAdminServiceImpl implements NewsAdminService {

    /**
     * 资讯管线统一时区（HKT，沿 NewsLlmBudgetService/NewsFetchService 先例）
     */
    private static final ZoneId HKT_ZONE = ZoneId.of("Asia/Hong_Kong");

    private final NewsItemMapper itemMapper;
    private final NewsLlmReceiptMapper receiptMapper;
    private final NewsFetchProperties fetchProperties;

    @Override
    public void hide(Long id) {
        int rows = itemMapper.update(null, Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getId, id)
                .eq(NewsItemDO::getStatus, NewsItemStatus.PUBLISHED)
                .set(NewsItemDO::getStatus, NewsItemStatus.HIDDEN));
        if (rows == 0) {
            // 幂等语义：仅 published → hidden 会产生变更；hidden 重复下架与不存在同样 0 行，
            // 先查一次区分两种语义给出准确反馈
            Long exists = itemMapper.selectCount(Wrappers.lambdaQuery(NewsItemDO.class)
                    .eq(NewsItemDO::getId, id));
            if (exists != null && exists > 0) {
                log.info("[news] 条目 {} 已是 hidden，重复下架忽略", id);
                return;
            }
            throw new ClientException("条目不存在");
        }
        log.info("[news] 条目 {} 已下架（published → hidden）", id);
    }

    /**
     * 管线状态与六口径日报（#185）：三段聚合（详见 NewsPipelineStatusVO 语义注记）——
     * ①fetch_time 日窗（发现/准入/唯一内容/归档）②eligible_time 日窗（富化成功/
     * 零调用回退/公开展示——公开展示叠加发布门快照判据）③实时积压（pending/expired）。
     * 全部从 t_news_item 现推，重启不重置
     */
    @Override
    public NewsPipelineStatusVO pipelineStatus(LocalDate date) {
        Date now = new Date();
        LocalDate today = NewsLlmBudgetService.todayDateKey(now);
        LocalDate reportDay = date != null ? date : today;
        ZonedDateTime dayStartHkt = reportDay.atStartOfDay(HKT_ZONE);
        Date dayStart = Date.from(dayStartHkt.toInstant());
        Date dayEnd = Date.from(dayStartHkt.plusDays(1).toInstant());
        Date gateFloor = new Date(now.getTime() - fetchProperties.effectivePublishGateSeconds() * 1000L);
        Map<String, Object> fetchDay = firstRow(itemMapper.selectMaps(new QueryWrapper<NewsItemDO>()
                .select("COUNT(*) AS discovered",
                        "COUNT(*) FILTER (WHERE status <> 'archived') AS admitted",
                        "COUNT(DISTINCT COALESCE(NULLIF(LOWER(TRIM(title_en)), ''), "
                                + "NULLIF(LOWER(TRIM(title_zh)), ''))) FILTER (WHERE status <> 'archived') AS unique_content",
                        "COUNT(*) FILTER (WHERE status = 'archived') AS archived_today")
                .ge("fetch_time", dayStart)
                .lt("fetch_time", dayEnd)));
        // 资格日窗口量级 ≤日准入（60）+回退数，拉行内存计数（发布门快照判据不出 SQL 字面量）
        List<NewsItemDO> eligibleDayRows = itemMapper.selectList(Wrappers.lambdaQuery(NewsItemDO.class)
                .select(NewsItemDO::getStatus, NewsItemDO::getSummarySource, NewsItemDO::getEligibleTime)
                .ge(NewsItemDO::getEligibleTime, dayStart)
                .lt(NewsItemDO::getEligibleTime, dayEnd));
        long enrichedLlm = 0;
        long fallbackToday = 0;
        long publicVisible = 0;
        for (NewsItemDO row : eligibleDayRows) {
            boolean llm = NewsItemStatus.SUMMARY_SOURCE_LLM.equals(row.getSummarySource());
            if (llm) {
                enrichedLlm++;
            } else if (NewsItemStatus.SUMMARY_SOURCE_FALLBACK.equals(row.getSummarySource())) {
                fallbackToday++;
            }
            if (NewsItemStatus.PUBLISHED.equals(row.getStatus())
                    && row.getEligibleTime() != null && !row.getEligibleTime().after(gateFloor)) {
                publicVisible++;
            }
        }
        Map<String, Object> backlog = firstRow(itemMapper.selectMaps(new QueryWrapper<NewsItemDO>()
                .select("COUNT(*) FILTER (WHERE status = 'pending') AS pending_now",
                        "COUNT(*) FILTER (WHERE status = 'expired') AS expired_total")));
        int siteCap = fetchProperties.effectiveAdmissionDailySiteCap();
        long admitted = longOf(fetchDay, "admitted");
        return NewsPipelineStatusVO.builder()
                .date(reportDay)
                .gateSeconds(fetchProperties.effectivePublishGateSeconds())
                .siteDailyCap(siteCap)
                .discovered(longOf(fetchDay, "discovered"))
                .admitted(admitted)
                .uniqueContent(longOf(fetchDay, "unique_content"))
                .enrichedLlm(enrichedLlm)
                .publicVisible(publicVisible)
                .eventCount(0L)
                .archivedToday(longOf(fetchDay, "archived_today"))
                .fallbackToday(fallbackToday)
                .pendingNow(longOf(backlog, "pending_now"))
                .expiredTotal(longOf(backlog, "expired_total"))
                .siteRemainingToday(Math.max(0L, siteCap - admitted))
                .build();
    }

    /**
     * selectMaps 单行提取（聚合恒返回一行；空集防御）
     */
    private static Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() || rows.get(0) == null ? Map.of() : rows.get(0);
    }

    /**
     * 聚合数值提取（PG COUNT/FILTER 聚合恒非 NULL；防御缺列=0）
     */
    private static long longOf(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    @Override
    public NewsLlmBudgetStatusVO llmBudgetStatus() {
        Date now = new Date();
        LocalDate today = NewsLlmBudgetService.todayDateKey(now);
        String month = NewsLlmBudgetService.monthKey(now);
        Map<String, Object> daily = aggregate(today, "stat_date");
        Map<String, Object> monthly = aggregate(month, "stat_month");
        Long degraded = receiptMapper.selectCount(new QueryWrapper<NewsLlmReceiptDO>()
                .eq("stat_date", today)
                .eq("status", NewsLlmBudgetService.STATUS_DEGRADED));
        BigDecimal dailyLimit = BigDecimal.valueOf(fetchProperties.getBudgetDailyYuan());
        BigDecimal monthlyLimit = BigDecimal.valueOf(fetchProperties.getBudgetMonthlyYuan());
        return NewsLlmBudgetStatusVO.builder()
                .dailyCostYuan(costOf(daily))
                .dailyAttempts(attemptsOf(daily))
                .dailyDegraded(degraded == null ? 0L : degraded)
                .dailyLimitYuan(dailyLimit)
                .dailyRemainingYuan(dailyLimit.subtract(costOf(daily)))
                .monthlyCostYuan(costOf(monthly))
                .monthlyAttempts(attemptsOf(monthly))
                .monthlyLimitYuan(monthlyLimit)
                .monthlyRemainingYuan(monthlyLimit.subtract(costOf(monthly)))
                .build();
    }

    /**
     * 按日/月键聚合成本与 attempts（数据源 t_news_llm_receipt，重启不清零）。
     * stat_date 键为 LocalDate（PG DATE 原生映射，#184 修正点6 实证）、stat_month 为字符串
     */
    private Map<String, Object> aggregate(Object key, String column) {
        List<Map<String, Object>> rows = receiptMapper.selectMaps(new QueryWrapper<NewsLlmReceiptDO>()
                .select("COALESCE(SUM(cost_estimate), 0) AS cost_sum",
                        "COALESCE(SUM(attempts), 0) AS attempt_sum")
                .eq(column, key));
        return rows == null || rows.isEmpty() || rows.get(0) == null ? Map.of() : rows.get(0);
    }

    private static BigDecimal costOf(Map<String, Object> row) {
        Object value = row.get("cost_sum");
        return value == null ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP)
                : new BigDecimal(String.valueOf(value));
    }

    private static Long attemptsOf(Map<String, Object> row) {
        Object value = row.get("attempt_sum");
        return value == null ? 0L : Long.parseLong(String.valueOf(value));
    }
}
