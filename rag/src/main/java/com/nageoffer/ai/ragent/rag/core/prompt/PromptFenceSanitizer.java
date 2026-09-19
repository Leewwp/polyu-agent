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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 伪标签围栏中和器（评审 M10/L16/M12）
 *
 * <p>检索 chunk 原文、MCP 工具返回体、历史摘要与用户问题都直接填进 prompt 的伪标签围栏
 * （{@code <content>/<rules>/<data>/<errors>/<question>/<conversation-summary>}），
 * 内容里出现同名闭合/伪造序列即可逃逸围栏改写回答行为。本工具把「可能破坏围栏结构的
 * 标签形序列」的左尖括号替换为 {@code &lt;}（模型可读、不再构成标签），
 * 正常文本与普通尖括号不受影响。
 */
public final class PromptFenceSanitizer {

    /**
     * 与 context-format.st 全部伪标签同名的开/闭标签形序列（大小写不敏感，词边界防误伤普通词）
     */
    private static final Pattern FENCE_BREAKERS = Pattern.compile(
            "(?i)</?(?:content|documents|rules|data|errors|question|conversation-summary|system)\\b");

    private PromptFenceSanitizer() {
    }

    /**
     * 中和围栏逃逸序列；空白入参原样返回（含 null 转空串）
     */
    public static String neutralize(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        Matcher matcher = FENCE_BREAKERS.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        matcher.reset();
        while (matcher.find()) {
            String seq = matcher.group();
            matcher.appendReplacement(sb, Matcher.quoteReplacement("&lt;" + seq.substring(1)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
