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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/**
 * 稳定事件身份 UID（校历合同§3）：固定顺序六段数组
 * {@code ["polyu-keydate-v1", 学年, 学期, 事件码, 受众码, 语义阶段]} 序列化为
 * 无空白 UTF-8 JSON 后取完整 SHA-256 小写十六进制（恰 64 字符）。
 *
 * <p>身份不含日期、标题、raw_text、页面位置、抓取时间、来源URL——这些都是可变
 * 证据或展示字段（改期/改文案不换 UID，跨学年才是新事件）。无 occurrence、无
 * 45 天邻近匹配（r3 已撤销）。序列化不依赖具体 JSON 库：身份字段为封闭代码集
 * （kebab-case/学年斜杠/AY-S1-S2-SU），本类的转义覆盖引号/反斜杠/控制字符即可
 * 与规范 JSON 逐字节一致；ICS UID 前缀 {@code urn:polyu:keydate:} 由导出层拼接，
 * 不落 64 位库字段。
 */
public final class KeyDateUid {

    /**
     * 命名空间版本（身份数组首段，算法升版才改）
     */
    public static final String VERSION = "polyu-keydate-v1";

    private KeyDateUid() {
    }

    /**
     * 规范身份数组（固定顺序：版本,学年,学期,事件码,受众码,语义阶段）
     */
    public static List<String> identityFields(String academicYear,
                                              String term,
                                              String eventCode,
                                              String audienceCode,
                                              String semanticSlot) {
        return List.of(VERSION, academicYear, term, eventCode, audienceCode, semanticSlot);
    }

    /**
     * 规范序列化：无空白 UTF-8 JSON 数组（ensure_ascii=False 等价——原文直存不转 \\u）
     */
    public static String canonicalIdentity(String academicYear,
                                           String term,
                                           String eventCode,
                                           String audienceCode,
                                           String semanticSlot) {
        String[] parts = {VERSION, academicYear, term, eventCode, audienceCode, semanticSlot};
        StringBuilder sb = new StringBuilder(96);
        sb.append('[');
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendJsonString(sb, parts[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * UID = SHA-256(规范字节)，小写完整 64 hex；不截断、不对展示文字做哈希兜底。
     * 输入只有六个身份参数——不存在日期/历史版本参数位，日期邻近匹配无从发生
     */
    public static String uidOf(String academicYear,
                               String term,
                               String eventCode,
                               String audienceCode,
                               String semanticSlot) {
        byte[] canonical = canonicalIdentity(academicYear, term, eventCode, audienceCode, semanticSlot)
                .getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 规范保证 SHA-256 存在
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * JSON 字符串转义（规范 JSON 子集）：引号/反斜杠/常见控制短转义，其余控制字符
     * \\u00XX。与 Python json.dumps(ensure_ascii=False, separators=(",", ":")) 逐字节一致
     */
    private static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
