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

package com.nageoffer.ai.ragent.rag.service.preguard;

import com.nageoffer.ai.ragent.rag.service.preguard.QuestionScopeRules.Decision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则层确定性判定：高精度拒答、默认放行。例句全部自编中性形态，
 * 覆盖「机构半边 + 校园语境半边」放行、「开放域直答形态」拒答与不确定带
 */
class QuestionScopeRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "香港理工大学的学费是多少？",
            "理大图书馆周末开放吗",
            "How do I get to PolyU by MTR?",
            "香港城市大学和理大哪个宿舍更好",
            "Is HKUST easier to get into than PolyU?"
    })
    void 机构半边命中即放行(String question) {
        assertThat(QuestionScopeRules.evaluate(question).decision()).isEqualTo(Decision.ALLOW);
        assertThat(QuestionScopeRules.evaluate(question).reasonFamily()).isEqualTo("institution");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "学费什么时候截止缴纳",
            "How do I apply for a student visa?",
            "签证续签要准备什么材料",
            "Add-Drop 期可以退课吗",
            "宿舍什么时候可以申请",
            "How do I book a study room in the library?",
            "校园卡丢了怎么补办",
            "怎么预约体育馆的羽毛球场",
            "我的学生签证还需要等多久",
            "退课会影响奖学金吗",
            "期末考试的时间表在哪里查",
            "How many credits do I need to graduate?",
            "毕业典礼需要报名吗",
            "我的成绩单怎么申请",
            // dev 集实测补族的校园语境形态（#430 段二词表微调）
            "How much is the first instalment of the hall fee for a Double Room?",
            "Under what circumstances would my Hall Caution Money be forfeited?",
            "非本地新生接受录取时要先交多少留位费？",
            "内地新生抵港后多久之内要办中联办报到注册？",
            "When can non-local new students apply for hall residence?",
            "本科生一次能借几本书？能借多久？",
            "How many books can an undergraduate student borrow?",
            "学校现在有什么健康活动或福利可以参加？",
            "有特殊需要要怎么向学校申报？",
            "特殊需要学生在校内能用到哪些资源？",
            "非本地学习活动有没有资助？最多能拿多少？"
    })
    void 校园语境半边命中即放行_无机构显式词(String question) {
        assertThat(QuestionScopeRules.evaluate(question).decision()).isEqualTo(Decision.ALLOW);
        assertThat(QuestionScopeRules.evaluate(question).reasonFamily()).isEqualTo("campus-context");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "你好", "hello!", "Hello", "谢谢你", "thanks!", "THANK YOU",
            "早上好呀", "good morning", "再见啦", "bye!", "晚安", "嗨"
    })
    void 寒暄直接放行_不进拒答路径(String question) {
        assertThat(QuestionScopeRules.evaluate(question).decision()).isEqualTo(Decision.ALLOW);
        assertThat(QuestionScopeRules.evaluate(question).reasonFamily()).isEqualTo("chitchat");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 寒暄只是引子、后半句是正题：不能当寒暄放行，也不能当寒暄拒答
            "你好，请问图书馆几点开门",
            "Hi! How do I apply for a hostel?"
    })
    void 寒暄带正题的长句按正题判定(String question) {
        assertThat(QuestionScopeRules.evaluate(question).decision()).isEqualTo(Decision.ALLOW);
        assertThat(QuestionScopeRules.evaluate(question).reasonFamily()).isEqualTo("campus-context");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "《围城》的作者是谁",
            "Who is the author of that classic novel about the Great Gatsby?",
            "法国的首都是哪座城市",
            "What is the capital of Australia?",
            "世界上最高的山是哪一座",
            "Which is the longest river in the world?",
            "日本的人口有多少",
            "Who was the president of the United States in 2008?",
            "相对论是谁提出来的",
            "那幅星空名画是谁画的"
    })
    void 开放域直答形态且无机构校园信号_规则层拒答(String question) {
        assertThat(QuestionScopeRules.evaluate(question).decision()).isEqualTo(Decision.REJECT);
        assertThat(QuestionScopeRules.evaluate(question).reasonFamily()).isEqualTo("open-domain-rule");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // 机构信号优先于开放域形态：问的是理大校长，是校园域内问题
            "理大的校长是谁",
            "Who is the president of PolyU?",
            // 校园语境优先于开放域形态：作业/课程语境的归属问句
            "这门课的期末作业是谁出的",
            "Who wrote the course outline for COMP101?"
    })
    void 机构或校园信号压过开放域形态(String question) {
        assertThat(QuestionScopeRules.evaluate(question).decision()).isEqualTo(Decision.ALLOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "怎么做番茄炒蛋",
            "How do I fix a flat tire on a bicycle?",
            "帮我看看这段代码为什么报错",
            "Can you recommend a good movie for the weekend?",
            "今天香港天气怎么样",
            "What is the best way to learn cooking?"
    })
    void 无任何信号形态_落入不确定带(String question) {
        assertThat(QuestionScopeRules.evaluate(question).decision()).isEqualTo(Decision.UNCERTAIN);
        assertThat(QuestionScopeRules.evaluate(question).reasonFamily()).isEqualTo("uncertain");
    }

    @Test
    void 空白问题放行_交由后续链路校验() {
        assertThat(QuestionScopeRules.evaluate(null).decision()).isEqualTo(Decision.ALLOW);
        assertThat(QuestionScopeRules.evaluate("   ").decision()).isEqualTo(Decision.ALLOW);
        assertThat(QuestionScopeRules.evaluate("").reasonFamily()).isEqualTo("blank");
    }

    @Test
    void 英文缩写按词边界命中_不子串误伤() {
        // PolyU 是词条、polyurethane 不是机构信号，也不含其它信号：落不确定带而非机构放行
        assertThat(QuestionScopeRules.evaluate("What is polyurethane made of?").decision())
                .isEqualTo(Decision.UNCERTAIN);
    }

    @Test
    void 校园语境词在词边界内命中() {
        assertThat(QuestionScopeRules.evaluate("How do I book a badminton court at the gym?").decision())
                .isEqualTo(Decision.ALLOW);
    }
}
