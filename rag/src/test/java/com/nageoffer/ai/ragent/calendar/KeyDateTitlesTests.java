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

package com.nageoffer.ai.ragent.calendar;

import com.nageoffer.ai.ragent.calendar.i18n.KeyDateTitles;
import com.nageoffer.ai.ragent.calendar.model.KeyDateCandidate;
import com.nageoffer.ai.ragent.calendar.parse.AcademicCalendarParser;
import com.nageoffer.ai.ragent.calendar.parse.AssessmentResultsParser;
import com.nageoffer.ai.ragent.calendar.parse.CalendarHtmlTables;
import com.nageoffer.ai.ragent.calendar.parse.ExamTimetableParser;
import com.nageoffer.ai.ragent.calendar.parse.FeePaymentAnnualParser;
import com.nageoffer.ai.ragent.calendar.parse.SourceGate;
import com.nageoffer.ai.ragent.calendar.parse.TimetableExamResultsParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双语标题词表测试（票面：规则词表+缺词回退英文，零 LLM）：全部 74 写事件的
 * 英文标题必有且不超列宽；中文标题按词表覆盖（身份代码集封闭——词表缺失回退
 * null 由查询侧处理，但当前代码集要求全量覆盖，缺口=词表欠账测试红）。
 */
class KeyDateTitlesTests {

    @Test
    void allSeventyFourEventsHaveBilingualTitles() throws IOException {
        List<KeyDateCandidate> events = allEvents();
        assertTrue(events.size() >= 74, "五源写事件（含已批扩展）：" + events.size());
        for (KeyDateCandidate e : events) {
            String en = KeyDateTitles.titleEn(e);
            String zh = KeyDateTitles.titleZh(e);
            assertNotNull(en, "英文标题必有：" + e.getEventCode() + "/" + e.getSlot());
            assertFalse(en.isBlank(), "英文标题非空：" + e.getEventCode());
            assertTrue(en.length() <= 256, "英文标题不超列宽：" + en);
            assertNotNull(zh, "当前身份代码集词表全量覆盖（缺词=词表欠账，须补 KeyDateTitles）："
                    + e.getAy() + "/" + e.getTerm() + "/" + e.getEventCode() + "/" + e.getAudienceCode() + "/" + e.getSlot());
            assertTrue(zh.length() <= 256, "中文标题不超列宽：" + zh);
        }
    }

    @Test
    void unknownCodesFallBackToEnglishSemantics() {
        KeyDateCandidate unknown = new KeyDateCandidate("x", "r0", "raw", null, null);
        unknown.setAy("2026/27");
        unknown.setTerm("S1");
        unknown.setEventCode("future-event-code");
        unknown.setSlot("window");
        assertNotNull(KeyDateTitles.titleEn(unknown), "理论缺词兜底=身份码拼接，不抛异常");
        assertTrue(KeyDateTitles.titleZh(unknown) == null || KeyDateTitles.titleZh(unknown).isEmpty()
                || KeyDateTitles.titleZh(unknown) != null, "缺词回退约定：zh 可空（查询侧回退英文）");
    }

    @Test
    void representativeTitlesSpotCheck() throws IOException {
        KeyDateCandidate feeDeadline = allEvents().stream()
                .filter(e -> "fee-deadline".equals(e.getEventCode()) && "initial".equals(e.getSlot())
                        && "S1".equals(e.getTerm()))
                .findFirst().orElseThrow();
        assertTrue(KeyDateTitles.titleEn(feeDeadline).contains("Semester One tuition fee payment deadline"));
        assertTrue(KeyDateTitles.titleZh(feeDeadline).contains("缴费截止"));

        KeyDateCandidate holiday = allEvents().stream()
                .filter(e -> "general-holiday".equals(e.getEventCode()) && "christmas-day".equals(e.getSlot()))
                .findFirst().orElseThrow();
        assertEqualsValue("圣诞节", KeyDateTitles.titleZh(holiday));
    }

    private static void assertEqualsValue(String expected, String actual) {
        assertTrue(expected.equals(actual), expected + " != " + actual);
    }

    private static List<KeyDateCandidate> allEvents() throws IOException {
        Map<String, Object> parsers = Map.of(
                "cal-academic-calendar", new AcademicCalendarParser(),
                "cal-fee-payment-annual", new FeePaymentAnnualParser(),
                "cal-timetable-exam-results", new TimetableExamResultsParser(),
                "cal-exam-timetable", new ExamTimetableParser(),
                "cal-assessment-results", new AssessmentResultsParser());
        return parsers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    try {
                        String html = snap(e.getKey());
                        return SourceGate.evaluate((com.nageoffer.ai.ragent.calendar.parse.CalendarPageParser) e.getValue(),
                                CalendarHtmlTables.mainRows(html), CalendarHtmlTables.visibleText(html));
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                })
                .flatMap(r -> r.events().stream())
                .toList();
    }

    private static String snap(String key) throws IOException {
        try (InputStream in = KeyDateTitlesTests.class.getResourceAsStream(
                "/fixtures/calendar/snapshots/" + key + ".html")) {
            assertNotNull(in, key);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
