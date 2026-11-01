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

import com.nageoffer.ai.ragent.news.gate.EightUniversityGate;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 回答前分类器的确定性规则层（#430）：高精度拒答、默认放行
 *
 * <p>输入侧词表分两半边：</p>
 * <ul>
 *     <li><b>机构半边</b>：直接复用八校白名单（#277 域，沿用不扩），
 *     命中即视为校园域内问题放行</li>
 *     <li><b>校园语境半边</b>：学费/签证/选课退改/预约/宿舍/图书馆/校园卡等
 *     校园服务语境词族（中英）——相当比例的域内问题不含机构显式词，
 *     仅靠机构半边会把它们误推进不确定带</li>
 * </ul>
 * <p>拒答只由「开放域直答形态」触发，且必须同时无机构与校园语境信号；
 * 形态按泛化句式抽象（归属/首都/人口/世界之最等），不针对任何具体探针题定制。
 * 规则判不准的一律进不确定带，交 LLM 兜底判定。</p>
 */
public final class QuestionScopeRules {

    /**
     * 判定结果
     */
    public enum Decision {

        /**
         * 放行：机构/校园语境信号命中、寒暄或空白
         */
        ALLOW,

        /**
         * 拒答：开放域直答形态且无任何校园域信号
         */
        REJECT,

        /**
         * 不确定：无信号或形态判不准，交 LLM 兜底
         */
        UNCERTAIN
    }

    /**
     * 规则层判定 + 理由族（决策日志口径）
     */
    public record RuleVerdict(Decision decision, String reasonFamily) {

        static RuleVerdict allow(String family) {
            return new RuleVerdict(Decision.ALLOW, family);
        }

        static RuleVerdict of(Decision decision, String family) {
            return new RuleVerdict(decision, family);
        }
    }

    /**
     * 机构半边：八校白名单默认词表（#277 域沿用不扩，含英文词边界与歧义组语境约束）
     */
    private static final EightUniversityGate INSTITUTION = EightUniversityGate.withDefaults();

    /**
     * 校园语境半边：校园服务语境词族（中英）。纯 ASCII 词条按词边界匹配，含 CJK 词条按子串匹配。
     * 误放行（漏拒）优先于误拒，宽进不影响拒答精度
     */
    private static final List<String> CAMPUS_CONTEXT_TERMS = List.of(
            // 学费/奖助
            "tuition", "scholarship", "financial aid", "学费", "奖学金", "助学金", "缴费",
            // 签证/出入境
            "visa", "immigration", "签证",
            // 选课退改
            "add drop", "add-drop", "course registration", "withdrawal", "enrollment", "enrolment",
            "course", "选课", "退课", "退改", "改选", "补选", "课程", "科目", "选修", "必修", "退学", "休学",
            // 预约
            "booking", "reservation", "reserve", "appointment", "预约", "预订", "预定",
            // 宿舍
            "hostel", "dormitory", "accommodation", "hall fee", "hall residence", "caution money",
            "instalment", "宿舍", "住宿", "舍堂", "留位费", "宿位",
            // 非本地/内地生事务
            "non-local", "mainland student", "mainland students", "非本地", "内地生", "内地新生", "中联办", "报到",
            // 图书馆/自习
            "library", "learning commons", "study room", "borrow", "图书馆", "自习室", "研讨室",
            "借书", "借阅", "能借", "可借",
            // 校园卡/证件
            "campus card", "student card", "student id", "门禁", "校园卡", "学生证",
            // 考试/学业
            "exam", "midterm", "quiz", "gpa", "credits", "semester", "academic calendar",
            "transcript", "degree", "programme", "tutorial", "lecture", "thesis", "dissertation",
            "internship", "exchange", "考试", "期中", "期末", "测验", "绩点", "成绩", "学分",
            "学期", "校历", "成绩单", "学位", "专业", "作业", "导修", "实验", "论文", "实习", "交换",
            // 毕业
            "graduation", "convocation", "毕业", "毕业典礼", "毕业设计",
            // 校园设施/生活/福利
            "gym", "fitness centre", "fitness center", "badminton", "canteen", "cafeteria",
            "locker", "counseling", "career", "orientation", "wellness", "funding", "special needs",
            "体育馆", "健身房", "羽毛球", "食堂", "饭堂", "打印", "心理", "就业", "推荐信", "迎新", "学号",
            "健康活动", "福利", "资助", "特殊需要", "无障碍", "申报"
    );

    /**
     * 开放域直答形态：归属/首都/人口/世界之最等泛化句式（词条口径与八校门同款：
     * ASCII 词边界、单星有界无相邻双星回溯）
     */
    private static final List<Pattern> OPEN_DOMAIN_SHAPES = List.of(
            // 人/作品的归属问句：who is/was the author/president/... of ...
            Pattern.compile("(?i)\\bwho (?:is|was|are|were) the "
                    + "(?:author|writer|director|producer|painter|composer|inventor|discoverer|founder|"
                    + "president|prime minister|ceo|king|queen|emperor|pope|mayor|governor)\\b"),
            // 动词形态的归属问句：who wrote/invented/...
            Pattern.compile("(?i)\\bwho (?:wrote|directed|produced|painted|composed|invented|discovered|"
                    + "founded|created|designed|built)\\b"),
            // 地理/国情直答
            Pattern.compile("(?i)\\bcapital of\\b|\\b(?:population|area|currency) of\\b"),
            // 世界之最
            Pattern.compile("(?i)\\b(?:largest|longest|highest|deepest|tallest|biggest|smallest|fastest|"
                    + "oldest|hottest|coldest)\\b[^.!?]{0,48}\\bin the world\\b"),
            // 中文归属问句
            Pattern.compile("的作者是谁|作者是谁|是谁写的|谁写的|是谁发明|谁发明的|是谁发现|谁发现的|"
                    + "是谁提出|谁提出的|是谁画的|谁画的|是谁导演"),
            // 中文国情直答
            Pattern.compile("的首都|首都是|的人口|的(?:总统|首相|主席|国王|女王|皇帝)是谁"),
            // 中文世界之最
            Pattern.compile("世界(?:上|内)(?:最|第一)")
    );

    /**
     * 寒暄整句形态（zh/en）：全句匹配，长句里的问候语不当寒暄
     */
    private static final Pattern CHITCHAT_ZH = Pattern.compile(
            "^(?:你好|您好|嗨|哈喽|早上好|上午好|下午好|晚上好|早安|晚安|谢谢|谢谢你|多谢|多谢你|感谢|"
                    + "非常感谢|再见|拜拜|下次见)[呀啊啦哈~！!。.，,\\s]*$");
    private static final Pattern CHITCHAT_EN = Pattern.compile(
            "(?i)^(?:hi|hello|hey|good\\s(?:morning|afternoon|evening)|thanks|thank you|thx|ty|bye|"
                    + "goodbye|good night|see you)[!.,~\\s]*$");

    /**
     * 寒暄判定长度上限：超过即视为带正题的长句，不按寒暄归类
     */
    private static final int CHITCHAT_MAX_CHARS = 30;

    private static final List<Pattern> CAMPUS_CONTEXT = CAMPUS_CONTEXT_TERMS.stream()
            .map(QuestionScopeRules::termPattern)
            .toList();

    private QuestionScopeRules() {
    }

    /**
     * 确定性判定入口：机构/校园语境/寒暄放行 → 开放域形态拒答 → 其余不确定
     */
    public static RuleVerdict evaluate(String question) {
        if (question == null || question.isBlank()) {
            return RuleVerdict.allow("blank");
        }
        String trimmed = question.trim();
        if (isChitchat(trimmed)) {
            return RuleVerdict.allow("chitchat");
        }
        if (INSTITUTION.mentions(trimmed, null)) {
            return RuleVerdict.allow("institution");
        }
        if (matchesAny(CAMPUS_CONTEXT, trimmed)) {
            return RuleVerdict.allow("campus-context");
        }
        if (matchesAny(OPEN_DOMAIN_SHAPES, trimmed)) {
            return RuleVerdict.of(Decision.REJECT, "open-domain-rule");
        }
        return RuleVerdict.of(Decision.UNCERTAIN, "uncertain");
    }

    private static boolean isChitchat(String trimmed) {
        if (trimmed.length() > CHITCHAT_MAX_CHARS) {
            return false;
        }
        return CHITCHAT_ZH.matcher(trimmed).matches() || CHITCHAT_EN.matcher(trimmed).matches();
    }

    private static boolean matchesAny(List<Pattern> patterns, String text) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 词条 → 匹配模式：与八校门同款口径——纯 ASCII 词条按词边界+大小写不敏感，
     * 含 CJK 词条按原样子串
     */
    private static Pattern termPattern(String term) {
        String stripped = term.strip();
        if (stripped.matches("[A-Za-z][A-Za-z ]*[A-Za-z]|[A-Za-z]")) {
            return Pattern.compile("(?i)(?<![A-Za-z])" + Pattern.quote(stripped) + "(?![A-Za-z])");
        }
        return Pattern.compile("(?i)" + Pattern.quote(stripped));
    }
}
