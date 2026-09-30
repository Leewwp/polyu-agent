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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自写 RFC 5545 校验器的反例证明（#194 验收：校验器不是橡皮图章——每条硬门
 * 都有「埋错→必报」用例）：裸 LF、超 75 八位组、缺 UID/DTSTAMP/DTSTART、
 * DATE DTEND≤DTSTART（半开端点）、UID 重复、VALARM 缺 TRIGGER、时长文法、
 * VTIMEZONE 缺 TZID、TZID 引用未声明、STATUS 非法、组件不配对、缺
 * VERSION/PRODID、末行缺 CRLF。正例（合法最小 feed+生成器闭环）在
 * {@code IcsCalendarWriterTests.generatedFeedPassesSelfWrittenRfcValidator}。
 */
class Rfc5545ValidatorTests {

    /**
     * 合法最小 feed 模板（CRLF 分行；正文变量按用例替换）
     */
    private static final String[] SKELETON = {
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//polyu-agent//Key Dates Calendar//ZH",
            "BEGIN:VTIMEZONE",
            "TZID:Asia/Hong_Kong",
            "BEGIN:STANDARD",
            "DTSTART:19700101T000000",
            "TZOFFSETFROM:+0800",
            "TZOFFSETTO:+0800",
            "TZNAME:HKT",
            "END:STANDARD",
            "END:VTIMEZONE",
            "BEGIN:VEVENT",
            "UID:urn:polyu:keydate:%s",
            "DTSTAMP:20260930T000000Z",
            "DTSTART;VALUE=DATE:20260901",
            "DTEND;VALUE=DATE:20260902",
            "SEQUENCE:1",
            "STATUS:CONFIRMED",
            "END:VEVENT",
            "END:VCALENDAR",
    };

    private static String feed(String uid, String... replacements) {
        StringBuilder sb = new StringBuilder();
        for (String line : SKELETON) {
            String resolved = line.formatted(uid);
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                resolved = resolved.replace(replacements[i], replacements[i + 1]);
            }
            for (String physical : IcsCalendarWriter.fold(resolved)) {
                sb.append(physical).append("\r\n");
            }
        }
        return sb.toString();
    }

    private static void mustReport(String ics, String keyword, String why) {
        List<String> violations = Rfc5545Validator.violations(ics);
        assertTrue(violations.stream().anyMatch(v -> v.contains(keyword)),
                why + "——应报含「" + keyword + "」的违例，实际=" + violations);
    }

    @Test
    void wellFormedFeedPasses() {
        assertTrue(Rfc5545Validator.violations(feed("a".repeat(64))).isEmpty(),
                "合法最小 feed 应零违例");
    }

    @Test
    void bareLineFeedRejected() {
        String ics = feed("b".repeat(64)).replace("SEQUENCE:1\r\n", "SEQUENCE:1\n");
        mustReport(ics, "裸 LF", "行仅允许 CRLF 分行");
    }

    @Test
    void octetOver75Rejected() {
        // 折行后的合法 feed 上注入一条未折行超长行（feed() 会自动折行——须折叠后替换）
        String ics = feed("c".repeat(64)).replace("SEQUENCE:1\r\n", "SEQUENCE:" + "9".repeat(80) + "\r\n");
        mustReport(ics, "八位组超 75", "物理行（未折行）超 75 八位组");
    }

    @Test
    void missingTrailingCrLfRejected() {
        String ics = feed("d".repeat(64));
        ics = ics.substring(0, ics.length() - 2);
        mustReport(ics, "缺 CRLF 终界", "末行也须 CRLF 终界");
    }

    @Test
    void missingUidDtstampDtstartRejected() {
        String uidE = "e".repeat(64);
        // UID 行 86 八位组被 feed() 折为两物理行——按折叠形整体替换为移除标记
        // （折叠点由 fold() 现算，不手写偏移）
        String foldedUid = String.join("\r\n", IcsCalendarWriter.fold("UID:urn:polyu:keydate:" + uidE));
        mustReport(feed(uidE).replace(foldedUid, "X-REMOVED:uid"),
                "缺必备属性 UID", "VEVENT 缺 UID");
        mustReport(feed("f".repeat(64)).replace("DTSTAMP:20260930T000000Z", "X-REMOVED:stamp"),
                "DTSTAMP", "VEVENT 缺 DTSTAMP");
        mustReport(feed("0".repeat(64)).replace("DTSTART;VALUE=DATE:20260901", "X-REMOVED:start"),
                "DTSTART", "VEVENT 缺 DTSTART");
    }

    @Test
    void dateDtEndNotAfterDtStartRejected() {
        // 相等=零时长（半开端点语义下非法）；早于更非法
        mustReport(feed("1".repeat(64), "DTEND;VALUE=DATE:20260902", "DTEND;VALUE=DATE:20260901"),
                "严格晚于", "DATE 值 DTEND 须严格晚于 DTSTART（单日=+1 天）");
        mustReport(feed("2".repeat(64), "DTEND;VALUE=DATE:20260902", "DTEND;VALUE=DATE:20260831"),
                "严格晚于", "DTEND 早于 DTSTART 同报");
    }

    @Test
    void duplicateUidRejected() {
        String dup = "3".repeat(64);
        String one = String.join("\r\n",
                "BEGIN:VEVENT",
                "UID:urn:polyu:keydate:" + dup,
                "DTSTAMP:20260930T000000Z",
                "DTSTART;VALUE=DATE:20260901",
                "DTEND;VALUE=DATE:20260902",
                "END:VEVENT");
        String two = one.replace("DTSTART;VALUE=DATE:20260901", "DTSTART;VALUE=DATE:20261001")
                .replace("DTEND;VALUE=DATE:20260902", "DTEND;VALUE=DATE:20261002");
        String rebuilt = feed("4".repeat(64))
                .replace("END:VCALENDAR\r\n", one + "\r\n" + two + "\r\n" + "END:VCALENDAR\r\n");
        mustReport(rebuilt, "UID 重复", "同 UID 两组件（双提醒风险）须报重复");
    }

    @Test
    void valarmWithoutTriggerOrBadDurationRejected() {
        String noTrigger = feed("5".repeat(64)).replace("END:VEVENT",
                "BEGIN:VALARM\r\nACTION:DISPLAY\r\nDESCRIPTION:x\r\nEND:VALARM\r\nEND:VEVENT");
        mustReport(noTrigger, "缺 TRIGGER", "VALARM 必备 TRIGGER");
        String badDuration = feed("6".repeat(64)).replace("END:VEVENT",
                "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:7days\r\nDESCRIPTION:x\r\nEND:VALARM\r\nEND:VEVENT");
        mustReport(badDuration, "时长文法", "TRIGGER 须 ISO 8601 时长（如 -P7D）");
        String displayNoDescription = feed("7".repeat(64)).replace("END:VEVENT",
                "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-P7D\r\nEND:VALARM\r\nEND:VEVENT");
        mustReport(displayNoDescription, "缺 DESCRIPTION", "ACTION:DISPLAY 必备 DESCRIPTION");
    }

    @Test
    void vtimezoneWithoutTzidRejectedAndUndeclaredTzidRefRejected() {
        String noTzid = feed("8".repeat(64), "TZID:Asia/Hong_Kong", "X-TZ:removed");
        mustReport(noTzid, "缺 TZID", "VTIMEZONE 必备 TZID");
        // DTSTART 携未声明 TZID 的本地时刻
        String ics = feed("9".repeat(64),
                "DTSTART;VALUE=DATE:20260901", "DTSTART;TZID=America/New_York:20260901T090000",
                "DTEND;VALUE=DATE:20260902", "DTEND;TZID=America/New_York:20260901T100000");
        mustReport(ics, "未声明 TZID", "TZID 引用必须命中已声明 VTIMEZONE");
    }

    @Test
    void badStatusAndMismatchedEndRejected() {
        String badStatus = feed("a1".repeat(32), "STATUS:CONFIRMED", "STATUS:CANCEL");
        mustReport(badStatus, "STATUS 非法", "STATUS 枚举外取值");
        String mismatched = feed("a2".repeat(32)).replace("END:VTIMEZONE", "END:VEVENT");
        mustReport(mismatched, "不配对", "END 与当前组件不配对");
    }

    @Test
    void missingVersionProdidRejected() {
        String noVersion = feed("a3".repeat(32), "VERSION:2.0", "X-VER:removed");
        mustReport(noVersion, "缺 VERSION", "VCALENDAR 必备 VERSION:2.0");
        String noProdid = feed("a4".repeat(32), "PRODID:-//polyu-agent//Key Dates Calendar//ZH", "X-PID:removed");
        mustReport(noProdid, "缺 PRODID", "VCALENDAR 必备 PRODID");
        String wrongVersion = feed("a5".repeat(32), "VERSION:2.0", "VERSION:1.0");
        mustReport(wrongVersion, "VERSION 应为 2.0", "仅支持 iCalendar 2.0");
    }

    @Test
    void emptyAndBlankTextControlCharRejected() {
        assertTrue(Rfc5545Validator.violations("").contains("feed 为空"), "空 feed 报违例");
        String control = feed("a6".repeat(32), "STATUS:CONFIRMED", "SUMMARY:bad\u0001control");
        mustReport(control, "裸控制字符", "TEXT 值不得含裸控制字符");
    }

    @Test
    void validatorIsReentrantAndStateless() {
        // 同一实例路径连续两次校验结果一致（无跨 run 状态残留）
        List<String> first = Rfc5545Validator.violations(feed("a7".repeat(32)));
        List<String> broken = Rfc5545Validator.violations(feed("a8".repeat(32), "SEQUENCE:1", "SEQUENCE:x"));
        List<String> second = Rfc5545Validator.violations(feed("a7".repeat(32)));
        assertTrue(first.isEmpty());
        assertFalse(broken.isEmpty());
        assertTrue(second.isEmpty(), "坏 feed 校验后好 feed 仍须零违例（无状态泄漏）");
    }
}
