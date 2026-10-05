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

package com.nageoffer.ai.ragent.agent.memory;

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryExtractionDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmCall;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmField;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryExtractionStatus;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryApprovalService.ApprovalTransition;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryApprovalService.PlanCardProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 审批编排的裁决面：批准/拒绝/过期/失效入口与确认卡投影（#278）
 */
class AgentMemoryApprovalServiceTest {

    private static final String USER_ID = "u-3001";
    private static final String OTHER_USER = "u-3002";
    private static final String PLAN_ID = "e-7001";

    private AgentMemoryRepository memoryRepository;
    private AgentMemoryExtractionMapper extractionMapper;
    private AgentMessageMapper messageMapper;
    private AgentMemoryApprovalService service;

    @BeforeEach
    void setUp() {
        memoryRepository = mock(AgentMemoryRepository.class);
        extractionMapper = mock(AgentMemoryExtractionMapper.class);
        messageMapper = mock(AgentMessageMapper.class);
        service = new AgentMemoryApprovalService(memoryRepository, extractionMapper, messageMapper);
    }

    @Test
    void shouldApprovePendingPlanWithBinding() {
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(row(AgentMemoryExtractionStatus.PENDING_APPROVAL));
        when(memoryRepository.approvePlan(PLAN_ID, USER_ID, "call-1", "m-4004")).thenReturn(true);

        ApprovalTransition transition = service.approve(PLAN_ID, USER_ID, "call-1", "m-4004");

        assertThat(transition.outcome()).isEqualTo(ApprovalTransition.Outcome.APPROVED);
        verify(memoryRepository).approvePlan(PLAN_ID, USER_ID, "call-1", "m-4004");
    }

    /**
     * 条件更新落空且行还在待审态：唯一解释是过期，提示重新发起
     */
    @Test
    void shouldRefuseApprovalWhenPlanExpired() {
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(row(AgentMemoryExtractionStatus.PENDING_APPROVAL));
        when(memoryRepository.approvePlan(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        ApprovalTransition transition = service.approve(PLAN_ID, USER_ID, "call-1", "m-4004");

        assertThat(transition.outcome()).isEqualTo(ApprovalTransition.Outcome.NOT_CONFIRMABLE);
        assertThat(transition.reason()).contains("30 分钟");
    }

    @Test
    void shouldRefuseApprovalWhenPlanRejected() {
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(row(AgentMemoryExtractionStatus.REJECTED));
        when(memoryRepository.approvePlan(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        ApprovalTransition transition = service.approve(PLAN_ID, USER_ID, "call-1", "m-4004");

        assertThat(transition.outcome()).isEqualTo(ApprovalTransition.Outcome.NOT_CONFIRMABLE);
        assertThat(transition.reason()).contains("取消");
    }

    /**
     * 他人计划/不存在：不写任何状态，直接不可确认
     */
    @Test
    void shouldRefuseForeignOrMissingPlan() {
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(
                AgentMemoryExtractionDO.builder().id(PLAN_ID).userId(OTHER_USER)
                        .status(AgentMemoryExtractionStatus.PENDING_APPROVAL.name()).build());

        ApprovalTransition transition = service.approve(PLAN_ID, USER_ID, "call-1", "m-4004");

        assertThat(transition.outcome()).isEqualTo(ApprovalTransition.Outcome.NOT_CONFIRMABLE);
        verify(memoryRepository, never()).approvePlan(anyString(), anyString(), anyString(), anyString());
    }

    /**
     * 已执行的计划再点同意：不重复提交，续跑后由执行工具回放原结果
     */
    @Test
    void shouldReportAlreadyAppliedWithoutRebinding() {
        AgentMemoryExtractionDO applied = row(AgentMemoryExtractionStatus.APPLIED);
        applied.setPlanResultJson(AgentMemoryPlanExecution.resultJsonOf("已清空 2 条", 2, true, 2));
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(applied);

        ApprovalTransition transition = service.approve(PLAN_ID, USER_ID, "call-1", "m-4004");

        assertThat(transition.outcome()).isEqualTo(ApprovalTransition.Outcome.ALREADY_APPLIED);
        verify(memoryRepository, never()).approvePlan(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldRejectPendingPlan() {
        when(memoryRepository.rejectPlan(PLAN_ID, USER_ID)).thenReturn(true);

        service.reject(PLAN_ID, USER_ID);

        verify(memoryRepository).rejectPlan(PLAN_ID, USER_ID);
    }

    /**
     * 到期死卡定位·真实卡片形状：编号在服务端注入的 arguments JSON（折叠区）里、fields 是中文标签主视图——
     * 必须按生产卡形状断言，fields 带编号的合成卡曾经掩盖过这条链路不通的缺陷
     */
    @Test
    void shouldLocateDeadMemoryCardsByArgumentsJson() {
        AgentMessageDO memoryCard = awaitingMessage("m-5001", memoryConfirmCard(PLAN_ID));
        AgentMessageDO mcpCard = awaitingMessage("m-5002", mcpConfirmCard());
        when(messageMapper.selectList(any())).thenReturn(List.of(memoryCard, mcpCard));
        // 行已终局（含后台结算路径先走一步的情况——定位只看行状态，与谁先结算无关）
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(row(AgentMemoryExtractionStatus.EXPIRED));

        List<String> dead = service.settleExpiredAndLocateDeadCards(USER_ID, "c-31");

        assertThat(dead).containsExactly("m-5001");
        verify(memoryRepository).expireStalePlans(USER_ID);
    }

    /**
     * 计划仍可确认（待审/已批未执行）：同形状的卡不判死，会话继续等用户
     */
    @Test
    void shouldKeepCardsWhosePlanStillConfirmable() {
        when(messageMapper.selectList(any())).thenReturn(
                List.of(awaitingMessage("m-5001", memoryConfirmCard(PLAN_ID))));
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(row(AgentMemoryExtractionStatus.PENDING_APPROVAL));

        List<String> dead = service.settleExpiredAndLocateDeadCards(USER_ID, "c-31");

        assertThat(dead).isEmpty();
    }

    /**
     * 老形状兜底：编号写在 fields 里的卡（历史遗留/手工构造）也能定位
     */
    @Test
    void shouldLocateDeadCardsForLegacyFieldsShape() {
        when(messageMapper.selectList(any())).thenReturn(
                List.of(awaitingMessage("m-5003", legacyFieldsMemoryCard(PLAN_ID))));
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(row(AgentMemoryExtractionStatus.INVALIDATED));

        List<String> dead = service.settleExpiredAndLocateDeadCards(USER_ID, "c-31");

        assertThat(dead).containsExactly("m-5003");
    }

    /**
     * 会话里没有记忆卡（纯 MCP 卡）：不做任何台账行查询，结算照常先走
     */
    @Test
    void shouldSkipCardLocationWhenNoMemoryCards() {
        when(messageMapper.selectList(any())).thenReturn(
                List.of(awaitingMessage("m-5002", mcpConfirmCard())));

        List<String> dead = service.settleExpiredAndLocateDeadCards(USER_ID, "c-31");

        assertThat(dead).isEmpty();
        verify(memoryRepository).expireStalePlans(USER_ID);
        verify(extractionMapper, never()).selectById(anyString());
    }

    /**
     * 卡片投影：服务端计划渲染成 fields 与折叠区 JSON，编号只在折叠区原文出现
     */
    @Test
    void shouldRenderPlanIntoCardFields() {
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(planRow());
        when(messageMapper.selectList(any())).thenReturn(List.of());

        PlanCardProjection projection = service.describeForCard(PLAN_ID, USER_ID);

        assertThat(projection).isNotNull();
        List<String> labels = projection.fields().stream().map(AgentConfirmField::getLabel).toList();
        assertThat(labels).contains("变更内容", "撤回条目", "联合新增", "覆盖范围", "生效方式");
        assertThat(fieldOf(projection, "变更内容")).contains("清空全部长期记忆");
        assertThat(fieldOf(projection, "撤回条目")).contains("用户穿 L 码");
        assertThat(fieldOf(projection, "联合新增")).contains("新事实");
        assertThat(fieldOf(projection, "生效方式")).doesNotContain("不可逆");
        assertThat(projection.arguments()).contains(PLAN_ID);
    }

    /**
     * 他人计划不可投影：卡片退化为编号展示，执行侧也会拒绝
     */
    @Test
    void shouldNotProjectForeignPlan() {
        AgentMemoryExtractionDO foreign = planRow();
        foreign.setUserId(OTHER_USER);
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(foreign);

        assertThat(service.describeForCard(PLAN_ID, USER_ID)).isNull();
    }

    /**
     * 终局行不可投影（过期/拒绝/失效/已执行）：不再渲染成可确认的样子，卡片退化为编号展示
     */
    @Test
    void shouldNotProjectTerminalPlansIntoCard() {
        for (AgentMemoryExtractionStatus status : List.of(AgentMemoryExtractionStatus.EXPIRED,
                AgentMemoryExtractionStatus.REJECTED, AgentMemoryExtractionStatus.INVALIDATED,
                AgentMemoryExtractionStatus.APPLIED)) {
            AgentMemoryExtractionDO terminal = planRow();
            terminal.setStatus(status.name());
            when(extractionMapper.selectById(PLAN_ID)).thenReturn(terminal);

            assertThat(service.describeForCard(PLAN_ID, USER_ID))
                    .as("状态 %s 不应再投影成确认卡", status)
                    .isNull();
        }
    }

    private static String fieldOf(PlanCardProjection projection, String label) {
        return projection.fields().stream()
                .filter(field -> label.equals(field.getLabel()))
                .findFirst().map(AgentConfirmField::getValue).orElse("");
    }

    private AgentMemoryExtractionDO row(AgentMemoryExtractionStatus status) {
        return AgentMemoryExtractionDO.builder()
                .id(PLAN_ID).userId(USER_ID).status(status.name()).build();
    }

    private AgentMemoryExtractionDO planRow() {
        AgentMemoryPlan plan = new AgentMemoryPlan(PLAN_ID, USER_ID, "c-31", "m-1", "m-2",
                2, 3, 7L, "1900000000000000005", "FLUSH",
                List.of(AgentMemoryDecision.clear(),
                        AgentMemoryDecision.retract("m-8"),
                        AgentMemoryDecision.add("新事实")),
                java.util.Map.of("m-8", "用户穿 L 码"));
        return AgentMemoryExtractionDO.builder()
                .id(PLAN_ID).userId(USER_ID)
                .status(AgentMemoryExtractionStatus.PENDING_APPROVAL.name())
                .planJson(plan.toJson()).build();
    }

    private AgentMessageDO awaitingMessage(String id, AgentBlock card) {
        AgentMessageDO message = new AgentMessageDO();
        message.setId(id);
        message.setUserId(USER_ID);
        message.setConversationId("c-31");
        message.setMessageStatus("AWAITING_CONFIRM");
        message.setBlocks(List.of(card));
        return message;
    }

    /**
     * 生产形状的记忆确认卡：fields=describeForCard 的中文标签主视图（不带编号），
     * arguments=服务端注入的折叠区 JSON（编号只在这里）
     */
    private AgentBlock memoryConfirmCard(String operationId) {
        return AgentBlock.builder()
                .kind(AgentBlock.KIND_CONFIRM)
                .status("pending")
                .calls(List.of(AgentConfirmCall.builder()
                        .toolCallId("call-" + operationId)
                        .name(com.nageoffer.ai.ragent.agent.tool.MemoryApplyTool.TOOL_NAME)
                        .fields(List.of(
                                AgentConfirmField.builder().name("变更内容").label("变更内容")
                                        .value("清空全部长期记忆（现有 3 条）").build(),
                                AgentConfirmField.builder().name("生效方式").label("生效方式")
                                        .value("确认后从生效长期记忆中移除；取消则不执行").build()))
                        .arguments(cn.hutool.json.JSONUtil.toJsonPrettyStr(java.util.Map.of(
                                "operationId", operationId,
                                "triggerType", "FLUSH")))
                        .build()))
                .build();
    }

    /**
     * 老形状兜底用：编号写在 fields 里、无 arguments 的合成卡
     */
    private AgentBlock legacyFieldsMemoryCard(String operationId) {
        return AgentBlock.builder()
                .kind(AgentBlock.KIND_CONFIRM)
                .status("pending")
                .calls(List.of(AgentConfirmCall.builder()
                        .toolCallId("call-" + operationId)
                        .name(com.nageoffer.ai.ragent.agent.tool.MemoryApplyTool.TOOL_NAME)
                        .fields(List.of(AgentConfirmField.builder()
                                .name("operationId").label("计划编号").value(operationId).build()))
                        .build()))
                .build();
    }

    private AgentBlock mcpConfirmCard() {
        return AgentBlock.builder()
                .kind(AgentBlock.KIND_CONFIRM)
                .status("pending")
                .calls(List.of(AgentConfirmCall.builder()
                        .toolCallId("call-mcp")
                        .name("submit_leave")
                        .fields(List.of(AgentConfirmField.builder()
                                .name("leave_type").label("假期类型").value("annual").build()))
                        .build()))
                .build();
    }
}
