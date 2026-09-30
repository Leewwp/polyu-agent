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

package com.nageoffer.ai.ragent.calendar.ics;

import com.nageoffer.ai.ragent.calendar.ics.IcsCalendarWriter.EventSpec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC 5545 文本面硬门（#194 票面逐条）：折行 ≤75 八位组/续行空格/仅 CRLF、
 * TEXT 转义、exact-range DTEND=结束日+1（半开端点）、UID 前缀、VTIMEZONE、
 * VALARM 按规格渲染。全部用例末尾过 {@link Rfc5545Validator}（同一判据双保险）。
 */
class IcsCalendarWriterTests {

    private static final LocalDate SEP1 = LocalDate.of(2026, 9, 1);
    private static final LocalDate FEB6 = LocalDate.of(2027, 2, 6);
    private static final LocalDate FEB14 = LocalDate.of(2027, 2, 14);
    private static final LocalDateTime STAMP = LocalDateTime.of(2026, 9, 30, 8, 0);

    private static EventSpec spec(String uid, LocalDate start, LocalDate end,
                                  int sequence, boolean cancelled, boolean alarm) {
        return new EventSpec(uid, "第一学期学费缴费截止（首轮）", "First round deadline\n适用: Current Students",
                "https://www.polyu.edu.hk/ar/students-in-taught-programmes/annual-schedules/fee-payment/",
                start, end, sequence, cancelled, alarm, STAMP);
    }

    /** 64hex 桩（UID 形状断言用——真值生成见 KeyDateUid 合同测试） */
    private static String hex64(String seed) {
        StringBuilder sb = new StringBuilder(64);
        for (int i = 0; i < 64; i++) {
            sb.append((seed.charAt(i % seed.length()) + i) % 10);
        }
        return sb.toString();
    }

    // —— 折行硬门 ——

    @Test
    void foldingOctetLimitContinuationPrefixAndCrLfOnly() {
        // 长中文描述（每字 3 八位组）必然折行
        String longSummary = "第".repeat(120);
        String ics = IcsCalendarWriter.write(List.of(new EventSpec(hex64("a"), longSummary, null, null,
                SEP1, null, 1, false, false, STAMP)), "PolyU 关键日期 Key Dates", 7);

        for (String physical : ics.split("\r\n", -1)) {
            int octets = physical.getBytes(StandardCharsets.UTF_8).length;
            assertTrue(octets <= 75, "物理行 " + octets + " 八位组超 75：" + physical);
        }
        // 仅 CRLF：文本内不再有裸 LF/CR
        String stripped = ics.replace("\r\n", "");
        assertFalse(stripped.contains("\n"), "存在裸 LF");
        assertFalse(stripped.contains("\r"), "存在裸 CR");
        // 续行以空格起；展开后可还原长标题（多字节序列未被切断——UTF-8 重解码合法即证）
        String[] lines = ics.split("\r\n");
        StringBuilder unfolded = new StringBuilder();
        boolean sawContinuation = false;
        for (String line : lines) {
            if (line.startsWith(" ")) {
                sawContinuation = true;
                unfolded.append(line, 1, line.length());
            } else {
                unfolded.append("\n").append(line);
            }
        }
        assertTrue(sawContinuation, "长行未折行");
        assertTrue(unfolded.toString().contains("第".repeat(120)), "折行展开后原文失真");
        // 末行 CRLF 终界
        assertTrue(ics.endsWith("\r\n"), "末行缺 CRLF 终界");
    }

    @Test
    void foldNeverSplitsMultibyteCodePoint() {
        // 全 3 八位组字符：75 = 3*25，恰在字符边界；边界外+1 字节场景逐一验证
        String line = "SUMMARY:" + "汉".repeat(60); // 8 + 180 = 188 八位组 → 多段
        for (String segment : IcsCalendarWriter.fold(line)) {
            // 每段解码合法（被切断的 UTF-8 序列会抛 MalformedInput 或替换符）
            String decoded = new String(segment.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
            assertFalse(decoded.contains("\uFFFD"), "折行切断了多字节序列");
        }
    }

    // —— 转义硬门 ——

    @Test
    void textEscapeCoversBackslashSemicolonCommaNewline() {
        assertEquals("a\\\\b\\;c\\,d\\ne", IcsCalendarWriter.escapeText("a\\b;c,d\r\ne"),
                "反斜杠/分号/逗号/CRLF 按 RFC 5545 §3.3.11 转义（CRLF 归一为 \\n）");
        assertEquals("one\\ntwo", IcsCalendarWriter.escapeText("one\ntwo"));
        // feed 内实际生效：DESCRIPTION 的换行转义为字面 \n（反斜杠+n 两字符）
        String ics = IcsCalendarWriter.write(List.of(spec(hex64("b"), SEP1, null, 1, false, false)),
                "名", 7);
        assertTrue(ics.contains("First round deadline\\n适用: Current Students"),
                "DESCRIPTION 换行以 \\n 转义、冒号不在 TEXT 转义集（RFC 只要求 \\ ; , 换行）");
    }

    // —— 日期语义硬门 ——

    @Test
    void exactRangeDtEndIsExclusiveEndDatePlusOneDay() {
        // 库内两端包含（FEB6..FEB14）→ RFC DATE 半开：DTSTART=FEB6、DTEND=FEB15
        String ics = IcsCalendarWriter.write(List.of(spec(hex64("c"), FEB6, FEB14, 1, false, false)), "名", 7);
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20270206"), "DTSTART=区间起始日");
        assertTrue(ics.contains("DTEND;VALUE=DATE:20270215"),
                "exact-range DTEND=结束日+1 天（非包含端点——库内含端→RFC 半开转换）");
        assertTrue(Rfc5545Validator.violations(ics).isEmpty());
    }

    @Test
    void exactDayDtEndIsNextDayExplicit() {
        String ics = IcsCalendarWriter.write(List.of(spec(hex64("d"), SEP1, null, 1, false, false)), "名", 7);
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260901"));
        assertTrue(ics.contains("DTEND;VALUE=DATE:20260902"),
                "exact-day 显式 DTEND=次日（消除省略 DTEND 的同日语义歧义）");
    }

    @Test
    void dtStampIsUtcZuluForm() {
        String ics = IcsCalendarWriter.write(List.of(spec(hex64("e"), SEP1, null, 1, false, false)), "名", 7);
        // 2026-09-30 08:00 HKT = 2026-09-30 00:00 UTC
        assertTrue(ics.contains("DTSTAMP:20260930T000000Z"), "DTSTAMP=UTC 紧凑格式（HKT 08:00→00:00Z）");
    }

    // —— UID/时区/状态硬门 ——

    @Test
    void uidCarriesContractPrefixAndVtimezoneDeclaresHongKong() {
        String uid = "0123456789abcdef".repeat(4);
        String ics = IcsCalendarWriter.write(List.of(spec(uid, SEP1, null, 1, false, false)), "名", 7);
        // UID 行 23+64=87 八位组必折行——断言在展开面
        assertTrue(ics.replace("\r\n ", "").contains("UID:" + IcsCalendarWriter.UID_PREFIX + uid),
                "UID=urn:polyu:keydate:<库内 64hex>（前缀由导出层拼接）");
        assertTrue(ics.contains("TZID:Asia/Hong_Kong"), "VTIMEZONE 声明 Asia/Hong_Kong");
        assertTrue(ics.contains("BEGIN:VTIMEZONE") && ics.contains("END:VTIMEZONE"));
        assertTrue(ics.contains("TZOFFSETFROM:+0800") && ics.contains("TZOFFSETTO:+0800"));
        // 组件体内不引用 TZID：DATE 全天值与时区无关（RFC 合法表示）；X-WR-TIMEZONE 仅供客户端
        assertFalse(ics.contains("DTSTART;TZID"), "DATE 值不得携带 TZID 参数");
    }

    @Test
    void statusMapsCancelledAndConfirmedWithSequence() {
        String ics = IcsCalendarWriter.write(List.of(
                spec(hex64("f"), SEP1, null, 3, true, false),
                spec(hex64("g"), SEP1, null, 2, false, false)), "名", 7);
        assertTrue(ics.contains("STATUS:CANCELLED"));
        assertTrue(ics.contains("STATUS:CONFIRMED"));
        assertTrue(Pattern.compile("SEQUENCE:3[\\r\\n]+STATUS:CANCELLED").matcher(ics).find(),
                "SEQUENCE 紧随其值（revision 映射）");
        assertTrue(ics.contains("TRANSP:TRANSPARENT"));
    }

    // —— VALARM 硬门（文本面：只按 spec 渲染；白名单在服务层测试） ——

    @Test
    void valarmRenderedOnlyWhenRequestedAndNeverOnCancelled() {
        String ics = IcsCalendarWriter.write(List.of(
                spec(hex64("h"), SEP1, null, 1, false, true)), "名", 7);
        assertTrue(ics.contains("BEGIN:VALARM"));
        assertTrue(ics.contains("ACTION:DISPLAY"));
        assertTrue(ics.contains("TRIGGER:-P7D"), "前置量随参数（默认 7 天）");

        String configured = IcsCalendarWriter.write(List.of(
                spec(hex64("h2"), SEP1, null, 1, false, true)), "名", 3);
        assertTrue(configured.contains("TRIGGER:-P3D"), "前置量随配置变化（非硬编码）");

        // 取消组件永不挂 VALARM（取消后不再提醒）
        String cancelled = IcsCalendarWriter.write(List.of(
                spec(hex64("h3"), SEP1, null, 2, true, true)), "名", 7);
        assertFalse(cancelled.contains("BEGIN:VALARM"), "CANCELLED 组件不得挂 VALARM");
    }

    // —— 生成器与校验器闭环（同一判据） ——

    @Test
    void generatedFeedPassesSelfWrittenRfcValidator() {
        String ics = IcsCalendarWriter.write(List.of(
                spec(hex64("i"), SEP1, null, 1, false, true),
                spec(hex64("j"), FEB6, FEB14, 4, true, false),
                new EventSpec(hex64("k"), "长标题" + "（含逗号，分号；反斜杠\\）".repeat(10), null, null,
                        FEB6, FEB14, 7, false, false, STAMP)),
                "PolyU 关键日期 Key Dates", 7);
        List<String> violations = Rfc5545Validator.violations(ics);
        assertTrue(violations.isEmpty(), "生成 feed 须过自写校验器，违例=" + violations);
    }
}
