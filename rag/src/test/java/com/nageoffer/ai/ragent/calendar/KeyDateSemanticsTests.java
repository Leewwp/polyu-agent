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

import com.nageoffer.ai.ragent.calendar.dao.entity.KeyDateDO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校历日期语义三件套最小单测（#343）——三函数各正反例，倒计时门覆盖
 * exact-day/exact-range 计数与 onwards/fuzzy 非门形态恒 null。board
 * （KeyDateQueryServiceTests）与日报装配（NewsDailyDigestServiceTests）
 * 的行为面回归由既有测试守门，此处只直测纯函数本身。
 */
class KeyDateSemanticsTests {

    /**
     * 固定 as-of 锚点（纯函数无时钟依赖）
     */
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 9);

    private static KeyDateDO row(String precision, String dateStartIso, String dateEndIso) {
        return KeyDateDO.builder()
                .precision(precision)
                .dateStart(dateStartIso == null ? null : LocalDate.parse(dateStartIso))
                .dateEnd(dateEndIso == null ? null : LocalDate.parse(dateEndIso))
                .build();
    }

    @Test
    void effectiveEndPrefersEndAndFallsBackToStart() {
        // 正例：exact-range 有结束列取 date_end
        assertEquals(LocalDate.of(2026, 10, 20),
                KeyDateSemantics.effectiveEnd(row("exact-range", "2026-10-09", "2026-10-20")));
        // 反例：exact-day/onwards 无结束列回落 date_start
        assertEquals(LocalDate.of(2026, 10, 12),
                KeyDateSemantics.effectiveEnd(row("exact-day", "2026-10-12", null)));
    }

    @Test
    void isOngoingRequiresStartedAndNotEnded() {
        // 正例：已开始（start < asOf）未结束（有效结束日 >= asOf）
        assertTrue(KeyDateSemantics.isOngoing(row("exact-range", "2026-10-01", "2026-10-15"), AS_OF));
        // 反例：当日开始不算进行中（归「即将来临」）
        assertFalse(KeyDateSemantics.isOngoing(row("exact-day", "2026-10-09", null), AS_OF));
        // 反例：已结束（有效结束日 < asOf）
        assertFalse(KeyDateSemantics.isOngoing(row("exact-range", "2026-09-20", "2026-09-25"), AS_OF));
    }

    @Test
    void daysUntilCountsOnlyExactPrecisions() {
        // 正例：exact-day 未来起始，asOf→start 天数
        assertEquals(3, KeyDateSemantics.daysUntil(row("exact-day", "2026-10-12", null), AS_OF));
        // 正例：exact-range 已开始区间为负值（留给 ongoing 徽章，不伪造「已过 N 天」以外的语义）
        assertEquals(-8, KeyDateSemantics.daysUntil(row("exact-range", "2026-10-01", "2026-10-15"), AS_OF));
        // 反例（非门形态）：onwards 开放起点不伪造截止语义恒 null
        assertNull(KeyDateSemantics.daysUntil(row("onwards", "2026-10-12", null), AS_OF));
        // 反例（非门形态）：fuzzy 模糊窗 date_* 全 NULL 恒 null
        assertNull(KeyDateSemantics.daysUntil(row("fuzzy", null, null), AS_OF));
    }
}
