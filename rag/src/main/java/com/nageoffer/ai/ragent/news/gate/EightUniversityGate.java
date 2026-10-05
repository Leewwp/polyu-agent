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
 * <p>词表可配置（rag.news.eight-university-gate.{unambiguous,contextual}-terms），
 * 边界与语境规则是固定语义、不造规则引擎：
 * <ul>
 *   <li><b>英文按 token 边界</b>：HKU 不子串命中 HKUST（前后均不得是 ASCII 字母）；</li>
 *   <li><b>歧义短名需香港高校语境</b>（文本出现 Hong Kong / 独立 HK / 香港）：
 *       CityU、Lingnan、城市大学、中大/科大/城大/岭南——防公司名、地名与
 *       境外同名大学误命中；</li>
 *   <li><b>不设显式负词表</b>：单独 LU、泛词「大学/香港/教育」不在词表即天然不准入。</li>
 * </ul>
 *
 * <p>实体边界=UGC 八所资助大学（港大/中大/科大/理大/城大/浸大/岭南/教大，
 * 中英文全名+繁简常用名+明确缩写）。富化后的 LLM 摘要、网页导航/footer
 * 不得作准入证据（票面红线）。
 */
public final class EightUniversityGate {

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
        for (Pattern pattern : unambiguous) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        if (hongKongContext.matcher(text).find()) {
            for (Pattern pattern : contextual) {
                if (pattern.matcher(text).find()) {
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
