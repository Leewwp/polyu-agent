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

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 5545 iCalendar 生成器（#194 硬门的文本面实现；纯静态零依赖——JDK 自写，
 * 仓库依赖树无 ICS 库且不引新技术）。
 *
 * <p><b>硬门对应</b>：
 * <ul>
 *   <li>UID=合同身份键：{@code urn:polyu:keydate:<64hex>}（前缀由本层拼接，
 *       64hex 来自 t_key_date.uid——改期/撤回/恢复全程同 UID，防客户端双提醒）</li>
 *   <li>exact-range 半开转换：库内两端包含 → DATE 值 DTEND 非包含端点
 *       （DTEND=结束日+1 天）；exact-day 单日事件 DTEND=起始日+1 天</li>
 *   <li>时区：DATE 全天值按 RFC 不携带 TZID（DATE 值与时区无关）；VTIMEZONE
 *       （Asia/Hong_Kong，无夏令时=单 STANDARD 规则）随 feed 提供，供客户端
 *       解释本日历与未来升级为时刻值复用；DTSTAMP 一律 UTC（RFC 强制）</li>
 *   <li>文本转义（RFC 5545 §3.3.11）：反斜杠/分号/逗号/换行</li>
 *   <li>折行（RFC 5545 §3.1）：行长按 <b>UTF-8 八位组</b>计（CJK 3 字节），
 *       首行 ≤75、续行前导空格后 ≤74；按字符边界折行（绝不切断多字节序列）</li>
 *   <li>VALARM 仅 deadline 白名单事件（资格判定在 {@link KeyDateIcsFeedService}，
 *       本层只按 spec 渲染；CANCELLED 组件不挂 VALARM——取消后不再提醒）</li>
 * </ul>
 *
 * <p>行序=输入序（调用方保证稳定序：UID 升序——同库两次导出字节一致，利缓存
 * 与测试断言）。SUMMARY/DESCRIPTION 传入<b>未转义原文</b>，转义只发生在本层。
 */
public final class IcsCalendarWriter {

    /**
     * UID 前缀（DDL 注释合同：64hex 库值 + 导出层前缀）
     */
    public static final String UID_PREFIX = "urn:polyu:keydate:";

    /**
     * 日历标识（RFC 5545 §3.7.3 强制；语义=本服务 key date feed）
     */
    static final String PRODID = "-//polyu-agent//Key Dates Calendar//ZH";

    /**
     * 事件渲染规格（服务层从 t_key_date 行派生；日期=库内包含语义，本层换算半开 DTEND）
     *
     * @param uid            64 小写 hex（不含前缀，本层拼接）
     * @param summary        未转义标题
     * @param description    未转义描述
     * @param url            来源页 URL（可空）
     * @param dateStart      起始日（包含）
     * @param dateEndInclusive 结束日（包含；null=exact-day 单日）
     * @param sequence       SEQUENCE（=t_key_date.revision）
     * @param cancelled      true=STATUS:CANCELLED 组件（撤回/精确变模糊的取消）
     * @param alarm          true=挂 VALARM（仅 deadline 白名单）
     * @param dtStampLocal   DTSTAMP 源时刻（HKT 墙钟，取行 last_seen_at；本层转 UTC）
     */
    public record EventSpec(String uid, String summary, String description, String url,
                            LocalDate dateStart, LocalDate dateEndInclusive,
                            int sequence, boolean cancelled, boolean alarm,
                            LocalDateTime dtStampLocal) {
    }

    /**
     * 折行上限（RFC 5545 §3.1：75 octets；续行首字符为空格计入本限）
     */
    static final int LINE_OCTET_LIMIT = 75;

    private static final ZoneId FEED_ZONE = ZoneId.of("Asia/Hong_Kong");
    private static final DateTimeFormatter UTC_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DATE_COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private IcsCalendarWriter() {
    }

    /**
     * 生成完整 .ics 文本（逻辑行 → 折行 → CRLF 连接；末行也带 CRLF）。
     *
     * @param alarmLeadDays VALARM TRIGGER 前置天数（外置 rag.calendar.ics.alarm-lead-days，
     *                      仅白名单事件经 spec.alarm 渲染时使用）
     */
    public static String write(List<EventSpec> events, String calendarName, int alarmLeadDays) {
        List<String> logical = new ArrayList<>();
        logical.add("BEGIN:VCALENDAR");
        logical.add("VERSION:2.0");
        logical.add("PRODID:" + PRODID);
        logical.add("CALSCALE:GREGORIAN");
        logical.add("METHOD:PUBLISH");
        logical.add("X-WR-CALNAME:" + escapeText(calendarName));
        logical.add("X-WR-TIMEZONE:Asia/Hong_Kong");
        appendVTimezone(logical);
        for (EventSpec e : events) {
            appendEvent(logical, e, alarmLeadDays);
        }
        logical.add("END:VCALENDAR");

        StringBuilder out = new StringBuilder(4096);
        for (String line : logical) {
            for (String physical : fold(line)) {
                out.append(physical).append("\r\n");
            }
        }
        return out.toString();
    }

    /**
     * VTIMEZONE（Asia/Hong_Kong）：1979 年后无夏令时——单一 STANDARD 观测
     * （TZOFFSETFROM=TO=+0800）即合法表示；DATE 值组件不引用 TZID，本块为
     * 票面时区硬门与未来时刻值升级预留
     */
    private static void appendVTimezone(List<String> logical) {
        logical.add("BEGIN:VTIMEZONE");
        logical.add("TZID:Asia/Hong_Kong");
        logical.add("BEGIN:STANDARD");
        logical.add("DTSTART:19700101T000000");
        logical.add("TZOFFSETFROM:+0800");
        logical.add("TZOFFSETTO:+0800");
        logical.add("TZNAME:HKT");
        logical.add("END:STANDARD");
        logical.add("END:VTIMEZONE");
    }

    private static void appendEvent(List<String> logical, EventSpec e, int alarmLeadDays) {
        logical.add("BEGIN:VEVENT");
        logical.add("UID:" + UID_PREFIX + e.uid());
        logical.add("DTSTAMP:" + utcStamp(e.dtStampLocal()));
        // 库内两端包含 → RFC DATE 值 DTEND 非包含端点：+1 天（exact-range 硬门；
        // exact-day（dateEndInclusive=null）同样显式 DTEND=次日，消除「省略 DTEND
        // 的同日语义」客户端差异）
        logical.add("DTSTART;VALUE=DATE:" + e.dateStart().format(DATE_COMPACT));
        LocalDate inclusiveEnd = e.dateEndInclusive() != null ? e.dateEndInclusive() : e.dateStart();
        logical.add("DTEND;VALUE=DATE:" + inclusiveEnd.plusDays(1).format(DATE_COMPACT));
        logical.add("SEQUENCE:" + e.sequence());
        logical.add("STATUS:" + (e.cancelled() ? "CANCELLED" : "CONFIRMED"));
        logical.add("TRANSP:TRANSPARENT");
        logical.add("SUMMARY:" + escapeText(e.summary()));
        if (e.description() != null && !e.description().isBlank()) {
            logical.add("DESCRIPTION:" + escapeText(e.description()));
        }
        if (e.url() != null && !e.url().isBlank()) {
            logical.add("URL:" + e.url());
        }
        // VALARM：仅 deadline 白名单且非取消组件（票面反例：fee-notification 等
        // 非截止事件到不了这里——服务层不置 alarm）
        if (e.alarm() && !e.cancelled()) {
            appendAlarm(logical, e, alarmLeadDays);
        }
        logical.add("END:VEVENT");
    }

    /**
     * 截止提醒（ACTION:DISPLAY + TRIGGER 起点前 -P{lead}D；前置量外置
     * rag.calendar.ics.alarm-lead-days）
     */
    private static void appendAlarm(List<String> logical, EventSpec e, int alarmLeadDays) {
        logical.add("BEGIN:VALARM");
        logical.add("ACTION:DISPLAY");
        logical.add("TRIGGER:-P" + Math.max(0, alarmLeadDays) + "D");
        logical.add("DESCRIPTION:" + escapeText(e.summary()));
        logical.add("END:VALARM");
    }

    /**
     * RFC 5545 §3.3.11 TEXT 转义：反斜杠/分号/逗号/换行（其余原样——含 CJK）
     */
    static String escapeText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case ';' -> sb.append("\\;");
                case ',' -> sb.append("\\,");
                case '\n' -> sb.append("\\n");
                case '\r' -> {
                    // CRLF / CR 统一按单个换行转义（RFC：换行转义 \n）
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                        i++;
                    }
                    sb.append("\\n");
                }
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 折行（RFC 5545 §3.1）：按 UTF-8 八位组计数在字符边界切分；首段 ≤75，
     * 续段=前导空格+≤74 八位组（前导空格计入续段八位组）。输入为单条逻辑行
     * （无折行、无换行符）
     */
    static List<String> fold(String logicalLine) {
        List<String> out = new ArrayList<>(2);
        int contentLimit = LINE_OCTET_LIMIT;
        StringBuilder segment = new StringBuilder(96);
        int octets = 0;
        int i = 0;
        int n = logicalLine.length();
        while (i < n) {
            int cp = logicalLine.codePointAt(i);
            int cpOctets = utf8Octets(cp);
            if (octets + cpOctets > contentLimit) {
                out.add(segment.toString());
                segment = new StringBuilder(96).append(' '); // 续行以空格起
                octets = 1;
                contentLimit = LINE_OCTET_LIMIT - 1; // 前导空格占 1 八位组
                continue; // 不前进——当前码点计入新段
            }
            segment.appendCodePoint(cp);
            octets += cpOctets;
            i += Character.charCount(cp);
        }
        out.add(segment.toString());
        return out;
    }

    /**
     * 码点 UTF-8 八位组长度（1-4）
     */
    static int utf8Octets(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        if (codePoint < 0x10000) {
            return 3;
        }
        return 4;
    }

    /**
     * DTSTAMP（RFC §3.8.7.2 强制 UTC）：HKT 墙钟 → UTC 紧凑格式；null 兜底当前时刻
     */
    static String utcStamp(LocalDateTime hktWall) {
        LocalDateTime base = hktWall != null ? hktWall : LocalDateTime.now(FEED_ZONE);
        return base.atZone(FEED_ZONE).toInstant().atZone(ZoneId.of("UTC"))
                .format(UTC_STAMP);
    }
}
