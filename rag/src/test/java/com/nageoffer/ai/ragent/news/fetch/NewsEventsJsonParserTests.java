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

    // ---------- #324：CPEO 文化活动日历（同款 Sitecore API，id=BA1FFC…；PolyU Cinema 系列） ----------

    private byte[] cpeoFixture() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/news/cpeo-events.json")) {
            assertNotNull(in, "fixture 缺失：/fixtures/news/cpeo-events.json");
            return in.readAllBytes();
        }
    }

    @Test
    void parsesCpeoCalendarSameShapeAsEventsSource() throws Exception {
        // #324 查定：CPEO 列表页背后即官网活动日历同款 calendar/get API（仅日历 id 不同），
        // 解析器零改动复用——4 条冻结实采（2026-10）全产出，含 PolyU Cinema 放映条目
        List<NewsEventsJsonParser.EventEntry> events = NewsEventsJsonParser.parse(cpeoFixture());
        assertEquals(4, events.size());
    }

    @Test
    void keepsChineseTitleVerbatimAndCinemaInstant() throws Exception {
        List<NewsEventsJsonParser.EventEntry> events = NewsEventsJsonParser.parse(cpeoFixture());
        // 中文标题条目原样保留（langRaw 归 LLM 富化判定，解析层不改写）
        NewsEventsJsonParser.EventEntry talk = events.stream()
                .filter(e -> e.title().startsWith("「年度中國歷史人物選舉2026」")).findFirst().orElseThrow();
        assertEquals("collaborations", talk.typeHint());
        assertEquals(DateFrom.instant("2026-10-06T06:30:00Z"), talk.start());
        // PolyU Cinema 放映场：eventStartDate=2026-10-07T19:30:00+08:00 → UTC 11:30 真实瞬时
        NewsEventsJsonParser.EventEntry cinema = events.stream()
                .filter(e -> e.title().startsWith("PolyU Cinema:")).findFirst().orElseThrow();
        assertEquals(DateFrom.instant("2026-10-07T11:30:00Z"), cinema.start());
        assertTrue(cinema.link().endsWith("/20261007_polyu-cinema?sc_lang=en"));
    }

    @Test
    void emptyTypeCpeoEntryIsKeptWithBlankTypeHint() throws Exception {
        // CPEO 实采存在 type 为空串的条目（"Colorful Breeze"）：不因类别缺失丢条目
        List<NewsEventsJsonParser.EventEntry> events = NewsEventsJsonParser.parse(cpeoFixture());
        NewsEventsJsonParser.EventEntry breeze = events.stream()
                .filter(e -> e.title().startsWith("\"Colorful Breeze\"")).findFirst().orElseThrow();
        assertEquals(DateFrom.instant("2026-10-13T11:30:00Z"), breeze.start());
        assertTrue(breeze.typeHint() == null || breeze.typeHint().isBlank());
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

    static final class DateFrom {

        static java.util.Date instant(String iso) {
            return java.util.Date.from(Instant.parse(iso));
        }
    }
}
