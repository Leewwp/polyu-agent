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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON_API 型抓取器
 *
 * <p>官网活动日历 Sitecore API：fetch_endpoint 含 {@code date=YYYY/MM} 占位，
 * 每次跑当前月+下月两个请求（跨月活动防漏）；「今天」与月份切日统一 HKT
 * （+08:00）。回溯月数外置 {@link NewsFetchProperties#getEventsPastMonths()}
 * （默认 0=现行口径；历史回灌临时调大向前多取 N 个月）。
 * 「大写 Calendar 302」由 OkHttp 默认跟随重定向覆盖。
 */
@Component
@RequiredArgsConstructor
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

    @Override
    public String supportedStrategy() {
        return "JSON_API";
    }

    @Override
    public List<RawNewsItem> fetch(NewsSourceDO source) {
        String endpoint = source.getFetchEndpoint();
        if (!endpoint.contains(MONTH_PLACEHOLDER)) {
            throw new NewsFetchException("events 端点缺少 " + MONTH_PLACEHOLDER + " 占位: "
                    + source.getSourceKey(), false);
        }
        ZonedDateTime now = ZonedDateTime.now(HKT);
        int pastMonths = Math.max(0, properties.getEventsPastMonths());
        List<RawNewsItem> items = new ArrayList<>();
        Map<String, RawNewsItem> byUrl = new LinkedHashMap<>();
        for (int offset = -pastMonths; offset <= 1; offset++) {
            String month = monthToken(now.plusMonths(offset));
            byte[] json = fetchClient.get(endpoint.replace(MONTH_PLACEHOLDER, month));
            // 历史回溯月零活动属正常（宽和）；当前/下月零条目仍 fail-closed
            for (NewsEventsJsonParser.EventEntry entry : NewsEventsJsonParser.parse(json, offset >= 0)) {
                String url = NewsUrlNormalizer.normalize(entry.link());
                byUrl.putIfAbsent(url, new RawNewsItem(url, NewsUrlNormalizer.urlHash(url), entry.title(),
                        null, "en", entry.start(), entry.typeHint(), source.getSourceKey()));
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
}
