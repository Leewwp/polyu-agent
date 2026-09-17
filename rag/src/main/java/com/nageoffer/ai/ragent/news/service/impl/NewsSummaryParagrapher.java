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

package com.nageoffer.ai.ragent.news.service.impl;

/**
 * 摘要分段兜底（T22）：LLM 输出未分段（不含换行）时按固定规则补分段，
 * 与存量重排 SQL（260917_news_summary_paragraphing.sql）同一条规则——
 * 首句独立成导语段 + 其余每 2–3 句一段（均衡分组），段间插入空行。
 *
 * <p>只插入 {@code \n\n} 分隔、不改动任何文字：对已含换行的输入原样返回
 * （提示词已放开换行，模型自带分段时以模型为准）；单句/两句文本不强制分段。
 * 切片式实现保证 {@code reflowed.replace("\n\n", "")} 与原文逐字节相同。
 */
final class NewsSummaryParagrapher {

    private static final String PARAGRAPH_SEP = "\n\n";

    private NewsSummaryParagrapher() {
    }

    /**
     * 对未分段文本按句界插入段间空行；已含换行或句子不足以成段时原样返回
     */
    static String reflow(String text) {
        if (text == null || text.contains("\n")) {
            return text;
        }
        int[] starts = sentenceStarts(text);
        if (starts.length < 3) {
            return text;
        }
        int body = starts.length - 1;
        int[] sizes = partitionSizes(body);
        StringBuilder out = new StringBuilder(text.length() + sizes.length * 2);
        out.append(text, 0, starts[1]);
        int cursor = 1;
        for (int size : sizes) {
            int last = cursor + size - 1;
            int end = last + 1 < starts.length ? starts[last + 1] : text.length();
            out.append(PARAGRAPH_SEP).append(text, starts[cursor], end);
            cursor += size;
        }
        String reflowed = out.toString();
        assert reflowed.replace(PARAGRAPH_SEP, "").equals(text) : "分段必须逐字节可逆";
        return reflowed;
    }

    /**
     * s 句正文 → 每段句数（均衡、常态每段 2–3 句；s≤3 单段）
     */
    private static int[] partitionSizes(int s) {
        if (s <= 3) {
            return new int[]{s};
        }
        int paragraphs = (s + 2) / 3;
        int base = s / paragraphs;
        int rem = s % paragraphs;
        int[] sizes = new int[paragraphs];
        for (int i = 0; i < paragraphs; i++) {
            sizes[i] = base + (i < rem ? 1 : 0);
        }
        return sizes;
    }

    /**
     * 句起始下标（含 0）。中文按全角句末标点（。！？，可带收尾引号/括号）；
     * 英文按句点/叹号/问号后接空白与大写字母、数字或引号开头的边界。
     * 切点仅影响分段位置，不影响任何字节，误切（如缩写）无内容风险
     */
    private static int[] sentenceStarts(String text) {
        int length = text.length();
        int[] tmp = new int[length / 2 + 2];
        int count = 0;
        tmp[count++] = 0;
        boolean cjk = text.codePoints().anyMatch(cp -> cp >= 0x4E00 && cp <= 0x9FFF);
        for (int i = 0; i < length; i++) {
            char ch = text.charAt(i);
            if (cjk) {
                if (isCjkSentenceEnd(ch)) {
                    int j = i + 1;
                    while (j < length && isCjkTail(text.charAt(j))) {
                        j++;
                    }
                    if (j < length) {
                        tmp[count++] = j;
                    }
                    i = j - 1;
                }
            } else if (isAsciiSentenceEnd(ch)) {
                int j = i + 1;
                while (j < length && (text.charAt(j) == ' ' || text.charAt(j) == '\t')) {
                    j++;
                }
                if (j < length && startsSentence(text.charAt(j))) {
                    tmp[count++] = j;
                    i = j - 1;
                }
            }
        }
        int[] starts = new int[count];
        System.arraycopy(tmp, 0, starts, 0, count);
        return starts;
    }

    private static boolean isCjkSentenceEnd(char ch) {
        return ch == '。' || ch == '！' || ch == '？';
    }

    private static boolean isCjkTail(char ch) {
        return ch == '”' || ch == '」' || ch == '』' || ch == '"' || ch == '\'' || ch == '）' || ch == ')';
    }

    private static boolean isAsciiSentenceEnd(char ch) {
        return ch == '.' || ch == '!' || ch == '?';
    }

    private static boolean startsSentence(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '"' || ch == '\'' || ch == '“' || ch == '(';
    }
}
