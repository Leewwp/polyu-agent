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

package com.nageoffer.ai.ragent.rag.service.impl;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nageoffer.ai.ragent.audit.support.BizChangeLogContext;
import com.nageoffer.ai.ragent.rag.config.OrchestrationProperties;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.controller.vo.AgentPromptConfigVO;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptCacheManager;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptResolver;
import com.nageoffer.ai.ragent.rag.core.prompt.AgentPromptSlot;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentProfileDO;
import com.nageoffer.ai.ragent.rag.dao.entity.AgentPromptDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentProfileMapper;
import com.nageoffer.ai.ragent.rag.dao.mapper.AgentPromptMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentProfileAdminServiceImplTest {

    private static final String SYNTHESIZED_TOOL_HINT =
            "模型靠它判断要不要查知识库，此时还看不到检索结果；写清这个库覆盖哪类问题，不必在这里规定回答风格";

    private final AgentProfileMapper profileMapper = mock(AgentProfileMapper.class);
    private final AgentPromptCacheManager cacheManager = mock(AgentPromptCacheManager.class);
    private final AgentPromptResolver promptResolver = mock(AgentPromptResolver.class);
    private final OrchestrationProperties orchestration = new OrchestrationProperties();
    private final SearchChannelProperties search = new SearchChannelProperties();
    private final AgentProfileAdminServiceImpl service = new AgentProfileAdminServiceImpl(profileMapper,
            mock(AgentPromptMapper.class), promptResolver, cacheManager,
            orchestration, mock(BizChangeLogContext.class), search);

    @BeforeAll
    static void initLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentProfileDO.class);
        // 列表页按槽位聚合时对提示词表做列投影，Lambda 列缓存要先建起来
        TableInfoHelper.initTableInfo(assistant, AgentPromptDO.class);
    }

    /**
     * 激活只写 active 一列：读出的整行若原样写回，会把并发刚保存的名称、描述改回旧值
     */
    @Test
    void activateWritesOnlyActiveFlag() {
        when(profileMapper.selectById("agent-b")).thenReturn(AgentProfileDO.builder()
                .id("agent-b").name("读出时的旧名称").description("读出时的旧描述").builtin(0).active(0).build());

        service.activate("agent-b");

        ArgumentCaptor<AgentProfileDO> written = ArgumentCaptor.forClass(AgentProfileDO.class);
        verify(profileMapper).updateById(written.capture());
        assertThat(written.getValue()).usingRecursiveComparison()
                .isEqualTo(AgentProfileDO.builder().id("agent-b").active(1).build());
        verify(cacheManager).clearCache();
    }

    /**
     * 合成形态（默认）下 Agent 的知识库工具在内部读 KB_ANSWER：控制台不得把它列为不生效，
     * 工具声明的编辑提醒也按「交回成品答案」写
     */
    @Test
    void listsKbAnswerAsEffectiveUnderAgentWhileSynthesized() {
        underAgent("旧的合成要求", SearchChannelProperties.KnowledgeAnswerForm.SYNTHESIZED);

        AgentPromptConfigVO config = service.loadPrompts("agent-b");

        assertThat(config.getSlots()).hasSize(AgentPromptSlot.values().length);
        AgentPromptConfigVO.Slot kbAnswer = slot(config, AgentPromptSlot.KB_ANSWER);
        assertThat(kbAnswer.getEffective()).isTrue();
        assertThat(kbAnswer.getInactiveReason()).isNull();
        assertThat(kbAnswer.getContent()).isEqualTo("旧的合成要求");
        assertThat(kbAnswer.getGroup())
                .as("两种架构都读，仍归「通用」，与引入开关之前相同")
                .isEqualTo("COMMON");
        AgentPromptConfigVO.Slot tool = slot(config, AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION);
        assertThat(tool.getEditorHint()).isEqualTo(SYNTHESIZED_TOOL_HINT);
    }

    /**
     * 资料原文形态下 Agent 的知识库工具不写答案：KB_ANSWER 列出并标为不生效，原因指明改哪两个槽位，已填内容照常可见
     */
    @Test
    void listsKbAnswerAsInactiveUnderAgentOnceEvidence() {
        underAgent("旧的合成要求", SearchChannelProperties.KnowledgeAnswerForm.EVIDENCE);

        AgentPromptConfigVO config = service.loadPrompts("agent-b");

        AgentPromptConfigVO.Slot kbAnswer = slot(config, AgentPromptSlot.KB_ANSWER);
        assertThat(kbAnswer.getEffective()).isFalse();
        assertThat(kbAnswer.getInactiveReason()).contains("「知识库问答」", "「Agent人设」");
        assertThat(kbAnswer.getContent()).isEqualTo("旧的合成要求");
        assertThat(kbAnswer.getGroup()).isEqualTo("WORKFLOW");
        AgentPromptConfigVO.Slot tool = slot(config, AgentPromptSlot.KNOWLEDGE_TOOL_DESCRIPTION);
        assertThat(tool.getEditorHint()).contains("工具返回资料原文");
    }

    /**
     * 列表页的覆盖率分母与上同口径：Agent 档位下合成形态把 KB_ANSWER 算进去，资料原文形态不算；WorkFlow 档位两种形态相同
     */
    @Test
    void coverageDenominatorFollowsTheAnswerForm() {
        orchestration.setType("agent");

        search.setKnowledgeAnswerForm(SearchChannelProperties.KnowledgeAnswerForm.SYNTHESIZED);
        assertThat(service.list().getEffectiveSlotTotal()).isEqualTo(7);

        search.setKnowledgeAnswerForm(SearchChannelProperties.KnowledgeAnswerForm.EVIDENCE);
        assertThat(service.list().getEffectiveSlotTotal()).isEqualTo(6);

        orchestration.setType("workflow");
        assertThat(service.list().getEffectiveSlotTotal()).isEqualTo(4);
    }

    private void underAgent(String ownKbAnswer, SearchChannelProperties.KnowledgeAnswerForm answerForm) {
        when(profileMapper.selectById("agent-b"))
                .thenReturn(AgentProfileDO.builder().id("agent-b").builtin(0).build());
        when(promptResolver.loadOwnPrompts("agent-b"))
                .thenReturn(Map.of(AgentPromptSlot.KB_ANSWER.name(), ownKbAnswer));
        orchestration.setType("agent");
        search.setKnowledgeAnswerForm(answerForm);
    }

    private static AgentPromptConfigVO.Slot slot(AgentPromptConfigVO config, AgentPromptSlot slot) {
        return config.getSlots().stream()
                .filter(each -> slot.name().equals(each.getSlotKey()))
                .findFirst().orElseThrow();
    }
}
