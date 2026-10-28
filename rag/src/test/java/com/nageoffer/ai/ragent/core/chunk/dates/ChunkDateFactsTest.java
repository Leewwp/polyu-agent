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

package com.nageoffer.ai.ragent.core.chunk.dates;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #411 抽取规则纯函数单测：边界样本=#376（research/date-structure-376-20261011）
 * 冻结夹具四形态+票面点名形态（发布与截止同日/跨月/仅年份/学年红线），全部用语料实证过的句式
 */
class ChunkDateFactsTest {

    @Test
    @DisplayName("发布与截止同日：两族各自独立抽取，互不替代")
    void publishAndDeadlineSameDay() {
        ChunkDateFacts.Dates dates = ChunkDateFacts.extract(
                "Annual Schedule 2026/27\nUpdated on 1 September 2026\n"
                        + "[Notification: 14 August 2026; Payment Deadline: 1 September 2026] "
                        + "Payment of Tuition Fee for Current Students (Semester One)");
        assertEquals(LocalDate.of(2026, 9, 1), dates.sourceDate());
        assertEquals(LocalDate.of(2026, 9, 1), dates.activityEndDate());
    }

    @Test
    @DisplayName("跨月：发布 7 月、截止 9 月，各自按自己的标记落值")
    void publishAndDeadlineCrossMonth() {
        ChunkDateFacts.Dates dates = ChunkDateFacts.extract(
                "Annual Schedule for Fee Payment for Academic Year 2026/27\n"
                        + "Updated on 21 July 2026\n"
                        + "[Notification: 14 August 2026; Payment Deadline: 1 September 2026] Payment of Tuition Fee");
        assertEquals(LocalDate.of(2026, 7, 21), dates.sourceDate());
        assertEquals(LocalDate.of(2026, 9, 1), dates.activityEndDate());
    }

    @Test
    @DisplayName("仅年份不成形：发布/截止都不落值（不虚构月日）")
    void yearOnlyYieldsNothing() {
        ChunkDateFacts.Dates dates = ChunkDateFacts.extract(
                "The arrangement applies to 2026. Deadline for submission will be announced in 2026.");
        assertNull(dates.sourceDate());
        assertNull(dates.activityEndDate());
    }

    @Test
    @DisplayName("#376 红线：学年形态 26/27、2026/27 拒判为日期；署期照常抽取")
    void academicYearFormsRejected() {
        // en-019nav 学年日历页形态：26/27 学年、无任何显式发布/截止标记 → 双 null（fixtures fx-unknown）
        ChunkDateFacts.Dates calendar = ChunkDateFacts.extract(
                "Academic calendar for the 26/27 academic year. Terms and teaching weeks are listed below.");
        assertTrue(calendar.isEmpty());

        // 学年紧邻真日期：2026/27 不得被当成日期，署期 21 July 2026 照常抽取
        ChunkDateFacts.Dates withUpdate = ChunkDateFacts.extract(
                "Annual Schedule for Academic Year 2026/27 Updated on 21 July 2026");
        assertEquals(LocalDate.of(2026, 7, 21), withUpdate.sourceDate());
        assertNull(withUpdate.activityEndDate());
    }

    @Test
    @DisplayName("en-022 语料形态：Registration was open until D → 截止族")
    void registrationOpenUntil() {
        ChunkDateFacts.Dates dates = ChunkDateFacts.extract(
                "Workshop 5 (7 October 2026). Registration was open until 28 August 2026.");
        assertEquals(LocalDate.of(2026, 8, 28), dates.activityEndDate());
        assertNull(dates.sourceDate());
    }

    @Test
    @DisplayName("中文形态：发布于/报名截止：前缀式与日期前置后缀式")
    void chineseMarkers() {
        assertEquals(LocalDate.of(2026, 10, 1),
                ChunkDateFacts.extract("图书馆开放时间调整\n发布于 2026年10月1日").sourceDate());
        assertEquals(LocalDate.of(2026, 10, 15),
                ChunkDateFacts.extract("工作坊报名须知：报名截止：2026年10月15日").activityEndDate());
        assertEquals(LocalDate.of(2026, 10, 15),
                ChunkDateFacts.extract("感兴趣的同学请于 2026年10月15日截止 前提交").activityEndDate());
    }

    @Test
    @DisplayName("美式月日年与月名缩写、ISO 署期均可解析")
    void americanAbbrevAndIsoForms() {
        assertEquals(LocalDate.of(2026, 7, 21),
                ChunkDateFacts.extract("Last updated: July 21, 2026").sourceDate());
        assertEquals(LocalDate.of(2026, 10, 2),
                ChunkDateFacts.extract("Last Reviewed on 2 Oct 2026").sourceDate());
        assertEquals(LocalDate.of(2026, 7, 21),
                ChunkDateFacts.extract("Updated: 2026-07-21").sourceDate());
    }

    @Test
    @DisplayName("显式报名句式：Register by / apply before / 截止标记")
    void explicitByBeforeDeadlines() {
        assertEquals(LocalDate.of(2026, 10, 15),
                ChunkDateFacts.extract("Register by 15 October 2026 to secure a seat.").activityEndDate());
        assertEquals(LocalDate.of(2026, 9, 30),
                ChunkDateFacts.extract("Students should apply before 30 September 2026.").activityEndDate());
    }

    @Test
    @DisplayName("无效历法（2 月 30 日）跳过，窗口内无有效日期则不产出")
    void invalidCalendarDateSkipped() {
        ChunkDateFacts.Dates dates = ChunkDateFacts.extract("Deadline: 30 February 2026");
        assertNull(dates.activityEndDate());
    }

    @Test
    @DisplayName("相对日期不换算：Apply by next Friday 不产出（不推算）")
    void relativeDateNotComputed() {
        ChunkDateFacts.Dates dates = ChunkDateFacts.extract("Apply by next Friday at noon.");
        assertNull(dates.activityEndDate());
    }

    @Test
    @DisplayName("活动区间句式非截止语义（#376：报名期句式依赖不可靠，保守不落截止）")
    void periodRangeNotDeadline() {
        ChunkDateFacts.Dates dates = ChunkDateFacts.extract(
                "Subject Registration Period: 28 to 30 August 2026");
        assertNull(dates.activityEndDate());
    }

    @Test
    @DisplayName("多个截止命中取正文位置最早的一处（确定性）")
    void earliestPositionWins() {
        ChunkDateFacts.Dates dates = ChunkDateFacts.extract(
                "[Payment Deadline: 1 September 2026] Payment of Tuition Fee (Semester One)\n"
                        + "[Payment Deadline: 24 June 2027] Payment of Tuition Fee (Summer Term)");
        assertEquals(LocalDate.of(2026, 9, 1), dates.activityEndDate());
    }

    @Test
    @DisplayName("无标记语料：双 null（「日期未知」语义）；空白输入同样安全")
    void noMarkersYieldEmpty() {
        assertTrue(ChunkDateFacts.extract(
                "The Library offers workshops and orientation throughout the year. Visit the following pages.").isEmpty());
        assertTrue(ChunkDateFacts.extract(null).isEmpty());
        assertTrue(ChunkDateFacts.extract("   ").isEmpty());
    }

    @Test
    @DisplayName("toIsoDay：#275 口径 yyyy-MM-dd 序列化，null 直传")
    void isoDaySerialization() {
        assertEquals("2026-07-21", ChunkDateFacts.toIsoDay(LocalDate.of(2026, 7, 21)));
        assertNull(ChunkDateFacts.toIsoDay(null));
    }
}
