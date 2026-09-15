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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JSON_API 回溯月数测试（历史回灌口径，NewsFetchProperties.events-past-months）：
 * 默认 0=当前月+下月两请求（现行口径）；&gt;0 时向前多取 N 个月；历史月零活动
 * 宽和不炸源（当前/下月仍 fail-closed）。
 */
class EventsApiNewsFetcherTests {

    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");
    private static final String ENDPOINT =
            "https://www.polyu.edu.hk/en/api/sitecore/calendar/get?id=F45B&date=YYYY/MM";

    private NewsHttpFetchClient fetchClient;
    private NewsFetchProperties properties;
    private EventsApiNewsFetcher fetcher;
    private NewsSourceDO source;

    @BeforeEach
    void setUp() {
        fetchClient = mock(NewsHttpFetchClient.class);
        properties = new NewsFetchProperties();
        fetcher = new EventsApiNewsFetcher(fetchClient, properties);
        source = NewsSourceDO.builder()
                .id(1L).sourceKey("events").platform("official")
                .fetchEndpoint(ENDPOINT)
                .fetchStrategy("JSON_API").enabled(true).build();
    }

    private static byte[] eventsJson(String date, String slug) {
        String json = "{\"events\":[{\"title\":\"PolyU Event " + slug + "\","
                + "\"start-date\":\"" + date + "\",\"type\":\"seminar\","
                + "\"content\":\"<a href=\\\"https://www.polyu.edu.hk/events/" + slug + "/\\\">detail</a>\"}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String monthToken(LocalDate date) {
        return date.withDayOfMonth(1).toString().substring(0, 7).replace('-', '/');
    }

    private static List<String> expectedMonths(int pastMonths) {
        LocalDate now = LocalDate.now(HKT);
        return java.util.stream.IntStream.rangeClosed(-pastMonths, 1)
                .mapToObj(offset -> monthToken(now.plusMonths(offset)))
                .toList();
    }

    @Test
    void defaultFetchesCurrentAndNextMonthOnly() {
        when(fetchClient.get(anyString()))
                .thenReturn(eventsJson("2026-09-15", "ev-current"), eventsJson("2026-10-05", "ev-next"));

        List<RawNewsItem> items = fetcher.fetch(source);

        List<String> months = expectedMonths(0);
        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(fetchClient, times(2)).get(urls.capture());
        assertEquals(ENDPOINT.replace("YYYY/MM", months.get(0)), urls.getAllValues().get(0));
        assertEquals(ENDPOINT.replace("YYYY/MM", months.get(1)), urls.getAllValues().get(1));
        assertEquals(2, items.size());
    }

    @Test
    void pastMonthsWideningFetchesHistoricalMonths() {
        properties.setEventsPastMonths(2);
        when(fetchClient.get(anyString()))
                .thenReturn(eventsJson("2026-07-08", "ev-jul"), eventsJson("2026-08-20", "ev-aug"),
                        eventsJson("2026-09-15", "ev-current"), eventsJson("2026-10-05", "ev-next"));

        List<RawNewsItem> items = fetcher.fetch(source);

        List<String> months = expectedMonths(2);
        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(fetchClient, times(4)).get(urls.capture());
        assertEquals(months.stream().map(m -> ENDPOINT.replace("YYYY/MM", m)).toList(),
                urls.getAllValues());
        assertEquals(4, items.size());
    }

    @Test
    void emptyHistoricalMonthIsToleratedWhileCurrentMonthStaysFailClosed() {
        properties.setEventsPastMonths(1);
        // 历史月 events 空数组：宽和收空；当前月有数据
        when(fetchClient.get(anyString()))
                .thenReturn("{\"events\":[]}".getBytes(StandardCharsets.UTF_8),
                        eventsJson("2026-09-15", "ev-current"), eventsJson("2026-10-05", "ev-next"));

        List<RawNewsItem> items = fetcher.fetch(source);

        assertEquals(2, items.size());
    }

    @Test
    void emptyCurrentMonthStillFailsClosed() {
        when(fetchClient.get(anyString()))
                .thenReturn("{\"events\":[]}".getBytes(StandardCharsets.UTF_8));

        assertThrows(NewsFetchException.class, () -> fetcher.fetch(source));
    }
}
