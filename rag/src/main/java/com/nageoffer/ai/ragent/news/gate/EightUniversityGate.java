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

package com.nageoffer.ai.ragent.news.gate;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 教资会八校确定性白名单门（#277）：纯函数谓词——原始标题或去 HTML 的
 * feed 原始摘要命中八校实体才准入；不命中=零落库/零详情抓取/零 LLM
 * （在 fresh/stale 队列、名额消耗之前拦截）。
 *
 * <p>词表可配置（rag.news.{unambiguous,contextual}-terms 平铺键），
 * 边界与语境规则是固定语义、不造规则引擎：
 * <ul>
 *   <li><b>英文按 token 边界</b>：HKU 不子串命中 HKUST（前后均不得是 ASCII 字母）；</li>
 *   <li><b>歧义短名需香港高校语境</b>（文本出现 Hong Kong / 独立 HK / 香港）：
 *       CityU、Lingnan、城市大学、中大/科大/城大/岭南——防公司名、地名与
 *       境外同名大学误命中；</li>
 *   <li><b>站名承袭排除语境</b>（#409）：「HKU station」「HKU MTR station」
 *       「港大站」「香港大學站/香港大学站（官方全名）」等承袭大学名的港铁站名
 *       在词条匹配前被抹除——站名用法不算八校命中（#318/doc100 id 225
 *       判例：通勤语境站名触发为 17 天观察窗唯一误收模式）；同文并存的真校名
 *       命中不受影响（只抹站名用法、不吞整文）；</li>
 *   <li><b>不设显式负词表</b>：单独 LU、泛词「大学/香港/教育」不在词表即天然不准入。</li>
 * </ul>
 *
 * <p>实体边界=UGC 八所资助大学（港大/中大/科大/理大/城大/浸大/岭南/教大，
 * 中英文全名+繁简常用名+明确缩写）。富化后的 LLM 摘要、网页导航/footer
 * 不得作准入证据（票面红线）。
 */
public final class EightUniversityGate {

    /**
     * 站名承袭排除语境（#409）：承袭大学名的港铁站名——港大站，官方全名
     * 「香港大學站/香港大学站」（繁简）。英文侧独立 token 的 HKU（前后非 ASCII
     * 字母）后接空白+可选 MTR+station（station 后不得再接 ASCII 字母，防
     * 「HKU stationery」这类真高校语境被误抹），覆盖「HKU station」与港铁
     * 限定形「HKU MTR station」；中文侧「港大站」与官方全名「香港大學站/
     * 香港大学站」各自整体（官方全名整串即含「香港大學/香港大学」校名全名
     * 词条原文，不排除则全名站名标题伪过门）。匹配前以单空格抹除——站名用法
     * 不参与词条命中，其余文本（含同文真校名）不受影响。八校中仅港大存在
     * 同名港铁站，无其他站名可承袭
     */
    private static final Pattern STATION_NAME_INHERITANCE = Pattern.compile(
            "(?i)(?<![A-Za-z])HKU(?![A-Za-z])\\s+(?:MTR\\s+)?station(?![A-Za-z])|港大站|香港大學站|香港大学站");

    private final List<Pattern> unambiguous;
    private final List<Pattern> contextual;
    private final Pattern hongKongContext;

    public EightUniversityGate(List<String> unambiguousTerms, List<String> contextualTerms) {
        this.unambiguous = unambiguousTerms.stream().map(EightUniversityGate::termPattern).toList();
        this.contextual = contextualTerms.stream().map(EightUniversityGate::termPattern).toList();
        this.hongKongContext = Pattern.compile("(?i)hong\\s+kong|(?<![A-Za-z])HK(?![A-Za-z])|香港");
    }

    /**
     * 标题或原始摘要是否命中八校（原始标题/去 HTML 原摘要为仅有的两类证据）
     */
    public boolean mentions(String title, String rawSummary) {
        String text = (title == null ? "" : title) + "\n" + (rawSummary == null ? "" : rawSummary);
        if (text.isBlank()) {
            return false;
        }
        // 站名承袭排除语境（#409）：港铁站名用法先抹除（单空格），再进词条匹配——
        // 「HKU station」「HKU MTR station」「港大站」「香港大學站/香港大学站」
        // 不构成八校命中，同文其余真校名不受影响
        String gateEvidence = STATION_NAME_INHERITANCE.matcher(text).replaceAll(" ");
        for (Pattern pattern : unambiguous) {
            if (pattern.matcher(gateEvidence).find()) {
                return true;
            }
        }
        if (hongKongContext.matcher(gateEvidence).find()) {
            for (Pattern pattern : contextual) {
                if (pattern.matcher(gateEvidence).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 词条 → 匹配模式：纯 ASCII 词条（含空格短语）按词边界+大小写不敏感；
     * 含 CJK 的词条（繁简形各自列词表）按原样子串（大小写不敏感无副作用）
     */
    private static Pattern termPattern(String term) {
        String stripped = term.strip();
        if (stripped.matches("[A-Za-z][A-Za-z ]*[A-Za-z]|[A-Za-z]")) {
            return Pattern.compile("(?i)(?<![A-Za-z])" + Pattern.quote(stripped) + "(?![A-Za-z])");
        }
        return Pattern.compile("(?i)" + Pattern.quote(stripped));
    }

    /**
     * 默认词表（与 application.yaml 缺省一致；配置可整组覆盖）：
     * 无歧义组=带「香港/Hong Kong」限定词的全名与特指性简称/缩写
     */
    public static EightUniversityGate withDefaults() {
        return new EightUniversityGate(List.of(
                // 英文全名与明确缩写（任意语境）
                "University of Hong Kong", "Chinese University of Hong Kong",
                "Hong Kong University of Science and Technology",
                "Hong Kong Polytechnic University", "Polytechnic University",
                "Hong Kong Baptist University", "Education University of Hong Kong",
                "HKU", "HKUST", "CUHK", "HKBU", "EdUHK", "PolyU",
                // 中文全名（繁+简；前缀「香港」自证语境）
                "香港大學", "香港大学", "香港中文大學", "香港中文大学",
                "香港科技大學", "香港科技大学", "香港理工大學", "香港理工大学",
                "香港浸會大學", "香港浸会大学", "香港城市大學", "香港城市大学",
                "香港教育大學", "香港教育大学", "嶺南大學", "岭南大学",
                // 特指性简称（港语境独有指向）
                "港大", "理大", "浸大", "教大"
        ), List.of(
                // 歧义组：需文本另有 Hong Kong / HK / 香港语境
                "CityU", "Lingnan", "City University", "城市大學", "城市大学",
                "中大", "科大", "城大", "嶺南", "岭南"
        ));
    }

    /** 调试/日志友好：小写词表串 */
    public String describe() {
        return "eight-university-gate[" + unambiguous.size() + "+" + contextual.size() + " terms]";
    }

}
