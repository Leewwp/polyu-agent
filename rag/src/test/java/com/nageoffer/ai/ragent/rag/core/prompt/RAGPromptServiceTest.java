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

import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RAGPromptServiceTest {

    /**
     * 基础模板已迁入 DB 由管理员可编辑，故此处只桩一段带标记的假模板
     * 断言落在本类的职责（选模板、清理、追加引用规则）上，不再断言模板正文内容
     */
    private static final String STUB_BASE_TEMPLATE = "# 桩基础模板\n\n正文占位";

    private static RAGPromptService service(boolean citationEnabled) {
        RAGConfigProperties properties = new RAGConfigProperties();
        properties.setCitationEnabled(citationEnabled);

        AgentPromptResolver resolver = mock(AgentPromptResolver.class);
        when(resolver.resolve(any())).thenReturn(STUB_BASE_TEMPLATE);

        return new RAGPromptService(new PromptTemplateLoader(new DefaultResourceLoader()), resolver, properties);
    }

    private static PromptContext kbContext() {
        return PromptContext.builder()
                .kbContext("<content ref=\"1\">资料</content>")
                .kbIntents(List.of())
                .eligibleIntentIds(Set.of())
                .build();
    }

    @Test
    void includesCitationRulesFromKnowledgePrompt() {
        String result = service(true).buildSystemPrompt(kbContext());

        assertTrue(result.contains("# 行内引用规则"));
        assertTrue(result.contains("[N](#cite-N)"));
        // 引用规则追加在基础模板之后
        assertTrue(result.indexOf("# 桩基础模板") < result.indexOf("# 行内引用规则"));

        // 引用只落在正文单元末尾，标题一律不带引用，示例本身也不能出现带引用的标题
        assertTrue(result.contains("标题与小标题一律不加引用"));
        assertTrue(result.replace("\r\n", "\n")
                .contains("## 二级标题\n\n第一部分包含要点 A 和要点 B。[1](#cite-1)"));
        assertFalse(result.contains("## 二级标题 ["));

        // 同一单元依据多份资料时连写编号
        assertTrue(result.contains("`[1](#cite-1)[3](#cite-3)`"));
        assertTrue(result.contains("由两份资料共同支撑。[1](#cite-1)[3](#cite-3)"));

        // 出处只允许数字角标：文档名与内部标签的禁令由基础模板统一声明，引用规则只声明自己是它的唯一例外
        assertTrue(result.contains("本章只豁免一件事：出处改以数字角标呈现"));
        assertTrue(result.contains("不报文档名、不出现输入侧的标签与属性字样、不罗列来源清单"));
    }

    @Test
    void omitsCitationRulesWhenCitationDisabled() {
        String result = service(false).buildSystemPrompt(kbContext());

        assertFalse(result.contains("# 行内引用规则"));
        assertFalse(result.contains("#cite-"), "关闭引用时不得向模型提及角标格式");
        assertFalse(result.contains("ref=\""), "关闭引用时上下文不注入编号，提示词也不应描述该属性");
    }

    @Test
    void usesResolvedTemplateAsBase() {
        // 无自定义意图模板时，基础模板整段取自 AgentPromptResolver
        for (boolean citationEnabled : new boolean[]{true, false}) {
            String result = service(citationEnabled).buildSystemPrompt(kbContext());
            assertTrue(result.startsWith(STUB_BASE_TEMPLATE));
        }
    }

    @Test
    void appendsOnlyCitationRulesToCustomIntentTemplate() {
        PromptContext context = PromptContext.builder()
                .kbContext("<content>资料</content>")
                .kbIntents(List.of(intentWithTemplate("# 自定义意图模板")))
                .eligibleIntentIds(Set.of("intent-1"))
                .build();

        String result = service(true).buildSystemPrompt(context);

        assertTrue(result.contains("# 自定义意图模板"));
        assertFalse(result.contains("# 桩基础模板"), "意图模板整份替换基础模板");
        assertTrue(result.contains("# 行内引用规则"));
        assertTrue(result.indexOf("# 自定义意图模板") < result.indexOf("# 行内引用规则"));
    }

    @Test
    void usesSingleCandidateTemplateForGlobalEvidence() {
        PromptContext context = PromptContext.builder()
                .kbContext("<content>全局资料</content>")
                .kbIntents(List.of(intentWithTemplate("intent-1", "# 单意图模板")))
                .eligibleIntentIds(Set.of("intent-1"))
                .build();

        String result = service(false).buildSystemPrompt(context);

        assertTrue(result.startsWith("# 单意图模板"));
        assertFalse(result.contains(STUB_BASE_TEMPLATE));
    }

    /**
     * 语言约束在所有模板选择之后统一追加：意图自定义模板也绕不开，且位置在最后压制模板残留的固定语言要求
     */
    @Test
    void appendsAnswerLanguageRuleAfterEveryTemplatePath() {
        PromptContext intentTemplateContext = PromptContext.builder()
                .kbContext("<content>资料</content>")
                .kbIntents(List.of(intentWithTemplate("intent-1", "# 意图模板（固定要求简体中文）")))
                .eligibleIntentIds(Set.of("intent-1"))
                .build();

        for (PromptContext context : new PromptContext[]{kbContext(), intentTemplateContext}) {
            String en = service(false).buildStructuredMessages(
                    context, List.of(), "question", List.of(), false, "en").get(0).getContent();
            assertTrue(en.contains("written entirely in English"), "英文约束必须追加");
            assertTrue(en.indexOf("written entirely in English") > en.indexOf("# 意图模板")
                    || en.indexOf("written entirely in English") > en.indexOf("# 桩基础模板"),
                    "约束位于模板正文之后");

            String zh = service(false).buildStructuredMessages(
                    context, List.of(), "问题", List.of(), false, "zh").get(0).getContent();
            assertTrue(zh.contains("最终答案必须以简体中文撰写"), "中文约束必须追加");
            assertTrue(zh.indexOf("最终答案必须以简体中文撰写") > zh.indexOf("# 意图模板")
                    || zh.indexOf("最终答案必须以简体中文撰写") > zh.indexOf("# 桩基础模板"),
                    "约束位于模板正文之后");
        }
    }

    /**
     * 未判定语言（null）不追加任何约束段落，行为与改动前的默认路径一致
     */
    @Test
    void omitsAnswerLanguageRuleWhenUndetermined() {
        String result = service(false).buildStructuredMessages(
                kbContext(), List.of(), "问题", List.of(), false, null).get(0).getContent();

        assertFalse(result.contains("written entirely in English"));
        assertFalse(result.contains("最终答案必须以简体中文撰写"));
    }

    @Test
    void directedMissUsesDefaultTemplate() {
        PromptContext context = PromptContext.builder()
                .kbContext("<content>补充资料</content>")
                .kbIntents(List.of(intentWithTemplate("intent-1", "# 未命中模板")))
                .eligibleIntentIds(Set.of())
                .build();

        String result = service(false).buildSystemPrompt(context);

        assertTrue(result.startsWith(STUB_BASE_TEMPLATE));
        assertFalse(result.contains("# 未命中模板"));
    }

    @Test
    void usesOnlyEligibleIntentTemplateAmongCandidates() {
        PromptContext context = PromptContext.builder()
                .kbContext("<content>意图资料</content>")
                .kbIntents(List.of(
                        intentWithTemplate("intent-1", "# 未命中模板"),
                        intentWithTemplate("intent-2", "# 命中模板")))
                .eligibleIntentIds(Set.of("intent-2"))
                .build();

        String result = service(false).buildSystemPrompt(context);

        assertTrue(result.startsWith("# 命中模板"));
        assertFalse(result.contains("# 未命中模板"));
    }

    @Test
    void usesDefaultTemplateForMultipleCandidatesWithGlobalEvidence() {
        PromptContext context = PromptContext.builder()
                .kbContext("<content>全局资料</content>")
                .kbIntents(List.of(
                        intentWithTemplate("intent-1", "# 候选模板一"),
                        intentWithTemplate("intent-2", "# 候选模板二")))
                .eligibleIntentIds(Set.of("intent-1", "intent-2"))
                .build();

        String result = service(false).buildSystemPrompt(context);

        assertTrue(result.startsWith(STUB_BASE_TEMPLATE));
        assertFalse(result.contains("# 候选模板一"));
        assertFalse(result.contains("# 候选模板二"));
    }

    @Test
    void usesTemplateWhenDuplicateCandidatesShareEligibleId() {
        PromptContext context = PromptContext.builder()
                .kbContext("<content>意图资料</content>")
                .kbIntents(List.of(
                        intentWithTemplate("intent-1", "# 单意图模板"),
                        intentWithTemplate("intent-1", "# 单意图模板")))
                .eligibleIntentIds(Set.of("intent-1"))
                .build();

        String result = service(false).buildSystemPrompt(context);

        assertTrue(result.startsWith("# 单意图模板"));
    }

    /**
     * #332 A1 as-of 时钟基准：所有 KB 合成路径的 system prompt 都注入 HKT 当前日期行，
     * 位置紧随槽模板（含意图自定义模板替换场景），先于引用规则。
     */
    @Test
    void injectsHktCurrentDateFactAfterTemplateBeforeRules() {
        for (boolean citationEnabled : new boolean[]{true, false}) {
            String result = service(citationEnabled).buildSystemPrompt(kbContext());
            assertTrue(result.contains("当前日期（香港时间）："), "system prompt 必须注入 as-of 时钟基准");
            assertTrue(result.indexOf(STUB_BASE_TEMPLATE) < result.indexOf("当前日期（香港时间）"),
                    "日期行紧随槽模板之后");
        }
        String withCitation = service(true).buildSystemPrompt(kbContext());
        assertTrue(withCitation.indexOf("当前日期（香港时间）") < withCitation.indexOf("# 行内引用规则"),
                "日期行先于引用规则追加段");
        PromptContext context = PromptContext.builder()
                .kbContext("<content>资料</content>")
                .kbIntents(List.of(intentWithTemplate("# 自定义意图模板")))
                .eligibleIntentIds(Set.of("intent-1"))
                .build();
        assertTrue(service(true).buildSystemPrompt(context).contains("当前日期（香港时间）："),
                "意图模板整份替换基础模板时 as-of 基准不得丢失");
    }

    private static NodeScore intentWithTemplate(String template) {
        return intentWithTemplate("intent-1", template);
    }

    private static NodeScore intentWithTemplate(String id, String template) {
        IntentNode node = IntentNode.builder()
                .id(id)
                .promptTemplate(template)
                .build();
        return NodeScore.builder().node(node).build();
    }
}
