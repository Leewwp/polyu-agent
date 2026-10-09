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

package com.nageoffer.ai.ragent.rag.core.prompt;

/**
 * 回答语言约束的取值与 KB 合成侧规则文本
 * <p>
 * 语言在入口判定一次后以 "zh"/"en" 字符串贯穿 Agent 与 rag 两模块，null 表示本轮不约束
 * （判不出语言时回落人设的「跟随用户语言」），跨模块契约就是这两个字面值
 */
public final class AnswerLanguageRules {

    public static final String ZH = "zh";
    public static final String EN = "en";

    private AnswerLanguageRules() {
    }

    /**
     * KB 合成系统提示的追加段：在模板选择完成后由 {@link RAGPromptService} 统一拼接，
     * 意图自定义模板与已解析槽位模板都绕不开；取值非法时返回 null 不追加
     */
    public static String kbSynthesisRule(String answerLanguage) {
        if (ZH.equals(answerLanguage)) {
            return "回答语言：最终答案必须以简体中文撰写；即使检索证据以英文为主，也输出简体中文。"
                    + "事实、数值、日期、资格条件与链接不得因语言转换改变；"
                    + "专有名词、机构名、课程代码、URL 与代码可保留原文。";
        }
        if (EN.equals(answerLanguage)) {
            return "Answer language: the final answer must be written entirely in English; "
                    + "even when the retrieved evidence is mostly Chinese, answer in English. "
                    + "Facts, numbers, dates, eligibility conditions and links must not change across the language conversion; "
                    + "proper nouns, organization names, course codes, URLs and code may stay in their original form.";
        }
        return null;
    }
}
