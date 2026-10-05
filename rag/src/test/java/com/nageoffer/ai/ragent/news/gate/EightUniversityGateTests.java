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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 八校确定性白名单门纯函数测试（#277）：八校中英/繁简可命中、英文 token
 * 边界（HKU 不子串命中 HKUST）、歧义短名需香港语境、单独 LU/泛词/空输入
 * 不准入。可穷举纯函数（票面：token 边界+歧义负例）。
 */
class EightUniversityGateTests {

    private final EightUniversityGate gate = EightUniversityGate.withDefaults();

    // ================== 八校命中面（中英全名/缩写/繁简） ==================

    @Test
    void englishFullNamesAndAbbreviationsMatch() {
        assertTrue(gate.mentions("HKU eyes Northern Metropolis expansion", null), "HKU 缩写");
        assertTrue(gate.mentions("CUHK announces new programme", null));
        assertTrue(gate.mentions("HKUST team wins award", null));
        assertTrue(gate.mentions("HKBU scholar publishes study", null));
        assertTrue(gate.mentions("EdUHK admission talk", null));
        assertTrue(gate.mentions("PolyU and industry sign MoU", null));
        assertTrue(gate.mentions("The University of Hong Kong said", null));
        assertTrue(gate.mentions("Chinese University of Hong Kong research", null));
        assertTrue(gate.mentions("Hong Kong University of Science and Technology opens lab", null));
    }

    @Test
    void chineseFullNamesMatchInTraditionalAndSimplified() {
        assertTrue(gate.mentions("香港大學公布新研究", null), "繁体全名");
        assertTrue(gate.mentions("香港大学公布新研究", null), "简体全名");
        assertTrue(gate.mentions("香港中文大學迎新", null));
        assertTrue(gate.mentions("香港科技大学团队突破", null));
        assertTrue(gate.mentions("香港浸會大學推出課程", null));
        assertTrue(gate.mentions("香港教育大学招生", null));
        assertTrue(gate.mentions("嶺南大學研究獲獎", null), "岭南繁体全名（无歧义组）");
    }

    @Test
    void hongKongSpecificShortNamesMatch() {
        assertTrue(gate.mentions("港大研究登上期刊", null));
        assertTrue(gate.mentions("理大學生奪獎", null));
        assertTrue(gate.mentions("浸大新課程", null));
        assertTrue(gate.mentions("教大畢業禮", null));
    }

    // ================== 英文 token 边界 ==================

    @Test
    void hkuDoesNotSubstringMatchHkust() {
        // HKU 词条以 token 边界匹配：HKUST 文本不含独立 HKU token——命中走 HKUST 自身词条
        assertTrue(gate.mentions("HKUST team wins", null), "HKUST 由自身词条命中");
        assertFalse(gate.mentions("HKUlyac building opens", null), "HKU 后跟字母=非 token，不得命中");
        assertFalse(gate.mentions("aHKU marker", null), "HKU 前跟字母=非 token");
        // 大小写不敏感
        assertTrue(gate.mentions("hku announces", null));
        assertTrue(gate.mentions("polyu signs mou", null));
    }

    // ================== 歧义短名：需香港高校语境 ==================

    @Test
    void ambiguousShortNamesRequireHongKongContext() {
        // CityU：境外同名大学（如 City University of Seattle / Malaysia）不得命中
        assertFalse(gate.mentions("CityU of Seattle expands campus", null));
        assertTrue(gate.mentions("CityU in Hong Kong expands campus", null));
        assertTrue(gate.mentions("Hong Kong's CityU expands", null));
        // Lingnan：大陆岭南（广州/中山）与澳门同名不得命中
        assertFalse(gate.mentions("Lingnan University Guangzhou opens", null));
        assertTrue(gate.mentions("Lingnan University in Hong Kong opens", null));
        // 中文歧义简称：中大/科大/城大需「香港」语境（防中山大学/中科大/其它城市大学）
        assertFalse(gate.mentions("中大（广州）新校区", null), "无香港语境的中大=中山大学嫌疑");
        assertTrue(gate.mentions("香港中大明年扩招", null));
        assertFalse(gate.mentions("中科大少年班", null), "科大无香港语境不命中（且中科大含科大子串——语境缺失拦下）");
        assertTrue(gate.mentions("科大香港校园开放日", null));
        assertFalse(gate.mentions("城市大学（东莞）揭牌", null));
        assertTrue(gate.mentions("香港城市大学揭牌", null), "带香港前缀的全名走无歧义组");
    }

    // ================== 负例：泛词/空输入/公司名 ==================

    @Test
    void genericWordsAndEmptyInputNeverMatch() {
        assertFalse(gate.mentions("Hong Kong universities raise fees", null),
                "泛指 universities 不命中（须具体实体）");
        assertFalse(gate.mentions("Education bureau announces policy", null));
        assertFalse(gate.mentions("大学教育资助委员会开会", null), "泛词「大学」不命中");
        assertFalse(gate.mentions("Lu Wei appointed", null), "单独 LU 不在词表");
        assertFalse(gate.mentions(null, null));
        assertFalse(gate.mentions("", ""));
        assertFalse(gate.mentions("   ", null));
    }

    @Test
    void companyNameLookalikesDoNotMatch() {
        assertFalse(gate.mentions("Lingnan Express Logistics IPO", null), "公司名 Lingnan 无港语境");
        assertFalse(gate.mentions("Cityunion Bank results", null), "CityUnion 前缀不命中 CityU（token 边界）");
        assertFalse(gate.mentions("Polyurethane market grows", null), "Polyurethane 不命中 PolyU（token 边界）");
    }

    // ================== 原始摘要作为第二证据面 ==================

    @Test
    void rawSummaryIsEvidenceWhenTitleMisses() {
        // 票面：短标题缺校名、正文提校名的报道可命中（标题+原摘要双证据面）
        assertFalse(gate.mentions("Northern Metropolis expansion plan", null));
        assertTrue(gate.mentions("Northern Metropolis expansion plan",
                "The University of Hong Kong (HKU) is looking to secure more laboratory space."));
        assertTrue(gate.mentions(null, "香港科技大學研究團隊宣布突破"));
    }
}
