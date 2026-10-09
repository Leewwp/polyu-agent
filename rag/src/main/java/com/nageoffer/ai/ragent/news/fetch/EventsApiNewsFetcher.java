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

package com.nageoffer.ai.ragent.news.fetch;

import com.nageoffer.ai.ragent.news.dao.entity.NewsSourceDO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * JSON_API 型抓取器
 *
 * <p>官网活动日历 Sitecore API：fetch_endpoint 含 {@code date=YYYY/MM} 占位，
 * <b>按月取数拼窗</b>（#323 扩窗）——月份集合=覆盖「今天起 events-window-weeks
 * 周（默认 8）」所需的全部月份（月中起跑跨 3 个自然月、月初起跑 2 个月，跨年
 * 边界自然拼接）。「今天」与月份切日统一 HKT（+08:00）；<b>跨月条目去重</b>：
 * 同一活动会在其覆盖的每个月份响应里重复出现（如 32nd Congregation 10-31~
 * 11-21 同时挂在 10/11 月响应），按规范化 URL 首见保留。
 *
 * <p><b>fail-closed 口径不随扩窗放大</b>（#186 源健康纪律）：仅当前月+下月维持
 * 「零条目=结构失配嫌疑」守卫；历史回溯月与 +2 月及以后的远端未来月零排期属
 * 日历常态（远期活动逐月释出），宽和收空不炸源——连败隔离/探活行为与扩窗前
 * 一致。回溯月数外置 {@link NewsFetchProperties#getEventsPastMonths()}
 * （默认 0；历史回灌临时调大向前多取 N 个月）。
 *
 * <p><b>活动起止透出</b>（#323 活动实体模型）：条目携带活动起止
 * （publishTime=开始、activityEnd=结束；无结束证据的条目 activityEnd=null 走
 * 纯资讯流），入库后经 t_news_item.activity_end_time 供日报装配层活动版面读取。
 * 「大写 Calendar 302」由 OkHttp 默认跟随重定向覆盖。
 */
@Component
public class EventsApiNewsFetcher implements NewsSourceFetcher {

    /**
     * endpoint 的月份占位（种子行口径）
     */
    static final String MONTH_PLACEHOLDER = "YYYY/MM";

    /**
     * 时区统一 HKT
     */
    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");

    private final NewsHttpFetchClient fetchClient;
    private final NewsFetchProperties properties;
    private final Supplier<ZonedDateTime> clock;

    @Autowired
    public EventsApiNewsFetcher(NewsHttpFetchClient fetchClient, NewsFetchProperties properties) {
        this(fetchClient, properties, () -> ZonedDateTime.now(HKT));
    }

    /**
     * 全参构造器（测试注入时钟，NewsDailyDigestServiceImpl 先例同源）
     */
    EventsApiNewsFetcher(NewsHttpFetchClient fetchClient, NewsFetchProperties properties,
                         Supplier<ZonedDateTime> clock) {
        this.fetchClient = fetchClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public String supportedStrategy() {
        return "JSON_API";
    }

    @Override
    public List<RawNewsItem> fetch(NewsSourceDO source) {
        String endpoint = source.getFetchEndpoint();
        if (!endpoint.contains(MONTH_PLACEHOLDER)) {
            // 本地配置失配（种子行缺占位）=结构失配类（#186 分类学），计入滞回
            throw new NewsFetchStructureException("events 端点缺少 " + MONTH_PLACEHOLDER + " 占位: "
                    + source.getSourceKey());
        }
        ZonedDateTime now = clock.get();
        int pastMonths = Math.max(0, properties.getEventsPastMonths());
        int futureMonths = monthsAheadForRollingWindow(now.toLocalDate(),
                properties.effectiveEventsWindowWeeks());
        // allow-empty 源（#186）：当前/下月零条目也按有效空收；历史回溯月与远端
        // 未来月本就宽和
        boolean allowEmpty = properties.isAllowEmptySource(source.getSourceKey());
        List<RawNewsItem> items = new ArrayList<>();
        Map<String, RawNewsItem> byUrl = new LinkedHashMap<>();
        for (int offset = -pastMonths; offset <= futureMonths; offset++) {
            String month = monthToken(now.plusMonths(offset));
            byte[] json = fetchClient.get(endpoint.replace(MONTH_PLACEHOLDER, month));
            // fail-closed 仅当前月(0)/下月(1)（扩窗前口径不变）；offset<0 历史回溯月
            // 与 offset>1 远端未来月零活动属正常（宽和收空，防远期零排期误炸源）
            for (NewsEventsJsonParser.EventEntry entry : NewsEventsJsonParser.parse(json,
                    (offset == 0 || offset == 1) && !allowEmpty)) {
                String url = NewsUrlNormalizer.normalize(entry.link());
                // #275：活动 start 时刻含义不改（非新闻发布时间），精度 unknown；
                // #323：起止成对透出（end 无证据=null 走纯资讯流）
                byUrl.putIfAbsent(url, new RawNewsItem(url, NewsUrlNormalizer.urlHash(url), entry.title(),
                        null, "en", entry.start(), entry.end(), entry.typeHint(), source.getSourceKey(),
                        PublishTimePrecision.UNKNOWN, null));
            }
        }
        items.addAll(byUrl.values());
        return items;
    }

    /**
     * 月份 token：YYYY/MM（LocalDate.toString 恰为 YYYY-MM-dd，切 / 为 - 前段）
     */
    private static String monthToken(ZonedDateTime month) {
        LocalDate firstDay = month.toLocalDate().withDayOfMonth(1);
        return firstDay.toString().substring(0, 7).replace('-', '/');
    }

    /**
     * 滚动窗口需向前取的月数：覆盖「今天起 windowWeeks 周」所需的全部自然月
     * （月首对齐的 MONTHS.between——如 10-09+8 周=12-04 → between(10-01,12-01)=2，
     * 即 offsets 0..2 拼 {10,11,12} 月；5-03+8 周=6-28 → 1，即 {5,6} 月不变）。
     * 纯函数便于跨月/跨年边界单测（#323）
     */
    static int monthsAheadForRollingWindow(LocalDate today, int windowWeeks) {
        LocalDate windowEnd = today.plusWeeks(windowWeeks);
        return (int) ChronoUnit.MONTHS.between(today.withDayOfMonth(1), windowEnd.withDayOfMonth(1));
    }
}
