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
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void allowEmptySourceToleratesEmptyCurrentAndNextMonth() {
        // #186 成功分类学：allow-empty 源的当前/下月零条目=有效空（VALID_EMPTY，源健康），
        // 不再 fail-closed 计结构失配
        properties.getAllowEmptySources().add("events");
        when(fetchClient.get(anyString()))
                .thenReturn("{\"events\":[]}".getBytes(StandardCharsets.UTF_8));

        List<RawNewsItem> items = fetcher.fetch(source);

        assertTrue(items.isEmpty(), "allow-empty 源零条目=空产出（非抛断）");
        verify(fetchClient, times(2)).get(anyString());
    }

    @Test
    void secondJsonApiSourceFetchesOwnCalendarEndpoint() {
        // #324：CPEO 文化活动日历与 events 同为 JSON_API——strategy 分发一对多，
        // 抓取器按源行 endpoint 取各自日历 id（BA1FFC…），条目归属各自 sourceKey。
        // 实查空月常态（2027/01、02 均 0 条）：cpeo-events 配 allow-empty（#186 成功
        // 分类学）——静月=VALID_EMPTY 非 fail-closed，否则空月连败会误触自动隔离
        properties.getAllowEmptySources().add("cpeo-events");
        String cpeoEndpoint = "https://www.polyu.edu.hk/en/api/sitecore/calendar/get"
                + "?id=BA1FFC08557D4D82A33C584551D93F99&date=YYYY/MM";
        NewsSourceDO cpeo = NewsSourceDO.builder()
                .id(2L).sourceKey("cpeo-events").platform("official")
                .fetchEndpoint(cpeoEndpoint)
                .fetchStrategy("JSON_API").enabled(true).build();
        String json = "{\"events\":[{\"title\":\"PolyU Cinema\","
                + "\"eventStartDate\":\"2026-10-07T19:30:00+08:00\",\"type\":\"highlights\","
                + "\"content\":\"<a href=\\\"https://www.polyu.edu.hk/cpeo/promotion-of-culture-on-campus/event/2026/10-october/20261007_polyu-cinema?sc_lang=en\\\">detail</a>\"}]}";
        when(fetchClient.get(anyString())).thenReturn(
                json.getBytes(StandardCharsets.UTF_8),
                "{\"events\":[]}".getBytes(StandardCharsets.UTF_8));

        List<RawNewsItem> items = fetcher.fetch(cpeo);

        List<String> months = expectedMonths(0);
        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(fetchClient, times(2)).get(urls.capture());
        assertEquals(cpeoEndpoint.replace("YYYY/MM", months.get(0)), urls.getAllValues().get(0));
        assertEquals(1, items.size());
        assertEquals("cpeo-events", items.get(0).sourceKey());
        assertEquals("PolyU Cinema", items.get(0).title());
        assertEquals("unknown", items.get(0).publishTimePrecision());
    }
}
