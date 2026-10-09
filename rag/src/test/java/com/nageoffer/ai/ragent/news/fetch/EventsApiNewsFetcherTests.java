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
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JSON_API（events）抓取窗口测试（#323 扩窗 + 历史回灌口径）：
 * <ul>
 * <li><b>未来 8 周滚动窗口</b>（#323，默认 events-window-weeks=8）：按月取数拼窗
 *     ——月份集合=覆盖「今天起 8 周」所需的全部月份（月中起跑跨 3 个月、月初起跑
 *     2 个月、跨年边界自然拼接），跨月条目（同活动在每个月份响应重复出现）按
 *     规范化 URL 去重；</li>
 * <li><b>fail-closed 口径不随扩窗放大</b>：仅当前月+下月维持零条目结构守卫（与
 *     扩窗前行为一致——源健康连败隔离不因远端未来月零排期误触发），历史回溯月
 *     与 +2 月及以后的远端月零活动宽和收空；</li>
 * <li>历史回灌：events-past-months&gt;0 时向前多取 N 个月（宽和）。</li>
 * </ul>
 */
class EventsApiNewsFetcherTests {

    private static final ZoneId HKT = ZoneId.of("Asia/Hong_Kong");
    private static final String ENDPOINT =
            "https://www.polyu.edu.hk/en/api/sitecore/calendar/get?id=F45B&date=YYYY/MM";

    private NewsHttpFetchClient fetchClient;
    private NewsFetchProperties properties;
    private NewsSourceDO source;

    @BeforeEach
    void setUp() {
        fetchClient = mock(NewsHttpFetchClient.class);
        properties = new NewsFetchProperties();
        source = NewsSourceDO.builder()
                .id(1L).sourceKey("events").platform("official")
                .fetchEndpoint(ENDPOINT)
                .fetchStrategy("JSON_API").enabled(true).build();
    }

    private EventsApiNewsFetcher fetcherAt(String hktDate) {
        ZonedDateTime fixed = LocalDate.parse(hktDate).atTime(12, 0).atZone(HKT);
        Supplier<ZonedDateTime> clock = () -> fixed;
        return new EventsApiNewsFetcher(fetchClient, properties, clock);
    }

    private static byte[] eventsJson(String date, String slug) {
        String json = "{\"events\":[{\"title\":\"PolyU Event " + slug + "\","
                + "\"start-date\":\"" + date + "\",\"end-date\":\"" + date + "\",\"type\":\"seminar\","
                + "\"content\":\"<a href=\\\"https://www.polyu.edu.hk/events/" + slug + "/\\\">detail</a>\"}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] eventsJsonRange(String slug, String startDate, String endDate) {
        String json = "{\"events\":[{\"title\":\"PolyU Event " + slug + "\","
                + "\"start-date\":\"" + startDate + "\",\"end-date\":\"" + endDate + "\",\"type\":\"ceremony\","
                + "\"content\":\"<a href=\\\"https://www.polyu.edu.hk/events/" + slug + "/\\\">detail</a>\"}]}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static final byte[] EMPTY_EVENTS = "{\"events\":[]}".getBytes(StandardCharsets.UTF_8);

    private static String monthToken(LocalDate date) {
        return date.withDayOfMonth(1).toString().substring(0, 7).replace('-', '/');
    }

    private List<String> capturedMonths() {
        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(fetchClient, atLeast(0)).get(urls.capture());
        return urls.getAllValues().stream()
                .map(url -> url.substring(url.indexOf("date=") + 5))
                .toList();
    }

    // ================== #323：未来 8 周滚动窗口（按月取数拼窗） ==================

    @Test
    void rollingWindowMidMonthCoversThreeMonths() {
        // 今天=2026-10-09，+8 周=2026-12-04 → 月份集合 {10,11,12} 月（月中起跑跨 3 个月）
        when(fetchClient.get(anyString())).thenReturn(
                eventsJson("2026-10-10", "ev-oct"), eventsJson("2026-11-12", "ev-nov"),
                eventsJson("2026-12-03", "ev-dec"));

        List<RawNewsItem> items = fetcherAt("2026-10-09").fetch(source);

        assertEquals(List.of("2026/10", "2026/11", "2026/12"), capturedMonths());
        assertEquals(3, items.size());
    }

    @Test
    void rollingWindowEarlyInMonthFitsTwoMonths() {
        // 今天=2026-05-03，+8 周=2026-06-28 → 月份集合 {5,6} 月（窗口不出 6 月）
        when(fetchClient.get(anyString())).thenReturn(
                eventsJson("2026-05-10", "ev-may"), eventsJson("2026-06-20", "ev-jun"));

        List<RawNewsItem> items = fetcherAt("2026-05-03").fetch(source);

        assertEquals(List.of("2026/05", "2026/06"), capturedMonths());
        assertEquals(2, items.size());
    }

    @Test
    void rollingWindowSpansYearBoundary() {
        // 今天=2026-12-20，+8 周=2027-02-14 → 月份集合 {2026/12, 2027/01, 2027/02}
        when(fetchClient.get(anyString())).thenReturn(
                eventsJson("2026-12-21", "ev-dec"), eventsJson("2027-01-10", "ev-jan"),
                eventsJson("2027-02-13", "ev-feb"));

        List<RawNewsItem> items = fetcherAt("2026-12-20").fetch(source);

        assertEquals(List.of("2026/12", "2027/01", "2027/02"), capturedMonths());
        assertEquals(3, items.size());
    }

    @Test
    void windowWeeksConfigurable() {
        properties.setEventsWindowWeeks(4);
        // 今天=2026-10-09，+4 周=2026-11-06 → 月份集合 {10,11} 月
        when(fetchClient.get(anyString())).thenReturn(
                eventsJson("2026-10-10", "ev-oct"), eventsJson("2026-11-05", "ev-nov"));

        fetcherAt("2026-10-09").fetch(source);

        assertEquals(List.of("2026/10", "2026/11"), capturedMonths());
    }

    @Test
    void crossMonthDuplicateEntriesAreDedupedByUrl() {
        // 跨月活动（32nd Congregation 型）：10 月与 11 月响应各出现一次同 URL 条目 → 只留一条
        when(fetchClient.get(anyString())).thenReturn(
                eventsJsonRange("ev-congregation", "2026-10-31", "2026-11-21"),
                eventsJsonRange("ev-congregation", "2026-10-31", "2026-11-21"),
                eventsJson("2026-12-03", "ev-dec"));

        List<RawNewsItem> items = fetcherAt("2026-10-09").fetch(source);

        assertEquals(List.of("2026/10", "2026/11", "2026/12"), capturedMonths());
        assertEquals(2, items.size(), "跨月重复条目按 URL 去重（10/11 月同 URL 只留一条）");
        assertTrue(items.stream().anyMatch(item -> item.url().endsWith("/ev-congregation")));
    }

    @Test
    void farFutureEmptyMonthToleratedWhileCurrentAndNextStayGuarded() {
        // +2 月（远端未来月）零排期属正常：宽和收空；当前/下月零条目仍 fail-closed（下方用例）
        when(fetchClient.get(anyString())).thenReturn(
                eventsJson("2026-10-10", "ev-oct"), eventsJson("2026-11-12", "ev-nov"),
                EMPTY_EVENTS);

        List<RawNewsItem> items = fetcherAt("2026-10-09").fetch(source);

        assertEquals(2, items.size());
    }

    @Test
    void farFutureEmptyMonthStillFailsClosedIsNotRequired() {
        // 仅远端未来月有数据、当前/下月空：当前/下月守卫照常 fail-closed（口径不随扩窗放大）
        when(fetchClient.get(anyString())).thenReturn(
                EMPTY_EVENTS, EMPTY_EVENTS, eventsJson("2026-12-03", "ev-dec"));

        assertThrows(NewsFetchException.class, () -> fetcherAt("2026-10-09").fetch(source));
    }

    // ================== #323：活动起止随条目透出（RawNewsItem.activityEnd） ==================

    @Test
    void itemsCarryActivityStartAndEnd() {
        when(fetchClient.get(anyString())).thenReturn(
                eventsJsonRange("ev-span", "2026-10-31", "2026-11-21"),
                eventsJsonRange("ev-span", "2026-10-31", "2026-11-21"),
                eventsJson("2026-12-03", "ev-dec"));

        List<RawNewsItem> items = fetcherAt("2026-10-09").fetch(source);

        RawNewsItem span = items.stream()
                .filter(item -> item.url().endsWith("/ev-span")).findFirst().orElseThrow();
        // start/end 均为裸日期代表值：start=HKT 零点、end=HKT 23:59:59（含端语义）
        assertEquals(LocalDate.parse("2026-10-31").atStartOfDay(HKT).toInstant(),
                span.publishTime().toInstant());
        assertEquals(LocalDate.parse("2026-11-21").atTime(23, 59, 59).atZone(HKT).toInstant(),
                span.activityEnd().toInstant());
        // 单日活动 end 同日；无结束证据的条目 activityEnd=null（解析面已证，此处透传口径）
        RawNewsItem singleDay = items.stream()
                .filter(item -> item.url().endsWith("/ev-dec")).findFirst().orElseThrow();
        assertNotNull(singleDay.activityEnd());
    }

    // ================== 历史回灌（events-past-months，宽和口径维持） ==================

    @Test
    void pastMonthsWideningFetchesHistoricalMonths() {
        properties.setEventsPastMonths(2);
        // 今天=2026-10-09：-2 月=2026/08（Aug/Sep/Oct/Nov/Dec 五请求）
        when(fetchClient.get(anyString()))
                .thenReturn(eventsJson("2026-08-20", "ev-aug"), eventsJson("2026-09-15", "ev-sep"),
                        eventsJson("2026-10-05", "ev-oct"), eventsJson("2026-11-12", "ev-nov"),
                        eventsJson("2026-12-03", "ev-dec"));

        List<RawNewsItem> items = fetcherAt("2026-10-09").fetch(source);

        assertEquals(List.of("2026/08", "2026/09", "2026/10", "2026/11", "2026/12"),
                capturedMonths());
        assertEquals(5, items.size());
    }

    @Test
    void emptyHistoricalMonthIsToleratedWhileCurrentMonthStaysFailClosed() {
        properties.setEventsPastMonths(1);
        // 历史月（2026/09）events 空数组：宽和收空；当前月有数据
        when(fetchClient.get(anyString()))
                .thenReturn(EMPTY_EVENTS,
                        eventsJson("2026-10-10", "ev-oct"), eventsJson("2026-11-12", "ev-nov"),
                        eventsJson("2026-12-03", "ev-dec"));

        List<RawNewsItem> items = fetcherAt("2026-10-09").fetch(source);

        assertEquals(3, items.size());
    }

    // ================== fail-closed / allow-empty（#186 口径不随扩窗回退） ==================

    @Test
    void emptyCurrentMonthStillFailsClosed() {
        when(fetchClient.get(anyString())).thenReturn(EMPTY_EVENTS);

        assertThrows(NewsFetchException.class, () -> fetcherAt("2026-10-09").fetch(source));
    }

    @Test
    void allowEmptySourceToleratesEmptyCurrentAndNextMonth() {
        // #186 成功分类学：allow-empty 源的当前/下月零条目=有效空（VALID_EMPTY，源健康），
        // 不再 fail-closed 计结构失配；窗口月全空 → 零产出
        properties.getAllowEmptySources().add("events");
        when(fetchClient.get(anyString())).thenReturn(EMPTY_EVENTS);

        List<RawNewsItem> items = fetcherAt("2026-10-09").fetch(source);

        assertTrue(items.isEmpty(), "allow-empty 源零条目=空产出（非抛断）");
        verify(fetchClient, times(3)).get(anyString());
    }

    // ================== 月份 token（现行口径回归） ==================

    @Test
    void monthTokenIsYearSlashMonth() {
        assertEquals("2026/10", monthToken(LocalDate.of(2026, 10, 9)));
        assertEquals("2027/01", monthToken(LocalDate.of(2026, 12, 31).plusMonths(1)));
    }

    @Test
    void missingPlaceholderStillFailsClosedAsStructureMismatch() {
        source.setFetchEndpoint("https://www.polyu.edu.hk/en/api/sitecore/calendar/get?id=F45B");
        assertThrows(NewsFetchStructureException.class, () -> fetcherAt("2026-10-09").fetch(source));
    }

    @Test
    void secondJsonApiSourceFetchesOwnCalendarEndpoint() {
        // #324：CPEO 文化活动日历与 events 同为 JSON_API——strategy 分发一对多，
        // 抓取器按源行 endpoint 取各自日历 id（BA1FFC…），条目归属各自 sourceKey。
        // 实查空月常态（2027/01、02 均 0 条）：cpeo-events 配 allow-empty（#186 成功
        // 分类学）——静月=VALID_EMPTY 非 fail-closed，否则空月连败会误触自动隔离。
        // #323 扩窗后口径：2026-10-09 起跑 8 周窗=10/11/12 三个月取数（跨月 URL 去重后 1 条）。
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
                EMPTY_EVENTS,
                EMPTY_EVENTS);

        List<RawNewsItem> items = fetcherAt("2026-10-09").fetch(cpeo);

        ArgumentCaptor<String> urls = ArgumentCaptor.forClass(String.class);
        verify(fetchClient, times(3)).get(urls.capture());
        assertEquals(cpeoEndpoint.replace("YYYY/MM", "2026/10"), urls.getAllValues().get(0));
        assertEquals(cpeoEndpoint.replace("YYYY/MM", "2026/11"), urls.getAllValues().get(1));
        assertEquals(cpeoEndpoint.replace("YYYY/MM", "2026/12"), urls.getAllValues().get(2));
        assertEquals(1, items.size());
        assertEquals("cpeo-events", items.get(0).sourceKey());
        assertEquals("PolyU Cinema", items.get(0).title());
        assertEquals("unknown", items.get(0).publishTimePrecision());
    }
}
