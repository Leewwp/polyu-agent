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

package com.nageoffer.ai.ragent.agent.language;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerLanguagesTest {

    @Test
    void detectsPlainEnglishAndChineseQuestions() {
        assertThat(AnswerLanguages.detect("Can I study in the library overnight?")).isEqualTo("en");
        assertThat(AnswerLanguages.detect("What campus resources are available for students with special needs?"))
                .isEqualTo("en");
        assertThat(AnswerLanguages.detect("图书馆几点开门")).isEqualTo("zh");
        assertThat(AnswerLanguages.detect("我可以用 Canva 提交作业吗")).isEqualTo("zh");
    }

    /**
     * 混语边界：英文问句夹中文专名不翻成中文，中文问句夹英文机构名不翻成英文
     */
    @Test
    void keepsMajorityLanguageOnMixedProperNouns() {
        assertThat(AnswerLanguages.detect("How do I book 研讨室?")).isEqualTo("en");
        assertThat(AnswerLanguages.detect("请问 Library 的开放时间是几点？")).isEqualTo("zh");
        assertThat(AnswerLanguages.detect("PolyU 图书馆几点开门")).isEqualTo("zh");
    }

    /**
     * 显式指令优先于主要语言判定：中英两种请求形态都要接住
     */
    @Test
    void explicitRequestBeatsMajorityHeuristic() {
        assertThat(AnswerLanguages.detect("Please answer in English")).isEqualTo("en");
        assertThat(AnswerLanguages.detect("Could you translate this to English?")).isEqualTo("en");
        assertThat(AnswerLanguages.detect("用英文回答：图书馆开放时间")).isEqualTo("en");
        assertThat(AnswerLanguages.detect("请用中文回答 my question")).isEqualTo("zh");
        assertThat(AnswerLanguages.detect("把这些内容翻译成中文 please")).isEqualTo("zh");
    }

    /**
     * 纯编号、表情、空串判不出语言：返回 null 让调用侧回落人设默认，不硬塞约束
     */
    @Test
    void returnsNullWhenNoNaturalLanguage() {
        assertThat(AnswerLanguages.detect(null)).isNull();
        assertThat(AnswerLanguages.detect("   ")).isNull();
        assertThat(AnswerLanguages.detect("12345")).isNull();
        assertThat(AnswerLanguages.detect("？？？")).isNull();
    }

    @Test
    void turnDirectiveCoversBothLanguagesAndRejectsUnknownValues() {
        assertThat(AnswerLanguages.turnDirective("zh")).contains("简体中文").contains("原样全文输出");
        assertThat(AnswerLanguages.turnDirective("en")).contains("English").contains("verbatim");
        assertThat(AnswerLanguages.turnDirective(null)).isNull();
        assertThat(AnswerLanguages.turnDirective("fr")).isNull();
    }
}
