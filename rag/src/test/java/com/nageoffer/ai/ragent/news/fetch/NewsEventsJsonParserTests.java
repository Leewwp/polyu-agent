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

    static final class DateFrom {

        static java.util.Date instant(String iso) {
            return java.util.Date.from(Instant.parse(iso));
        }
    }
}
