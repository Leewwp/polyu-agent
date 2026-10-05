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

import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryControlDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryExtractionDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryControlMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryExtractionStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentMemorySourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划执行短事务：同一操作至多一次已提交变更（#278 的核心保证）
 */
class AgentMemoryPlanExecutionTest {

    private static final String USER_ID = "u-4001";
    private static final String PLAN_ID = "e-6001";
    private static final String OTHER_USER = "u-4002";
    private static final String TOOL_CALL_ID = "call-91";
    private static final long REVISION = 7L;
    private static final String WATERMARK = "1900000000000000003";

    private AgentMemoryMapper memoryMapper;
    private AgentMemoryExtractionMapper extractionMapper;
    private AgentMemoryControlMapper controlMapper;
    private AgentMemoryRepository repository;

    @BeforeEach
    void setUp() {
        memoryMapper = mock(AgentMemoryMapper.class);
        extractionMapper = mock(AgentMemoryExtractionMapper.class);
        controlMapper = mock(AgentMemoryControlMapper.class);
        repository = new AgentMemoryRepository(memoryMapper, extractionMapper, controlMapper,
                mock(AgentMessageMapper.class), new AgentMemoryProperties());

        AgentMemoryControlDO control = new AgentMemoryControlDO();
        control.setUserId(USER_ID);
        control.setRevision(REVISION);
        when(controlMapper.selectForUpdate(USER_ID)).thenReturn(control);
        when(extractionMapper.selectWatermark(USER_ID)).thenReturn(WATERMARK);
    }

    /**
     * 批准后的清空+新增计划：清空、新增、终态结算与版本号推进全部在一个事务内
     */
    @Test
    void shouldApplyApprovedClearPlanAtomically() {
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(approvedRow(
                planOf(List.of(AgentMemoryDecision.clear(), AgentMemoryDecision.add("新事实")))));
        when(memoryMapper.retractAll(USER_ID)).thenReturn(3);
        when(memoryMapper.insert(any(AgentMemoryDO.class))).thenReturn(1);
        when(extractionMapper.settlePlanApplied(eq(PLAN_ID), eq(USER_ID), anyString(), anyInt())).thenReturn(1);

        AgentMemoryPlanExecution execution = repository.executeApprovedPlan(PLAN_ID, USER_ID, TOOL_CALL_ID);

        assertThat(execution.executed()).isTrue();
        assertThat(execution.detail()).contains("清空", "3 条");
        verify(memoryMapper).retractAll(USER_ID);
        ArgumentCaptor<AgentMemoryDO> inserted = ArgumentCaptor.forClass(AgentMemoryDO.class);
        verify(memoryMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getContent()).isEqualTo("新事实");
        assertThat(inserted.getValue().getSourceType()).isEqualTo(AgentMemorySourceType.FLUSH.name());
        verify(controlMapper).bumpRevision(USER_ID);
        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        verify(extractionMapper).settlePlanApplied(eq(PLAN_ID), eq(USER_ID), resultJson.capture(), eq(2));
        assertThat(resultJson.getValue()).contains("summary");
    }

    /**
     * 撤回型计划：目标失活的决策整条丢弃，其余照批；全落空也有可回放结果
     */
    @Test
    void shouldDropDecisionsPointingAtMissingTargets() {
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(approvedRow(
                planOf(List.of(AgentMemoryDecision.retract("m-gone"), AgentMemoryDecision.retract("m-here")))));
        when(memoryMapper.selectList(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class)))
                .thenReturn(List.of(AgentMemoryDO.builder().id("m-here").userId(USER_ID)
                        .content("在场条目").sourceType("FLUSH").build()));
        when(memoryMapper.retract(USER_ID, "m-here")).thenReturn(1);
        when(extractionMapper.settlePlanApplied(eq(PLAN_ID), eq(USER_ID), anyString(), anyInt())).thenReturn(1);

        AgentMemoryPlanExecution execution = repository.executeApprovedPlan(PLAN_ID, USER_ID, TOOL_CALL_ID);

        assertThat(execution.executed()).isTrue();
        verify(memoryMapper, never()).retract(USER_ID, "m-gone");
        verify(memoryMapper).retract(USER_ID, "m-here");
        assertThat(execution.detail()).contains("生效 1 条");
    }

    /**
     * 重复执行读原结果：APPLIED 行直接回放，不碰记忆表
     */
    @Test
    void shouldReplayAppliedResultWithoutSecondCommit() {
        AgentMemoryExtractionDO applied = approvedRow(planOf(List.of(AgentMemoryDecision.clear())));
        applied.setStatus(AgentMemoryExtractionStatus.APPLIED.name());
        applied.setPlanResultJson(AgentMemoryPlanExecution.resultJsonOf("已清空 5 条", 5, true, 5));
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(applied);

        AgentMemoryPlanExecution execution = repository.executeApprovedPlan(PLAN_ID, USER_ID, TOOL_CALL_ID);

        assertThat(execution.outcome()).isEqualTo(AgentMemoryPlanExecution.Outcome.REPLAYED);
        assertThat(execution.detail()).isEqualTo("已清空 5 条");
        verify(memoryMapper, never()).retractAll(anyString());
        verify(memoryMapper, never()).insert(any(AgentMemoryDO.class));
    }

    /**
     * 未获批准（直通调用/伪造编号）：无论行在哪个非批准状态都拒绝执行——后台与绕过路径的执行半边
     */
    @Test
    void shouldRefuseUnapprovedPlan() {
        for (AgentMemoryExtractionStatus status : List.of(AgentMemoryExtractionStatus.PENDING_APPROVAL,
                AgentMemoryExtractionStatus.REJECTED, AgentMemoryExtractionStatus.EXPIRED,
                AgentMemoryExtractionStatus.PROCESSING)) {
            AgentMemoryExtractionDO row = approvedRow(planOf(List.of(AgentMemoryDecision.clear())));
            row.setStatus(status.name());
            when(extractionMapper.selectById(PLAN_ID)).thenReturn(row);

            AgentMemoryPlanExecution execution = repository.executeApprovedPlan(PLAN_ID, USER_ID, TOOL_CALL_ID);

            assertThat(execution.executed()).as("状态 %s 不许执行", status).isFalse();
        }
        verify(memoryMapper, never()).retractAll(anyString());
        verify(memoryMapper, never()).insert(any(AgentMemoryDO.class));
        verify(controlMapper, never()).bumpRevision(anyString());
    }

    /**
     * 他人计划：查得到也不执行
     */
    @Test
    void shouldRefuseForeignOperationId() {
        AgentMemoryExtractionDO foreign = approvedRow(planOf(List.of(AgentMemoryDecision.clear())));
        foreign.setUserId(OTHER_USER);
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(foreign);

        assertThat(repository.executeApprovedPlan(PLAN_ID, USER_ID, TOOL_CALL_ID).executed()).isFalse();
    }

    /**
     * 批准绑定的 toolCallId 与本次调用不符：拒绝且不动行状态（留证据）
     */
    @Test
    void shouldRefuseWhenToolCallBindingMismatch() {
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(approvedRow(
                planOf(List.of(AgentMemoryDecision.clear()))));

        AgentMemoryPlanExecution execution = repository.executeApprovedPlan(PLAN_ID, USER_ID, "call-forged");

        assertThat(execution.executed()).isFalse();
        assertThat(execution.detail()).contains("不匹配");
        verify(memoryMapper, never()).retractAll(anyString());
        verify(extractionMapper, never()).settlePlanApplied(anyString(), anyString(), anyString(), anyInt());
    }

    /**
     * 跨会话版本变化：批准套不到新条目上，计划失效并明示重新发起
     */
    @Test
    void shouldInvalidatePlanWhenRevisionMoved() {
        AgentMemoryControlDO moved = new AgentMemoryControlDO();
        moved.setUserId(USER_ID);
        moved.setRevision(REVISION + 1);
        when(controlMapper.selectForUpdate(USER_ID)).thenReturn(moved);
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(approvedRow(
                planOf(List.of(AgentMemoryDecision.clear()))));

        AgentMemoryPlanExecution execution = repository.executeApprovedPlan(PLAN_ID, USER_ID, TOOL_CALL_ID);

        assertThat(execution.executed()).isFalse();
        assertThat(execution.detail()).contains("失效");
        verify(extractionMapper).invalidatePlan(USER_ID, PLAN_ID, null);
        verify(memoryMapper, never()).retractAll(anyString());
    }

    @Test
    void shouldInvalidatePlanWhenWatermarkMoved() {
        when(extractionMapper.selectWatermark(USER_ID)).thenReturn("1900000000000000099");
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(approvedRow(
                planOf(List.of(AgentMemoryDecision.clear()))));

        assertThat(repository.executeApprovedPlan(PLAN_ID, USER_ID, TOOL_CALL_ID).executed()).isFalse();
        verify(extractionMapper).invalidatePlan(USER_ID, PLAN_ID, null);
    }

    /**
     * 终态结算被旁路（重复执行/过期竞争）：整个事务回滚，变更与结果同成败
     */
    @Test
    void shouldRollBackWhenTerminalSettleLoses() {
        when(extractionMapper.selectById(PLAN_ID)).thenReturn(approvedRow(
                planOf(List.of(AgentMemoryDecision.clear()))));
        when(memoryMapper.retractAll(USER_ID)).thenReturn(2);
        when(extractionMapper.settlePlanApplied(anyString(), anyString(), anyString(), anyInt())).thenReturn(0);

        assertThatThrownBy(() -> repository.executeApprovedPlan(PLAN_ID, USER_ID, TOOL_CALL_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("旁路结算");
        verify(controlMapper, never()).bumpRevision(anyString());
    }

    /**
     * 冻结落空（条件更新 0 行）：回退按失败结算，行不能挂在 PROCESSING 等僵尸回收
     */
    @Test
    void shouldSettleFailureWhenFreezeMisses() {
        AgentMemoryExtractionDO extraction = AgentMemoryExtractionDO.builder()
                .id("e-6009").userId(USER_ID)
                .status(AgentMemoryExtractionStatus.PROCESSING.name())
                .attemptCount(1).build();
        when(extractionMapper.freezePlan(anyString(), anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(0);

        assertThat(repository.freezePlan(extraction,
                planOf(List.of(AgentMemoryDecision.clear())), 30)).isFalse();

        verify(extractionMapper).settle(eq("e-6009"),
                eq(AgentMemoryExtractionStatus.CONFLICT.name()), eq(0), eq(1));
    }

    /**
     * 冻结成功：行迁入待审态，有效期交给库时钟
     */
    @Test
    void shouldFreezePlanWithExpiry() {
        AgentMemoryExtractionDO extraction = AgentMemoryExtractionDO.builder()
                .id("e-6010").userId(USER_ID)
                .status(AgentMemoryExtractionStatus.PROCESSING.name())
                .attemptCount(1).build();
        when(extractionMapper.freezePlan(anyString(), anyString(), anyString(), anyLong(), anyInt()))
                .thenReturn(1);

        assertThat(repository.freezePlan(extraction,
                planOf(List.of(AgentMemoryDecision.retract("m-8"))), 30)).isTrue();

        verify(extractionMapper).freezePlan(eq("e-6010"), eq(USER_ID),
                anyString(), eq(REVISION), eq(30));
    }

    private AgentMemoryExtractionDO approvedRow(AgentMemoryPlan plan) {
        return AgentMemoryExtractionDO.builder()
                .id(PLAN_ID)
                .userId(USER_ID)
                .status(AgentMemoryExtractionStatus.APPROVED.name())
                .planJson(plan.toJson())
                .planToolCallId(TOOL_CALL_ID)
                .planConfirmMessageId("m-4004")
                .build();
    }

    private AgentMemoryPlan planOf(List<AgentMemoryDecision> decisions) {
        return new AgentMemoryPlan(PLAN_ID, USER_ID, "c-41", "m-1", "m-2", 2, 3,
                REVISION, WATERMARK, "FLUSH", decisions, Map.of());
    }
}
