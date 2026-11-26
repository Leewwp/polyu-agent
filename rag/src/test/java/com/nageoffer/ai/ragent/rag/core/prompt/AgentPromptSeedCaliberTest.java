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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子里提示词槽的口径守卫：全新空库按种子初始化后，内置助手的提示词槽与默认作答形态（合成）一致
 * <p>
 * 上游 idx 27 让知识库工具无条件改交资料原文，并把内置助手的工具声明与人设改成了资料原文口径。
 * 本仓以开关收编（rag.search.knowledge-answer-form，默认 synthesized）：合成形态下工具内仍用 KB_ANSWER 合成成品答案。
 * 种子若留着资料原文口径的工具声明，全新空库在默认形态下就是「声明说返回的不是成品答案、实际返回的是成品答案」，
 * 所以内置助手两槽保持合成口径，资料原文形态的提示词稿另成一行。
 */
class AgentPromptSeedCaliberTest {

    /** 内置助手（默认助手）的智能体 id，见 init_data_pg.sql */
    private static final String BUILTIN_AGENT_ID = "2001523723396309001";

    /**
     * 生产现生效的「知识库问答」工具声明的 md5（激活体没有自有槽，回落到内置助手这一行）：361 字、1023 字节，
     * 取自 2026-11-24 对生产库的一次只读核对。种子里的这一行与它逐字节相同，全新空库的关态提示词面才等于线上的
     */
    private static final String PROD_EFFECTIVE_TOOL_DESCRIPTION_MD5 = "cf73f8a24b6611b862df6cdb0d7fbe09";

    private static final Pattern PROMPT_ROW = Pattern.compile(
            "VALUES \\('(\\d+)', '(\\d+)', '([A-Z_]+)', \\$prompt\\$(.*?)\\$prompt\\$,", Pattern.DOTALL);

    @Test
    @DisplayName("内置助手两槽是合成口径：工具声明说返回的是合成的完整答案，人设保留成品内容原样全文输出的规则")
    void builtinAssistantFollowsTheDefaultSynthesizedForm() throws IOException {
        Map<String, String> builtin = promptRowsOf(BUILTIN_AGENT_ID);

        String toolDescription = builtin.get(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name());
        assertNotNull(toolDescription, "内置助手缺少 KNOWLEDGE_TOOL_DESCRIPTION");
        assertTrue(toolDescription.contains("合成的完整答案"), "合成形态下工具声明要说明返回的是合成的完整答案");
        assertFalse(toolDescription.contains("返回的不是成品答案"), "资料原文口径的工具声明不该出现在默认形态下的内置助手上");
        assertEquals(PROD_EFFECTIVE_TOOL_DESCRIPTION_MD5, md5(toolDescription), "内置助手的工具声明应与生产现生效的那一份逐字节相同");

        String persona = builtin.get(AgentPromptSlot.AGENT_MAIN.name());
        assertNotNull(persona, "内置助手缺少 AGENT_MAIN");
        assertTrue(persona.contains("工具描述声明其返回是面向用户的成品内容时"),
                "合成形态下人设要保留「成品内容原样全文输出」这一条");
        assertFalse(persona.contains("读资料作答"), "资料原文形态的人设（主 Agent 自己读资料作答）不该出现在默认形态下的内置助手上");
    }

    /** 某个智能体自己的全部提示词槽：slot_key → 正文 */
    private static Map<String, String> promptRowsOf(String agentId) throws IOException {
        Map<String, String> rows = new HashMap<>();
        Matcher matcher = PROMPT_ROW.matcher(seedText());
        while (matcher.find()) {
            if (agentId.equals(matcher.group(2))) {
                assertNull(rows.put(matcher.group(3), matcher.group(4)), "同一智能体的同一槽位出现了两行: " + matcher.group(3));
            }
        }
        return rows;
    }

    /** mvn 从模块目录跑测试，仓库根在上一级；从仓库根直跑（个别 IDE）时两级路径都试 */
    private static String seedText() throws IOException {
        List<Path> candidates = List.of(
                Path.of("..", "resources", "database", "init_data_pg.sql"),
                Path.of("resources", "database", "init_data_pg.sql"));
        Path seed = candidates.stream().filter(Files::exists).findFirst()
                .orElseThrow(() -> new IllegalStateException("找不到 resources/database/init_data_pg.sql，候选=" + candidates));
        return Files.readString(seed, StandardCharsets.UTF_8);
    }

    private static String md5(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
