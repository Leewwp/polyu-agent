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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 种子里提示词槽的口径守卫，加「生产 KB_ANSWER 纪律条目 → 迁入位置」对照表的逐条核对
 * <p>
 * 知识库作答形态由 {@code rag.search.knowledge-answer-form} 分流，默认 synthesized：工具内用 KB_ANSWER 把命中资料
 * 合成一段成品答案再交回主 Agent；取 evidence 时交回资料原文，由主 Agent 读后作答。两种形态对提示词的要求不同，
 * 所以种子里分成两处。内置助手（各智能体空槽的回落终点）的两槽是合成口径：工具声明说返回的是成品答案，人设保留
 * 「成品内容原样全文输出」的规则，两槽各按 md5 钉住；它若留着资料原文口径的工具声明，全新空库在默认形态下就是
 * 「声明说返回的不是成品答案、实际返回的是成品答案」。资料原文形态的提示词稿另成一行：不内置、不激活，不参与回落。
 * <p>
 * 资料原文形态下 Agent 链不再读 KB_ANSWER，回答纪律要迁到主 Agent 的提示里。对照表资源
 * {@code agent-prompt-seed/discipline-mapping.json} 是评审过的快照（每条的落点片段、标为迁不进去的行、
 * 两份源文的行号归属）；本测试不替人判断迁得对不对，只保证种子不与它漂移：每条的落点片段在稿的指定槽里逐字出现，
 * 迁不进去的行点名的特征句不在稿里，两份源文的每个非空行都有归属。
 */
class AgentPromptSeedCaliberTest {

    /** 内置助手（默认助手）的智能体 id，见 init_data_pg.sql */
    private static final String BUILTIN_AGENT_ID = "2001523723396309001";

    /**
     * 生产现生效的「知识库问答」工具声明的 md5（激活体没有自有槽，回落到内置助手这一行）：361 字、1023 字节，
     * 取自 2026-11-24 对生产库的一次只读核对。种子里的这一行与它逐字节相同，全新空库的关态提示词面才等于线上的
     */
    private static final String PROD_EFFECTIVE_TOOL_DESCRIPTION_MD5 = "cf73f8a24b6611b862df6cdb0d7fbe09";

    /**
     * 内置助手 AGENT_MAIN 人设的 md5：1872 字、5292 字节，与引入作答形态开关之前（4b63db50）种子里的同一行逐字节相同。
     * 整篇钉住而不只查标记句：标记句还在时规则本体照样可能被改，例如「原样全文输出……」那一句被删，两个标记句都不受影响
     */
    private static final String BUILTIN_ASSISTANT_PERSONA_MD5 = "260862c3cb2f351e3f5e974a9dbf7651";

    /** 资料原文形态的提示词稿所在的智能体行：非内置、不激活，不参与任何回落 */
    private static final String DRAFT_AGENT_ID = "2126005175029207041";

    private static final String DRAFT_AGENT_NAME = "PolyU Guide（资料原文形态提示词稿）";

    private static final String MAPPING_RESOURCE = "/agent-prompt-seed/discipline-mapping.json";

    private static final Pattern PROMPT_ROW = Pattern.compile(
            "VALUES \\('(\\d+)', '(\\d+)', '([A-Z_]+)', \\$prompt\\$(.*?)\\$prompt\\$,", Pattern.DOTALL);

    private static final Pattern PROFILE_ROW = Pattern.compile(
            "INSERT INTO t_agent_profile \\([^)]*\\)\\s*VALUES \\('(\\d+)', '([^']*)', '[^']*', '[^']*', (\\d), (\\d),",
            Pattern.DOTALL);

    private static final Set<String> STATUSES = Set.of("migrated", "rejected", "illustration");

    /** t_agent_prompt 的一行 */
    private record PromptRow(String rowId, String agentId, String slot, String content) {
    }

    /** t_agent_profile 的一行：builtin 与 active 的取值 */
    private record ProfileRow(String id, String name, int builtin, int active) {
    }

    @Test
    @DisplayName("内置助手两槽是合成口径：工具声明说返回的是合成的完整答案，人设保留成品内容原样全文输出的规则")
    void builtinAssistantFollowsTheDefaultSynthesizedForm() throws IOException {
        PromptRow toolRow = promptRowsOf(BUILTIN_AGENT_ID).get(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name());
        assertNotNull(toolRow, "内置助手缺少 KNOWLEDGE_TOOL_DESCRIPTION");
        assertTrue(toolRow.content().contains("合成的完整答案"), "合成形态下工具声明要说明返回的是合成的完整答案");
        assertFalse(toolRow.content().contains("返回的不是成品答案"), "资料原文口径的工具声明不该出现在默认形态下的内置助手上");
        assertEquals(PROD_EFFECTIVE_TOOL_DESCRIPTION_MD5, md5(toolRow.content()), "内置助手的工具声明应与生产现生效的那一份逐字节相同");

        PromptRow personaRow = promptRowsOf(BUILTIN_AGENT_ID).get(AgentPromptSlot.AGENT_MAIN.name());
        assertNotNull(personaRow, "内置助手缺少 AGENT_MAIN");
        assertTrue(personaRow.content().contains("工具描述声明其返回是面向用户的成品内容时"),
                "合成形态下人设要保留「成品内容原样全文输出」这一条");
        assertFalse(personaRow.content().contains("读资料作答"), "资料原文形态的人设（主 Agent 自己读资料作答）不该出现在默认形态下的内置助手上");
        assertEquals(BUILTIN_ASSISTANT_PERSONA_MD5, md5(personaRow.content()), "内置助手的人设应与引入作答形态开关之前的那一份逐字节相同");
    }

    @Test
    @DisplayName("资料原文形态的稿单独成一行：非内置、不激活，带人设与工具声明两槽，带证据规则八条")
    void evidenceFormDraftSitsOnItsOwnInactiveRow() throws IOException {
        ProfileRow draft = profileRows().get(DRAFT_AGENT_ID);
        assertNotNull(draft, "种子缺少提示词稿的智能体行 " + DRAFT_AGENT_ID);
        assertEquals(DRAFT_AGENT_NAME, draft.name());
        assertEquals(0, draft.builtin(), "稿不能是内置智能体，否则会成为所有空槽位的回落终点");
        assertEquals(0, draft.active(), "稿不能激活，否则默认形态下的解析结果就变了");

        Map<String, PromptRow> rows = promptRowsOf(DRAFT_AGENT_ID);
        assertEquals(Set.of(AgentPromptSlot.AGENT_MAIN.name(), AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name()),
                rows.keySet(), "稿只落主 Agent 人设与知识库工具声明两槽，其余槽照旧回落");

        JsonNode mapping = mapping();
        assertEquals(DRAFT_AGENT_ID, mapping.get("draftAgentId").asText());
        mapping.get("draftSlotRowIds").fields().forEachRemaining(entry ->
                assertEquals(entry.getValue().asText(), rows.get(entry.getKey()).rowId(), "稿的槽位行 id: " + entry.getKey()));

        String persona = rows.get(AgentPromptSlot.AGENT_MAIN.name()).content();
        assertTrue(persona.contains("# 资料边界") && persona.contains("# 证据规则"), "人设要带资料边界与证据规则两节");
        for (int rule = 1; rule <= 8; rule++) {
            assertTrue(persona.contains("\n" + rule + ". "), "证据规则第 " + rule + " 条缺失（按源文的编号 1 至 8）");
        }
        String toolDescription = rows.get(AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION.name()).content();
        assertTrue(toolDescription.startsWith("检索本助手配置的知识库，返回命中的资料原文"), "工具声明要写明返回的是资料原文");
        assertTrue(toolDescription.contains("读资料作答："), "工具声明要带读资料作答的规则");
        assertFalse(toolDescription.contains("用户问具体依据哪份资料时可以说出文档名"),
                "生产两份 KB_ANSWER 都带不报文档名规则，稿里不能留底稿这一句");

        for (PromptRow row : promptRows()) {
            if (!DRAFT_AGENT_ID.equals(row.agentId())) {
                assertFalse(row.content().contains("读资料作答"),
                        "资料原文口径的槽只能落在稿这一行，不能落在智能体 " + row.agentId() + " 的 " + row.slot());
            }
        }
    }

    @Test
    @DisplayName("对照表里每一条已迁入的纪律条目，落点片段都在稿的指定槽里逐字出现")
    void everyMigratedEntryIsVerbatimInItsSlot() throws IOException {
        Map<String, String> draft = draftSlots();
        JsonNode entries = mapping().get("entries");
        int migrated = 0;
        for (JsonNode entry : entries) {
            if (!"migrated".equals(entry.get("status").asText())) {
                continue;
            }
            migrated++;
            String id = entry.get("id").asText();
            String slot = entry.get("slot").asText();
            assertNotNull(draft.get(slot), id + " 指向了稿里没有的槽 " + slot);
            JsonNode fragments = entry.get("fragments");
            assertTrue(fragments.isArray() && !fragments.isEmpty(), id + " 已迁入却没有落点片段");
            for (JsonNode fragment : fragments) {
                assertTrue(draft.get(slot).contains(fragment.asText()), id + " 的落点片段不在 " + slot + " 里: " + fragment.asText());
            }
            for (JsonNode also : entry.get("also")) {
                assertTrue(draft.get(also.get(0).asText()).contains(also.get(1).asText()),
                        id + " 的另一处落点不在 " + also.get(0).asText() + " 里: " + also.get(1).asText());
            }
        }
        assertTrue(migrated >= 70, "已迁入的行数异常地少: " + migrated);
    }

    @Test
    @DisplayName("标为迁不进去的行点名的特征句不在稿里；生产现生效那一份的纪律条目没有迁不进去的行")
    void unmigratableEntriesStayOutOfTheDraft() throws IOException {
        String draft = String.join("\n", draftSlots().values());
        Set<String> ids = new HashSet<>();
        for (JsonNode entry : mapping().get("entries")) {
            String id = entry.get("id").asText();
            assertTrue(ids.add(id), "对照表里有重复的行 id: " + id);
            String status = entry.get("status").asText();
            assertTrue(STATUSES.contains(status), id + " 的处置未知: " + status);
            if ("rejected".equals(status)) {
                assertFalse("P".equals(entry.get("source").asText()),
                        "生产现生效的那一份（PolyU Guide 自有 KB_ANSWER）不容许有迁不进去的行: " + id);
            }
            for (JsonNode phrase : entry.get("mustNot")) {
                assertFalse(draft.contains(phrase.asText()), id + " 标为不迁，稿里却出现了: " + phrase.asText());
            }
        }
    }

    @Test
    @DisplayName("对照表覆盖两份 KB_ANSWER 的每个非空行：每行要么属于某一条目，要么是标题、分隔线或结构示例")
    void mappingCoversEveryLineOfBothSources() throws IOException {
        JsonNode mapping = mapping();
        Map<String, List<int[]>> rangesBySource = new LinkedHashMap<>();
        Map<String, String> idByRange = new LinkedHashMap<>();
        Set<String> sourcesWithIllustration = new HashSet<>();
        for (JsonNode entry : mapping.get("entries")) {
            String source = entry.get("source").asText();
            int from = entry.get("lines").get(0).asInt();
            int to = entry.get("lines").get(1).asInt();
            assertTrue(1 <= from && from <= to, entry.get("id").asText() + " 的行号范围不对: " + from + "-" + to);
            int[] range = {from, to};
            rangesBySource.computeIfAbsent(source, ignored -> new ArrayList<>()).add(range);
            idByRange.put(source + "/" + from + "-" + to, entry.get("id").asText());
            if ("illustration".equals(entry.get("status").asText())) {
                sourcesWithIllustration.add(source);
            }
        }
        // 同一行拆成几条（行尾 a、b 的小写字母）是允许的；两条不相干的条目登记同一行不允许
        for (Map.Entry<String, List<int[]>> bySource : rangesBySource.entrySet()) {
            List<int[]> ranges = bySource.getValue();
            for (int i = 0; i < ranges.size(); i++) {
                for (int j = i + 1; j < ranges.size(); j++) {
                    boolean overlap = ranges.get(i)[0] <= ranges.get(j)[1] && ranges.get(j)[0] <= ranges.get(i)[1];
                    if (overlap) {
                        String a = stem(idByRange.get(bySource.getKey() + "/" + ranges.get(i)[0] + "-" + ranges.get(i)[1]));
                        String b = stem(idByRange.get(bySource.getKey() + "/" + ranges.get(j)[0] + "-" + ranges.get(j)[1]));
                        assertTrue(a.startsWith(b) || b.startsWith(a),
                                bySource.getKey() + " 第 " + ranges.get(i)[0] + " 行被两条不相干的条目登记: " + a + "、"
                                        + b);
                    }
                }
            }
        }

        for (String source : List.of("P", "B")) {
            JsonNode spec = mapping.get("sources").get(source);
            PromptRow kbAnswer = promptRowsOf(spec.get("agentId").asText()).get(spec.get("slot").asText());
            assertNotNull(kbAnswer, source + " 指向的 KB_ANSWER 行不在种子里");
            String[] lines = kbAnswer.content().split("\n", -1);

            Set<Integer> accounted = new HashSet<>();
            for (JsonNode line : spec.get("structuralLines")) {
                assertTrue(line.asInt() >= 1 && line.asInt() <= lines.length, source + " 的结构行号越界: " + line);
                assertTrue(accounted.add(line.asInt()), source + " 的结构行重复登记: " + line);
            }
            for (int[] range : rangesBySource.getOrDefault(source, List.of())) {
                assertTrue(range[1] <= lines.length, source + " 的条目行号超出了源文的行数 " + lines.length);
                for (int line = range[0]; line <= range[1]; line++) {
                    accounted.add(line);
                }
            }
            List<Integer> uncovered = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].isBlank() && !accounted.contains(i + 1)) {
                    uncovered.add(i + 1);
                }
            }
            assertTrue(uncovered.isEmpty(), source + " 有非空行不属于任何条目，行号: " + uncovered);
            if ("B".equals(source)) {
                assertTrue(sourcesWithIllustration.contains(source), "内置那份里的示例段没有登记");
            }
        }
    }

    /** 条目 id 去掉行尾那个表示拆分的小写字母：B0-2a 与 B0-2b 同出 B0-2 */
    private static String stem(String id) {
        return id.replaceAll("[a-z]$", "");
    }

    /** 某个智能体自己的全部提示词槽：slot_key → 行 */
    private static Map<String, PromptRow> promptRowsOf(String agentId) throws IOException {
        Map<String, PromptRow> rows = new LinkedHashMap<>();
        for (PromptRow row : promptRows()) {
            if (agentId.equals(row.agentId())) {
                assertNull(rows.put(row.slot(), row), "同一智能体的同一槽位出现了两行: " + row.slot());
            }
        }
        return rows;
    }

    private static List<PromptRow> promptRows() throws IOException {
        List<PromptRow> rows = new ArrayList<>();
        Matcher matcher = PROMPT_ROW.matcher(seedText());
        while (matcher.find()) {
            rows.add(new PromptRow(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4)));
        }
        assertTrue(rows.size() >= 10, "种子里的提示词槽行数异常地少: " + rows.size());
        return rows;
    }

    private static Map<String, ProfileRow> profileRows() throws IOException {
        Map<String, ProfileRow> rows = new LinkedHashMap<>();
        Matcher matcher = PROFILE_ROW.matcher(seedText());
        while (matcher.find()) {
            rows.put(matcher.group(1), new ProfileRow(matcher.group(1), matcher.group(2),
                    Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4))));
        }
        assertTrue(rows.size() >= 3, "种子里的智能体行数异常地少: " + rows.size());
        return rows;
    }

    /** 稿的两槽：slot_key → 正文 */
    private static Map<String, String> draftSlots() throws IOException {
        Map<String, String> slots = new LinkedHashMap<>();
        promptRowsOf(DRAFT_AGENT_ID).forEach((slot, row) -> slots.put(slot, row.content()));
        return slots;
    }

    private static JsonNode mapping() throws IOException {
        try (InputStream in = AgentPromptSeedCaliberTest.class.getResourceAsStream(MAPPING_RESOURCE)) {
            assertNotNull(in, "测试资源缺失: " + MAPPING_RESOURCE);
            return new ObjectMapper().readTree(in);
        }
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
