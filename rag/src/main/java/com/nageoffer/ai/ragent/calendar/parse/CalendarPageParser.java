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

package com.nageoffer.ai.ragent.calendar.parse;

import java.util.List;
import java.util.regex.Matcher;

/**
 * 逐源解析器接口：主表行 + 页面可见文本 → 候选片段列表（含 UNKNOWN——由门禁
 * 转整源退化）。解析为纯函数：无时钟、无状态、无 IO——同一输入恒同一输出
 * （K06：时钟不可能参与身份/覆盖域，由签名与实现共同保证）。
 */
public interface CalendarPageParser {

    /**
     * 本解析器负责的 source_key（合同§2 五源之一）
     */
    String sourceKey();

    /**
     * 解析主表全部行为候选片段（每个业务片段恰有一去向；无兜底猜测）
     */
    List<com.nageoffer.ai.ragent.calendar.model.KeyDateCandidate> parse(
            List<List<String>> rows, String pageText);

    /**
     * 页面覆盖学年证据（合同§4 门 1）：#1 双证据（表内 "Academic Year … ends" 行
     * + 互异年份段头相差 1 年且与散文一致），其余源散文正则。无证据返回 null
     * →整源退化。绝不用当前时钟推断学年（抓取时钟不能把陈旧表重新标成年份正确的表）
     */
    static String pageAy(String sourceKey, String pageText, List<List<String>> rows) {
        if ("cal-academic-calendar".equals(sourceKey)) {
            List<Integer> years = rows.stream()
                    .filter(r -> r.size() == 1 && r.get(0).matches("^\\d{4}$"))
                    .map(r -> Integer.parseInt(r.get(0)))
                    .distinct().sorted().toList();
            Matcher m = CalendarLexicon.AY_PROSE.get(sourceKey).matcher(pageText);
            if (years.size() >= 2 && years.get(1) == years.get(0) + 1 && m.find()) {
                String expected = "%d/%02d".formatted(years.get(0), years.get(1) % 100);
                if (expected.equals(m.group(1))) {
                    return expected;
                }
            }
            return null;
        }
        Matcher m = CalendarLexicon.AY_PROSE.get(sourceKey).matcher(pageText);
        return m.find() ? m.group(1) : null;
    }
}
