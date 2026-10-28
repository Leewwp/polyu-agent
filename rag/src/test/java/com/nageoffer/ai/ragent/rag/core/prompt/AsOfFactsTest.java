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

package com.nageoffer.ai.ragent.rag.core.prompt;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * as-of 状态计算纯函数测试（#413 验收门 1）
 * <p>
 * 覆盖：HKT 日界边界（判定基准日只随香港时间日界翻转）、过期/当日/未来/仅日期未知全样本、
 * 组合矛盾数据的确定性优先级。全部样本用注入的固定基准日，判定可固定复现
 */
class AsOfFactsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    // ==================== 状态计算 ====================

    @Test
    void undatedEvidenceHasNoStatus() {
        // 双 null=日期未知：不判状态（缺失即缺失，不虚构）
        assertNull(AsOfFacts.status(TODAY, null, null));
        assertNull(AsOfFacts.factLine(TODAY, null, null));
    }

    @Test
    void expiredDeadlineIsExpired() {
        // 截止日早于基准日一天即过期（HKT 日界粒度，无时刻参与）
        assertEquals(AsOfFacts.Status.EXPIRED, AsOfFacts.status(TODAY, null, TODAY.minusDays(1)));
        assertEquals(AsOfFacts.Status.EXPIRED, AsOfFacts.status(TODAY, TODAY.minusYears(1), TODAY.minusDays(90)));
    }

    @Test
    void deadlineTodayIsOngoingNotExpired() {
        // 当日截止不算过期：可办理窗口的最后一天
        assertEquals(AsOfFacts.Status.ONGOING, AsOfFacts.status(TODAY, null, TODAY));
        assertEquals(AsOfFacts.Status.ONGOING, AsOfFacts.status(TODAY, TODAY.minusDays(10), TODAY));
    }

    @Test
    void futureDeadlineIsOngoing() {
        assertEquals(AsOfFacts.Status.ONGOING, AsOfFacts.status(TODAY, null, TODAY.plusDays(1)));
        assertEquals(AsOfFacts.Status.ONGOING, AsOfFacts.status(TODAY, null, TODAY.plusMonths(6)));
    }

    @Test
    void futureSourceOnlyIsNotStarted() {
        // 仅有未来资料日期：未开始（描述的未来安排尚未生效）
        assertEquals(AsOfFacts.Status.UPCOMING, AsOfFacts.status(TODAY, TODAY.plusDays(1), null));
        assertEquals(AsOfFacts.Status.UPCOMING, AsOfFacts.status(TODAY, TODAY.plusYears(1), null));
    }

    @Test
    void pastSourceOnlyIsOngoing() {
        // 仅有已过发布日期、无截止语义：现行资料（无截止不判过期——#376 红线）
        assertEquals(AsOfFacts.Status.ONGOING, AsOfFacts.status(TODAY, TODAY, null));
        assertEquals(AsOfFacts.Status.ONGOING, AsOfFacts.status(TODAY, TODAY.minusDays(1), null));
    }

    @Test
    void expiredWinsOverConflictingFutureSource() {
        // 矛盾数据优先级：截止已过+发布在未来 → 已过期（安全优先，防按失效期限行动）
        assertEquals(AsOfFacts.Status.EXPIRED, AsOfFacts.status(TODAY, TODAY.plusDays(30), TODAY.minusDays(1)));
    }

    @Test
    void deadlineTodayWinsOverConflictingFutureSource() {
        // 截止即今日+发布在未来 → 当日截止优先（可办理窗口最后一天的事实最可行动）
        assertEquals(AsOfFacts.Status.ONGOING,
                AsOfFacts.status(TODAY, TODAY.plusDays(30), TODAY));
    }

    @Test
    void futureSourceWinsOverFutureDeadline() {
        // 双未来（未来学年日程：发布在未来、截止更远）→ 未开始
        assertEquals(AsOfFacts.Status.UPCOMING,
                AsOfFacts.status(TODAY, TODAY.plusDays(30), TODAY.plusMonths(6)));
        // 事实行与状态同口径：状态行的措辞跟随 status()，不被「尚未截止」分支吞掉
        assertEquals("〔日期事实〕资料日期 " + TODAY.plusDays(30) + "：未开始，日期在未来（截至 " + TODAY + "，香港时间）",
                AsOfFacts.factLine(TODAY, TODAY.plusDays(30), TODAY.plusMonths(6)));
    }

    // ==================== HKT 日界边界 ====================

    @Test
    void hktMidnightRollsAsOfBasis() {
        // 香港时间零点=UTC 16:00：前一秒仍属上一日、后一秒翻日；判定基准日只随 HKT 日界翻转
        ZoneId hkt = CurrentDateFact.HKT;
        LocalDate beforeMidnight = LocalDate.now(Clock.fixed(
                Instant.parse("2026-10-28T15:59:59Z"), hkt));
        LocalDate afterMidnight = LocalDate.now(Clock.fixed(
                Instant.parse("2026-10-28T16:00:00Z"), hkt));
        assertEquals(LocalDate.of(2026, 10, 28), beforeMidnight, "UTC 15:59:59 = HKT 23:59:59，仍属 10-28");
        assertEquals(LocalDate.of(2026, 10, 29), afterMidnight, "UTC 16:00:00 = HKT 10-29 00:00，日界翻转");

        // 同一截止日 2026-10-29 在两侧的确定性判定：未截止 → 今日截止；再翻一日 → 已过期
        assertEquals(AsOfFacts.Status.ONGOING, AsOfFacts.status(beforeMidnight, null, LocalDate.of(2026, 10, 29)));
        assertEquals(AsOfFacts.Status.ONGOING, AsOfFacts.status(afterMidnight, null, LocalDate.of(2026, 10, 29)));
        assertEquals(AsOfFacts.Status.EXPIRED,
                AsOfFacts.status(afterMidnight.plusDays(1), null, LocalDate.of(2026, 10, 29)));
    }

    @Test
    void yearBoundaryAndLeapDayFlipDeterministically() {
        // 跨年与闰日边界：判定不依赖日历特例，纯日期比较
        assertEquals(AsOfFacts.Status.ONGOING, AsOfFacts.status(
                LocalDate.of(2026, 12, 31), null, LocalDate.of(2027, 1, 1)));
        assertEquals(AsOfFacts.Status.EXPIRED, AsOfFacts.status(
                LocalDate.of(2027, 1, 1), null, LocalDate.of(2026, 12, 31)));
        assertEquals(AsOfFacts.Status.ONGOING, AsOfFacts.status(
                LocalDate.of(2028, 2, 28), null, LocalDate.of(2028, 2, 29)));
        assertEquals(AsOfFacts.Status.EXPIRED, AsOfFacts.status(
                LocalDate.of(2028, 3, 1), null, LocalDate.of(2028, 2, 29)));
    }

    // ==================== 事实行文案（状态+截至声明）====================

    @Test
    void factLineCarriesStatusAndAsOfDeclaration() {
        assertEquals("〔日期事实〕截止日期 2026-10-28：已过期，截止日已过（截至 2026-10-29，香港时间）",
                AsOfFacts.factLine(TODAY, null, TODAY.minusDays(1)));
        assertEquals("〔日期事实〕截止日期 2026-10-29：进行中，今日为截止日（截至 2026-10-29，香港时间）",
                AsOfFacts.factLine(TODAY, null, TODAY));
        assertEquals("〔日期事实〕截止日期 2026-11-15：进行中，尚未截止（截至 2026-10-29，香港时间）",
                AsOfFacts.factLine(TODAY, null, TODAY.plusDays(17)));
        assertEquals("〔日期事实〕资料日期 2027-01-05：未开始，日期在未来（截至 2026-10-29，香港时间）",
                AsOfFacts.factLine(TODAY, TODAY.plusDays(68), null));
        assertEquals("〔日期事实〕资料日期 2026-09-02：进行中，现行资料（截至 2026-10-29，香港时间）",
                AsOfFacts.factLine(TODAY, LocalDate.of(2026, 9, 2), null));
        // 双日期在场：状态行以截止族为准（发布日期仍在标记属性里，两处不重复）
        assertEquals("〔日期事实〕截止日期 2026-09-01：已过期，截止日已过（截至 2026-10-29，香港时间）",
                AsOfFacts.factLine(TODAY, LocalDate.of(2026, 7, 21), LocalDate.of(2026, 9, 1)));
    }
}
