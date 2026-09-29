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
import com.nageoffer.ai.ragent.news.dao.entity.NewsItemDO;
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

    private final NewsItemMapper itemMapper;
    private final NewsLlmReceiptMapper receiptMapper;
    private final NewsFetchProperties fetchProperties;

    @Override
    public void hide(Long id) {
        int rows = itemMapper.update(null, Wrappers.lambdaUpdate(NewsItemDO.class)
                .eq(NewsItemDO::getId, id)
                .eq(NewsItemDO::getStatus, "published")
                .set(NewsItemDO::getStatus, "hidden"));
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
