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

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentDateFactTest {

    @Test
    void formatsIsoDateWithFullChineseWeekday() {
        assertEquals("当前日期（香港时间）：2026-10-10（星期六）",
                CurrentDateFact.hktDateLine(LocalDate.of(2026, 10, 10)));
        assertEquals("当前日期（香港时间）：2026-12-31（星期四）",
                CurrentDateFact.hktDateLine(LocalDate.of(2026, 12, 31)));
    }

    @Test
    void hktZoneIsExplicitHongKong() {
        assertEquals(ZoneId.of("Asia/Hong_Kong"), CurrentDateFact.HKT);
    }

    @Test
    void productionEntryYieldsTodayLine() {
        String line = CurrentDateFact.hktDateLine();
        assertTrue(line.startsWith("当前日期（香港时间）："), "行前缀稳定：槽模板时效规则按此前缀引用");
        assertTrue(line.matches("当前日期（香港时间）：\\d{4}-\\d{2}-\\d{2}（星期[一二三四五六日]）"));
    }
}
