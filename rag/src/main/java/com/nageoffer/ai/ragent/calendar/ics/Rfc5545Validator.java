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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * RFC 5545 自写校验器（#194 验收门；取舍=JDK 自写零依赖——仓库依赖树无 ICS
 * 库、不引新技术，票面开放「只读库 vs 等价校验器」）。纯函数：输入 feed 文本，
 * 输出违例列表（空=通过，带行号定位）；测试与导出面共用同一判据，输出留档即
 * 验收证据。
 *
 * <p><b>校验面（票面硬门逐条）</b>：
 * <ul>
 *   <li>行文法：仅 CRLF 分行（裸 LF/CR 违例）、物理行 ≤75 UTF-8 八位组、
 *       续行以空格/HTAB 起、无空行、首逻辑行非续行、末行带 CRLF 终界</li>
 *   <li>组件结构：BEGIN/END 配对嵌套（VCALENDAR 唯一且为根；VEVENT/VTIMEZONE
 *       只在 VCALENDAR 直接子层；VALARM 只在 VEVENT 内；STANDARD/DAYLIGHT
 *       只在 VTIMEZONE 内）</li>
 *   <li>VCALENDAR：VERSION:2.0 与 PRODID 各恰一次</li>
 *   <li>VEVENT：UID/DTSTAMP/DTSTART 必备且单值；SEQUENCE≥0 整数；STATUS ∈
 *       CONFIRMED/TENTATIVE/CANCELLED；TRANSP ∈ OPAQUE/TRANSPARENT；DTEND 值
 *       类型与 DTSTART 一致且<b>严格晚于</b> DTSTART（DATE 半开端点语义：
 *       单日事件 DTEND=DTSTART+1 天）；UID 全 feed 唯一（身份键不换/不重的
 *       RFC 面；前缀形状由导出合同测试断言）</li>
 *   <li>值文法：DATE=8 位数字；UTC DATE-TIME=yyyyMMdd'T'HHmmss'Z'；DTSTART/
 *       DTEND 携 TZID 时必须命中已声明 VTIMEZONE（顺序无关，收口时核对）；
 *       TEXT 值无裸控制字符、转义序列合法（\n \, \; \\）</li>
 *   <li>VALARM：ACTION+TRIGGER 必备；TRIGGER 时长文法（含 -PnD）；ACTION:
 *       DISPLAY 必备 DESCRIPTION</li>
 *   <li>VTIMEZONE：必备 TZID 与至少一个 STANDARD/DAYLIGHT 观测，观测内
 *       TZOFFSETFROM/TZOFFSETTO（±HHMM 文法）齐备</li>
 * </ul>
 */
public final class Rfc5545Validator {

    private Rfc5545Validator() {
    }

    /**
     * 校验入口：返回违例列表（空=通过）
     */
    public static List<String> violations(String ics) {
        return new Run(ics).validate();
    }

    private static final Pattern DATE = Pattern.compile("^\\d{8}$");
    private static final Pattern UTC_DATETIME = Pattern.compile("^\\d{8}T\\d{6}Z$");
    private static final Pattern LOCAL_DATETIME = Pattern.compile("^\\d{8}T\\d{6}$");
    private static final Pattern DURATION = Pattern.compile("^[+-]?P(\\d+W)?(\\d+D)?(T(\\d+H)(\\d+M)?(\\d+S)?)?$");
    private static final Pattern UTC_OFFSET = Pattern.compile("^[+-]\\d{4}$");
    private static final Pattern PROP_NAME = Pattern.compile("^[A-Za-z0-9-]+$");
    private static final Set<String> EVENT_STATUS = Set.of("CONFIRMED", "TENTATIVE", "CANCELLED");
    private static final Set<String> TRANSP_VALUES = Set.of("OPAQUE", "TRANSPARENT");
    private static final Set<String> EVENT_SINGLE_VALUE_PROPS = Set.of("UID", "DTSTAMP", "DTSTART",
            "DTEND", "SEQUENCE", "STATUS", "TRANSP", "SUMMARY", "DESCRIPTION", "URL");
    private static final Set<String> ALARM_SINGLE_VALUE_PROPS = Set.of("ACTION", "TRIGGER", "DESCRIPTION");

    /**
     * 单次校验的私有状态机（实例隔离，可并发/重入）
     */
    private static final class Run {

        private final List<String> out = new ArrayList<>();
        private final Deque<String> stack = new ArrayDeque<>();
        private final Set<String> declaredTzids = new HashSet<>();
        private final List<String[]> tzidRefs = new ArrayList<>(); // [tzid, lineNo]
        private final Map<String, String> uidFirstLine = new LinkedHashMap<>();

        // VCALENDAR 级
        private int vcalendarCount;
        private boolean sawVersion;
        private boolean sawProdid;

        // 当前 VEVENT 累积
        private Map<String, Integer> eventPropCounts;
        private Map<String, String> eventProps;      // name -> value（首现）
        private Map<String, String> eventValueTypes; // DTSTART/DTEND 值类型
        private String currentEventUid;

        // 当前 VALARM 累积
        private Set<String> alarmProps;
        private String alarmAction;

        // 当前 VTIMEZONE / 观测累积
        private String zoneTzid;
        private boolean zoneHasObservance;
        private boolean observanceHasFrom;
        private boolean observanceHasTo;

        List<String> validate() {
            if (text == null || text.isEmpty()) {
                out.add("feed 为空");
                return out;
            }
            List<String> physical = splitPhysical();
            List<String[]> logical = unfold(physical);
            walk(logical);
            finish();
            return out;
        }

        private final String text;

        private Run(String text) {
            this.text = text;
        }

        // —— 行文法：CRLF 分行 / 75 八位组 / 空行 ——
        private List<String> splitPhysical() {
            List<String> lines = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    lines.add(text.substring(start, i));
                    i++;
                    start = i + 1;
                } else if (c == '\n' || c == '\r') {
                    out.add("行文法：第 " + (lines.size() + 1) + " 行以裸 " + (c == '\n' ? "LF" : "CR")
                            + " 结束——RFC 5545 内容行仅允许 CRLF 分行");
                }
            }
            String tail = text.substring(start);
            if (!tail.isEmpty()) {
                lines.add(tail);
                out.add("行文法：末行缺 CRLF 终界");
            }
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int octets = line.getBytes(StandardCharsets.UTF_8).length;
                if (octets > IcsCalendarWriter.LINE_OCTET_LIMIT) {
                    out.add("行文法：第 " + (i + 1) + " 行 " + octets + " 八位组超 75 上限（UTF-8 计）");
                }
                if (line.isEmpty()) {
                    out.add("行文法：第 " + (i + 1) + " 行为空行——内容行不得为空");
                }
            }
            return lines;
        }

        private List<String[]> unfold(List<String> physical) {
            List<String[]> logical = new ArrayList<>();
            for (int i = 0; i < physical.size(); i++) {
                String line = physical.get(i);
                if (line.startsWith(" ") || line.startsWith("\t")) {
                    if (logical.isEmpty()) {
                        out.add("行文法：第 " + (i + 1) + " 行续行出现在任何逻辑行之前");
                    } else {
                        logical.get(logical.size() - 1)[0] += line.substring(1);
                    }
                } else {
                    logical.add(new String[]{line, String.valueOf(i + 1)});
                }
            }
            return logical;
        }

        // —— 结构/属性状态机 ——
        private void walk(List<String[]> logical) {
            for (String[] entry : logical) {
                Parsed p = parseContentLine(entry[0], entry[1]);
                if (p == null) {
                    continue;
                }
                String parent = stack.isEmpty() ? "" : stack.peek();
                if ("BEGIN".equals(p.name)) {
                    onBegin(p, parent);
                } else if ("END".equals(p.name)) {
                    onEnd(p);
                } else {
                    onProperty(p, parent);
                }
            }
        }

        private void onBegin(Parsed p, String parent) {
            String comp = p.value.toUpperCase();
            switch (comp) {
                case "VCALENDAR" -> {
                    if (!stack.isEmpty()) {
                        out.add("结构：第 " + p.lineNo + " 行 VCALENDAR 嵌套在其他组件内");
                    }
                    vcalendarCount++;
                }
                case "VEVENT" -> {
                    if (!"VCALENDAR".equals(parent)) {
                        out.add("结构：第 " + p.lineNo + " 行 VEVENT 不在 VCALENDAR 直接子层");
                    }
                    eventPropCounts = new HashMap<>();
                    eventProps = new LinkedHashMap<>();
                    eventValueTypes = new HashMap<>();
                    currentEventUid = null;
                }
                case "VTIMEZONE" -> {
                    if (!"VCALENDAR".equals(parent)) {
                        out.add("结构：第 " + p.lineNo + " 行 VTIMEZONE 不在 VCALENDAR 直接子层");
                    }
                    zoneTzid = null;
                    zoneHasObservance = false;
                }
                case "VALARM" -> {
                    if (!"VEVENT".equals(parent)) {
                        out.add("结构：第 " + p.lineNo + " 行 VALARM 不在 VEVENT 内");
                    }
                    alarmProps = new HashSet<>();
                    alarmAction = null;
                }
                case "STANDARD", "DAYLIGHT" -> {
                    if (!"VTIMEZONE".equals(parent)) {
                        out.add("结构：第 " + p.lineNo + " 行 " + comp + " 不在 VTIMEZONE 内");
                    }
                    zoneHasObservance = true;
                    observanceHasFrom = false;
                    observanceHasTo = false;
                }
                default -> out.add("结构：第 " + p.lineNo + " 行未知组件 BEGIN:" + p.value);
            }
            stack.push(comp);
        }

        private void onEnd(Parsed p) {
            String comp = p.value.toUpperCase();
            if (stack.isEmpty() || !stack.peek().equals(comp)) {
                out.add("结构：第 " + p.lineNo + " 行 END:" + comp + " 与当前组件 "
                        + (stack.isEmpty() ? "（无）" : stack.peek()) + " 不配对");
                return;
            }
            stack.pop();
            switch (comp) {
                case "VEVENT" -> closeEvent(p.lineNo);
                case "VALARM" -> closeAlarm(p.lineNo);
                case "STANDARD", "DAYLIGHT" -> {
                    if (!observanceHasFrom || !observanceHasTo) {
                        out.add("属性：第 " + p.lineNo + " 行 " + comp + " 观测缺 TZOFFSETFROM/TZOFFSETTO");
                    }
                }
                case "VTIMEZONE" -> {
                    if (zoneTzid == null) {
                        out.add("属性：第 " + p.lineNo + " 行 VTIMEZONE 缺 TZID");
                    } else {
                        declaredTzids.add(zoneTzid);
                    }
                    if (!zoneHasObservance) {
                        out.add("属性：第 " + p.lineNo + " 行 VTIMEZONE 缺 STANDARD/DAYLIGHT 观测");
                    }
                }
                default -> {
                }
            }
        }

        private void onProperty(Parsed p, String parent) {
            switch (parent) {
                case "VCALENDAR" -> {
                    if ("VERSION".equals(p.name)) {
                        if (!"2.0".equals(p.value)) {
                            out.add("属性：第 " + p.lineNo + " 行 VERSION 应为 2.0，实际 " + p.value);
                        }
                        sawVersion = true;
                    } else if ("PRODID".equals(p.name)) {
                        sawProdid = true;
                    }
                }
                case "VEVENT" -> onEventProp(p);
                case "VALARM" -> onAlarmProp(p);
                case "VTIMEZONE" -> {
                    if ("TZID".equals(p.name) && !p.value.isBlank()) {
                        zoneTzid = p.value;
                    }
                }
                case "STANDARD", "DAYLIGHT" -> {
                    if ("TZOFFSETFROM".equals(p.name)) {
                        checkOffset(p);
                        observanceHasFrom = true;
                    } else if ("TZOFFSETTO".equals(p.name)) {
                        checkOffset(p);
                        observanceHasTo = true;
                    }
                }
                default -> out.add("结构：第 " + p.lineNo + " 行属性 " + p.name + " 出现在组件外");
            }
        }

        private void onEventProp(Parsed p) {
            if (EVENT_SINGLE_VALUE_PROPS.contains(p.name)) {
                eventPropCounts.merge(p.name, 1, Integer::sum);
                eventProps.putIfAbsent(p.name, p.value);
            }
            switch (p.name) {
                case "UID" -> {
                    if (p.value.isBlank()) {
                        out.add("属性：第 " + p.lineNo + " 行 UID 为空");
                    } else {
                        String prev = uidFirstLine.put(p.value, p.lineNo);
                        if (prev != null) {
                            out.add("属性：UID 重复 " + p.value + "（第 " + prev + " 行与第 " + p.lineNo + " 行）——身份键须全 feed 唯一");
                        }
                        currentEventUid = p.value;
                    }
                }
                case "DTSTAMP" -> {
                    if (!UTC_DATETIME.matcher(p.value).matches()) {
                        out.add("属性：第 " + p.lineNo + " 行 DTSTAMP 应为 UTC 时刻（…T…Z），实际 " + p.value);
                    }
                }
                case "DTSTART", "DTEND" -> checkDateLike(p, p.name);
                case "SEQUENCE" -> {
                    if (!p.value.matches("\\d+")) {
                        out.add("属性：第 " + p.lineNo + " 行 SEQUENCE 应为非负整数：" + p.value);
                    }
                }
                case "STATUS" -> {
                    if (!EVENT_STATUS.contains(p.value)) {
                        out.add("属性：第 " + p.lineNo + " 行 STATUS 非法：" + p.value);
                    }
                }
                case "TRANSP" -> {
                    if (!TRANSP_VALUES.contains(p.value)) {
                        out.add("属性：第 " + p.lineNo + " 行 TRANSP 非法：" + p.value);
                    }
                }
                case "SUMMARY", "DESCRIPTION" -> checkTextValue(p);
                case "URL" -> {
                    if (p.value.chars().anyMatch(c -> c <= 0x20)) {
                        out.add("属性：第 " + p.lineNo + " 行 URL 含空白字符");
                    }
                }
                default -> {
                }
            }
        }

        /**
         * DTSTART/DTEND：VALUE=DATE（8 位）或 DATE-TIME（UTC Z 或 TZID 引用——
         * 引用与声明顺序无关，收口时核对）
         */
        private void checkDateLike(Parsed p, String name) {
            String type = p.params.get("VALUE");
            if (type == null) {
                if (!LOCAL_DATETIME.matcher(p.value).matches() && !UTC_DATETIME.matcher(p.value).matches()) {
                    out.add("属性：第 " + p.lineNo + " 行 " + name + " 无 VALUE 参数且非 DATE-TIME 文法：" + p.value);
                }
                if (UTC_DATETIME.matcher(p.value).matches()) {
                    eventValueTypes.put(name, "DATE-TIME-UTC");
                } else {
                    eventValueTypes.put(name, "DATE-TIME-LOCAL");
                    String tzid = p.params.get("TZID");
                    if (tzid == null) {
                        out.add("属性：第 " + p.lineNo + " 行 " + name + " 本地时刻未携 TZID（浮动时刻）");
                    } else {
                        tzidRefs.add(new String[]{tzid, p.lineNo});
                    }
                }
            } else if ("DATE".equals(type)) {
                if (!DATE.matcher(p.value).matches()) {
                    out.add("属性：第 " + p.lineNo + " 行 " + name + ";VALUE=DATE 应为 8 位数字，实际 " + p.value);
                }
                eventValueTypes.put(name, "DATE");
            } else if ("DATE-TIME".equals(type)) {
                if (!LOCAL_DATETIME.matcher(p.value).matches() && !UTC_DATETIME.matcher(p.value).matches()) {
                    out.add("属性：第 " + p.lineNo + " 行 " + name + ";VALUE=DATE-TIME 文法非法：" + p.value);
                }
                eventValueTypes.put(name, "DATE-TIME");
            } else {
                out.add("属性：第 " + p.lineNo + " 行 " + name + " 未知 VALUE 类型 " + type);
            }
        }

        private void checkOffset(Parsed p) {
            if (!UTC_OFFSET.matcher(p.value).matches()) {
                out.add("属性：第 " + p.lineNo + " 行 " + p.name + " 非 ±HHMM 文法：" + p.value);
            }
        }

        private void checkTextValue(Parsed p) {
            String v = p.value;
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c == '\\' && i + 1 < v.length()) {
                    char next = v.charAt(i + 1);
                    if (next == 'n' || next == 'N' || next == '\\' || next == ';' || next == ',') {
                        i++;
                        continue;
                    }
                    out.add("属性：第 " + p.lineNo + " 行 " + p.name + " 非法转义 \\" + next);
                    continue;
                }
                if (c == '\\') {
                    continue; // 行尾孤立反斜杠——转义缺口，收口无对象，仅略过
                }
                if (c < 0x20) {
                    out.add("属性：第 " + p.lineNo + " 行 " + p.name + " 含裸控制字符 U+"
                            + String.format("%04X", (int) c));
                }
            }
        }

        private void onAlarmProp(Parsed p) {
            if (ALARM_SINGLE_VALUE_PROPS.contains(p.name)) {
                alarmProps.add(p.name);
            }
            switch (p.name) {
                case "ACTION" -> {
                    alarmAction = p.value;
                    if (!"DISPLAY".equals(p.value) && !"AUDIO".equals(p.value) && !"EMAIL".equals(p.value)) {
                        out.add("属性：第 " + p.lineNo + " 行 VALARM ACTION 非法：" + p.value);
                    }
                }
                case "TRIGGER" -> {
                    String rel = p.params.get("RELATED");
                    if (rel != null && !"START".equals(rel) && !"END".equals(rel)) {
                        out.add("属性：第 " + p.lineNo + " 行 TRIGGER;RELATED 非法：" + rel);
                    }
                    if (!DURATION.matcher(p.value).matches()) {
                        out.add("属性：第 " + p.lineNo + " 行 TRIGGER 非时长文法：" + p.value);
                    }
                }
                default -> {
                }
            }
        }

        private void closeEvent(String lineNo) {
            for (String required : new String[]{"UID", "DTSTAMP", "DTSTART"}) {
                if (!eventProps.containsKey(required)) {
                    out.add("属性：第 " + lineNo + " 行 VEVENT（uid=" + abbreviate(currentEventUid)
                            + "）缺必备属性 " + required);
                }
            }
            for (String prop : EVENT_SINGLE_VALUE_PROPS) {
                int count = eventPropCounts.getOrDefault(prop, 0);
                if (count > 1) {
                    out.add("属性：第 " + lineNo + " 行 VEVENT 属性 " + prop + " 出现 " + count + " 次（应单值）");
                }
            }
            String start = eventProps.get("DTSTART");
            String end = eventProps.get("DTEND");
            String startType = eventValueTypes.get("DTSTART");
            String endType = eventValueTypes.get("DTEND");
            if (start != null && end != null) {
                if (!String.valueOf(startType).equals(String.valueOf(endType))) {
                    out.add("属性：第 " + lineNo + " 行 DTEND 值类型(" + endType + ")与 DTSTART(" + startType + ")不一致");
                } else if ("DATE".equals(startType) && end.compareTo(start) <= 0) {
                    out.add("属性：第 " + lineNo + " 行 DATE 值 DTEND(" + end + ") 须严格晚于 DTSTART("
                            + start + ")——半开端点语义（单日事件 DTEND=DTSTART+1 天）");
                }
            }
            eventPropCounts = null;
            eventProps = null;
            eventValueTypes = null;
        }

        private void closeAlarm(String lineNo) {
            if (!alarmProps.contains("ACTION")) {
                out.add("属性：第 " + lineNo + " 行 VALARM 缺 ACTION");
            }
            if (!alarmProps.contains("TRIGGER")) {
                out.add("属性：第 " + lineNo + " 行 VALARM 缺 TRIGGER");
            }
            if ("DISPLAY".equals(alarmAction) && !alarmProps.contains("DESCRIPTION")) {
                out.add("属性：第 " + lineNo + " 行 VALARM(ACTION:DISPLAY) 缺 DESCRIPTION");
            }
            alarmProps = null;
        }

        private void finish() {
            if (vcalendarCount != 1) {
                out.add("结构：VCALENDAR 应恰一个，实际 " + vcalendarCount);
            }
            if (!sawVersion) {
                out.add("属性：VCALENDAR 缺 VERSION");
            }
            if (!sawProdid) {
                out.add("属性：VCALENDAR 缺 PRODID");
            }
            if (!stack.isEmpty()) {
                out.add("结构：组件未闭合：" + stack);
            }
            for (String[] ref : tzidRefs) {
                if (!declaredTzids.contains(ref[0])) {
                    out.add("属性：第 " + ref[1] + " 行 DTSTART/DTEND 引用未声明 TZID:" + ref[0]);
                }
            }
        }

        // —— 内容行解析 ——

        private Parsed parseContentLine(String line, String lineNo) {
            int colon = indexOfValueColon(line);
            if (colon < 0) {
                out.add("行文法：第 " + lineNo + " 行缺属性值分隔冒号：" + abbreviate(line));
                return null;
            }
            String head = line.substring(0, colon);
            String value = line.substring(colon + 1);
            String name = head;
            Map<String, String> params = new HashMap<>();
            int semi = head.indexOf(';');
            if (semi >= 0) {
                name = head.substring(0, semi);
                String rest = head.substring(semi + 1);
                for (String param : rest.split(";")) {
                    int eq = param.indexOf('=');
                    if (eq < 0) {
                        out.add("行文法：第 " + lineNo + " 行参数缺 =：" + abbreviate(param));
                        continue;
                    }
                    params.put(param.substring(0, eq).toUpperCase(), param.substring(eq + 1).replace("\"", ""));
                }
            }
            if (!PROP_NAME.matcher(name).matches()) {
                out.add("行文法：第 " + lineNo + " 行属性名非法：" + abbreviate(name));
                return null;
            }
            return new Parsed(name.toUpperCase(), value, params, lineNo);
        }

        /**
         * 值分隔冒号：跳过带引号参数值内的冒号
         */
        private int indexOfValueColon(String line) {
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    quoted = !quoted;
                } else if (c == ':' && !quoted) {
                    return i;
                }
            }
            return -1;
        }
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    private record Parsed(String name, String value, Map<String, String> params, String lineNo) {
    }
}
