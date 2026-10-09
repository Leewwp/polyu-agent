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

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JSON_API（events）型解析测试（上线必选项之一）
 *
 * <p>fixture 裁自 2026-09-10 实测响应（Sitecore calendar/get，3 条当月活动）：
 * title / eventStartDate（ISO +08:00）/ type / content 内嵌详情页绝对链接。
 */
class NewsEventsJsonParserTests {

    private byte[] fixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/news/events.json")) {
            assertNotNull(in, "fixture 缺失：/fixtures/news/events.json");
            return in.readAllBytes();
        }
    }

    @Test
    void parsesEventsWithDetailLinkAndIsoStart() throws Exception {
        List<NewsEventsJsonParser.EventEntry> events = NewsEventsJsonParser.parse(fixture());
        assertEquals(3, events.size());

        NewsEventsJsonParser.EventEntry lecture = events.stream()
                .filter(e -> e.title().startsWith("Faculty of Engineering Distinguished Lecture"))
                .findFirst().orElseThrow();
        // eventStartDate=2026-09-14T10:30:00+08:00 → UTC 02:30
        assertEquals(DateFrom.instant("2026-09-14T02:30:00Z"), lecture.start());
        assertEquals("conference / lecture", lecture.typeHint());
        assertEquals("https://www.polyu.edu.hk/en/events/2026/9/15455_669", lecture.link());
        assertTrue(lecture.title().contains("Microwave Full Field Vibration"));
    }

    @Test
    void midnightEventKeepsHktDaySemantics() throws Exception {
        List<NewsEventsJsonParser.EventEntry> events = NewsEventsJsonParser.parse(fixture());
        NewsEventsJsonParser.EventEntry election = events.stream()
                .filter(e -> e.title().startsWith("Election of Student Members"))
                .findFirst().orElseThrow();
        // 2026-09-09T00:00:00+08:00 → UTC 前一日 16:00（HKT 切日语义，§13-3）
        assertEquals(DateFrom.instant("2026-09-08T16:00:00Z"), election.start());
    }

    @Test
    void malformedJsonFailsClosed() {
        assertThrows(NewsFetchException.class, () -> NewsEventsJsonParser.parse("{broken".getBytes()));
    }

    @Test
    void missingEventsArrayFailsClosed() {
        assertThrows(NewsFetchException.class, () -> NewsEventsJsonParser.parse("{\"foo\":1}".getBytes()));
    }

    // ---------- #186：允许空变体——缺 events 数组/坏 JSON 不因宽和豁免 ----------

    @Test
    void missingEventsArrayFailsEvenWhenNotFailClosed() {
        assertThrows(NewsFetchStructureException.class,
                () -> NewsEventsJsonParser.parse("{\"foo\":1}".getBytes(), false),
                "缺 events 数组=结构失配，allow-empty 源也不冒充有效空");
        assertThrows(NewsFetchStructureException.class,
                () -> NewsEventsJsonParser.parse("{broken".getBytes(), false),
                "坏 JSON 同理");
    }

    // ---------- #325：SAO 学生活动日历（同 API 不同 calendar id，fixture 冻结 2026-10-09 实采） ----------

    private byte[] saoFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/news/sao-events.json")) {
            assertNotNull(in, "fixture 缺失：/fixtures/news/sao-events.json");
            return in.readAllBytes();
        }
    }

    @Test
    void saoCalendarFixtureParsesStudentDevelopmentEntries() throws Exception {
        // SAO 学生发展组活动日历（calendar id=6840C445…，/sao/news-and-events/event-calendar/）
        // 与大学级 events 同款 Sitecore API 响应形状；eventTypeList 仅 Student Development
        List<NewsEventsJsonParser.EventEntry> events = NewsEventsJsonParser.parse(saoFixture());
        // CLF 2026 两段日期（9/3-4、9/7-8）= 两个条目，各自独立详情页（slug 含日期段）
        assertEquals(4, events.size());

        List<NewsEventsJsonParser.EventEntry> clfSegments = events.stream()
                .filter(e -> e.title().startsWith("Campus Life Festival 2026")).toList();
        assertEquals(2, clfSegments.size());
        assertEquals(DateFrom.instant("2026-09-03T04:00:00Z"), clfSegments.get(0).start());
        assertEquals(DateFrom.instant("2026-09-07T04:00:00Z"), clfSegments.get(1).start());
        assertTrue(!clfSegments.get(0).link().equals(clfSegments.get(1).link()),
                "两段日期各自详情页，URL 去重后仍独立成条");
        assertEquals("student development", clfSegments.get(0).typeHint());
        assertTrue(clfSegments.get(0).link().startsWith("https://www.polyu.edu.hk/sao/news-and-events/event-calendar/"),
                "详情链接落在 SAO 日历域名下");

        NewsEventsJsonParser.EventEntry talentShow = events.stream()
                .filter(e -> e.title().startsWith("Annual Talent Show 2026"))
                .findFirst().orElseThrow();
        // 19:00 HKT 晚场 → UTC 11:00
        assertEquals(DateFrom.instant("2026-09-17T11:00:00Z"), talentShow.start());
    }

    @Test
    void saoEmptyMonthIsValidEmptyOnlyWhenNotFailClosed() {
        // 2026-11 实采响应（32 字节）：events 空数组=日历渐进排期的正常空态——
        // 源已列 allow-empty-sources（#186），宽和收空；strict 重载仍 fail-closed
        // （若未来移出 allow-empty，空月即结构失配隔离，本测试钉住该语义边界）
        byte[] emptyMonth = "{\"events\":[],\"eventTypeList\":[]}".getBytes();
        assertTrue(NewsEventsJsonParser.parse(emptyMonth, false).isEmpty());
        assertThrows(NewsFetchStructureException.class, () -> NewsEventsJsonParser.parse(emptyMonth));
    }

    static final class DateFrom {

        static java.util.Date instant(String iso) {
            return java.util.Date.from(Instant.parse(iso));
        }
    }
}
