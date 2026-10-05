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
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryExtractionDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryExtractionStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 受审批次的管线行为：冻结不落库、前台领取、后台暂缓（#278 防绕过的管线半边）
 */
class AgentMemoryPipelineHitlTest {

    private static final String USER_ID = "u-2001";
    private static final String CONVERSATION_ID = "c-21";
    private static final String WATERMARK = "1900000000000000009";
    private static final long REVISION = 4L;

    private AgentMemoryRepository memoryRepository;
    private AgentMemoryJudge memoryJudge;
    private AgentMemoryPipeline pipeline;

    @BeforeEach
    void setUp() {
        memoryRepository = mock(AgentMemoryRepository.class);
        memoryJudge = mock(AgentMemoryJudge.class);
        // 用真实审批服务：buildPlan 的快照组装与冻结收口一并被测到，仓储面留给 mock
        AgentMemoryApprovalService approvalService = new AgentMemoryApprovalService(
                memoryRepository, mock(AgentMemoryExtractionMapper.class), mock(AgentMessageMapper.class));
        pipeline = new AgentMemoryPipeline(memoryRepository, memoryJudge,
                mock(AgentMemoryConsolidator.class), new AgentMemoryProperties(), approvalService);

        AgentMemoryControlDO control = new AgentMemoryControlDO();
        control.setUserId(USER_ID);
        control.setRevision(REVISION);
        control.setCreateTime(new Date());
        when(memoryRepository.ensureControl(USER_ID)).thenReturn(control);
        when(memoryRepository.currentWatermark(USER_ID)).thenReturn(WATERMARK);
        when(memoryRepository.loadPending(eq(USER_ID), eq(WATERMARK), any())).thenReturn(List.of(message("m-1"), message("m-2")));
        when(memoryRepository.expireStalePlans(USER_ID)).thenReturn(0);
        when(memoryRepository.pendingPlan(USER_ID)).thenReturn(null);
        when(memoryRepository.freezePlan(any(), any(), anyInt())).thenReturn(true);
    }

    /**
     * Judge 判出 RETRACT：整批不提交，冻结计划返回 operationId，一行记忆都不动
     */
    @Test
    void shouldFreezeRetractBatchInsteadOfCommitting() {
        AgentMemoryExtractionDO extraction = claimed("e-9001");
        when(memoryRepository.claim(eq(USER_ID), eq(CONVERSATION_ID), anyString(), anyString(),
                eq(AgentMemoryTriggerType.FLUSH))).thenReturn(extraction);
        when(memoryRepository.listActiveItems(USER_ID)).thenReturn(List.of(new AgentMemoryItem("m-8", "用户穿 L 码")));
        when(memoryJudge.judge(anyList(), anyList())).thenReturn(List.of(AgentMemoryDecision.retract("m-8")));

        AgentMemoryOutcome outcome = pipeline.extract(USER_ID, CONVERSATION_ID, AgentMemoryTriggerType.FLUSH);

        assertThat(outcome.status()).isEqualTo(Status.PLAN_PREPARED);
        assertThat(outcome.operationId()).isEqualTo("e-9001");
        verify(memoryRepository, never()).commit(any(AgentMemoryCommit.class));
        verify(memoryRepository, never()).settleFailure(any());
        ArgumentCaptor<AgentMemoryPlan> plan = ArgumentCaptor.forClass(AgentMemoryPlan.class);
        verify(memoryRepository).freezePlan(eq(extraction), plan.capture(), eq(30));
        assertThat(plan.getValue().decisions()).containsExactly(AgentMemoryDecision.retract("m-8"));
        assertThat(plan.getValue().targetContents()).containsEntry("m-8", "用户穿 L 码");
        assertThat(plan.getValue().expectedRevision()).isEqualTo(REVISION);
        assertThat(plan.getValue().expectedWatermark()).isEqualTo(WATERMARK);
        assertThat(plan.getValue().pendingMessageCount()).isEqualTo(2);
    }

    /**
     * CLEAR + ADD 联合批整批冻结：不拆批、不清空、新增也不先落
     */
    @Test
    void shouldFreezeJointClearAddBatchAsOnePlan() {
        AgentMemoryExtractionDO extraction = claimed("e-9002");
        when(memoryRepository.loadPending(eq(USER_ID), eq(WATERMARK), any()))
                .thenReturn(List.of(message("m-1"), message("m-2"), message("m-3")));
        when(memoryRepository.claim(anyString(), anyString(), anyString(), anyString(),
                eq(AgentMemoryTriggerType.BACKGROUND))).thenReturn(extraction);
        when(memoryRepository.listActiveItems(USER_ID)).thenReturn(List.of(new AgentMemoryItem("m-8", "旧记忆")));
        when(memoryJudge.judge(anyList(), anyList())).thenReturn(List.of(
                AgentMemoryDecision.clear(), AgentMemoryDecision.add("清空后要记的新事实")));

        AgentMemoryOutcome outcome = pipeline.extract(USER_ID, CONVERSATION_ID, AgentMemoryTriggerType.BACKGROUND);

        // 后台发现受审批次同样只冻结：后台永远无法提交 RETRACT/CLEAR
        assertThat(outcome.status()).isEqualTo(Status.PLAN_PREPARED);
        assertThat(outcome.operationId()).isEqualTo("e-9002");
        verify(memoryRepository, never()).commit(any());
        verify(memoryRepository, never()).executeApprovedPlan(anyString(), anyString(), any());
        ArgumentCaptor<AgentMemoryPlan> plan = ArgumentCaptor.forClass(AgentMemoryPlan.class);
        verify(memoryRepository).freezePlan(eq(extraction), plan.capture(), eq(30));
        assertThat(plan.getValue().containsClear()).isTrue();
        assertThat(plan.getValue().decisions()).hasSize(2);
        assertThat(plan.getValue().activeItemCount()).isEqualTo(1);
        assertThat(plan.getValue().triggerType()).isEqualTo(AgentMemoryTriggerType.BACKGROUND.name());
    }

    /**
     * 冻结落空按失败结算：不能把台账行留在 PROCESSING 等僵尸回收
     */
    @Test
    void shouldSettleFailureWhenFreezeMisses() {
        AgentMemoryExtractionDO extraction = claimed("e-9003");
        when(memoryRepository.claim(anyString(), anyString(), anyString(), anyString(),
                eq(AgentMemoryTriggerType.FLUSH))).thenReturn(extraction);
        when(memoryRepository.listActiveItems(USER_ID)).thenReturn(List.of());
        when(memoryJudge.judge(anyList(), anyList())).thenReturn(List.of(AgentMemoryDecision.clear()));
        when(memoryRepository.freezePlan(any(), any(), anyInt())).thenReturn(false);

        AgentMemoryOutcome outcome = pipeline.extract(USER_ID, CONVERSATION_ID, AgentMemoryTriggerType.FLUSH);

        assertThat(outcome.status()).isEqualTo(Status.FAILED);
        assertThat(outcome.operationId()).isNull();
    }

    /**
     * 待审计划在场：前台领取同一计划（不新建、不再判），编号原样带回
     */
    @Test
    void flushShouldPickUpExistingPendingPlanWithoutJudging() {
        when(memoryRepository.pendingPlan(USER_ID)).thenReturn(pending("e-8001"));

        AgentMemoryOutcome outcome = pipeline.flush(USER_ID, CONVERSATION_ID, "m-2");

        assertThat(outcome.status()).isEqualTo(Status.PLAN_PREPARED);
        assertThat(outcome.operationId()).isEqualTo("e-8001");
        verifyNoInteractions(memoryJudge);
        verify(memoryRepository, never()).claim(anyString(), anyString(), anyString(), anyString(), any());
    }

    /**
     * 待审计划在场：后台暂缓本轮抽取，也不占日志噪音位（idle）
     */
    @Test
    void backgroundShouldPauseWhilePlanPending() {
        when(memoryRepository.pendingPlan(USER_ID)).thenReturn(pending("e-8001"));

        AgentMemoryOutcome outcome = pipeline.extract(USER_ID, CONVERSATION_ID, AgentMemoryTriggerType.BACKGROUND);

        assertThat(outcome.status()).isEqualTo(Status.PLAN_PENDING);
        assertThat(outcome.idle()).isTrue();
        verify(memoryRepository, never()).claim(anyString(), anyString(), anyString(), anyString(), any());
        verifyNoInteractions(memoryJudge);
    }

    private AgentMemoryExtractionDO claimed(String id) {
        return AgentMemoryExtractionDO.builder()
                .id(id)
                .userId(USER_ID)
                .conversationId(CONVERSATION_ID)
                .status(AgentMemoryExtractionStatus.PROCESSING.name())
                .triggerType(AgentMemoryTriggerType.FLUSH.name())
                .attemptCount(1)
                .build();
    }

    private AgentMemoryExtractionDO pending(String id) {
        return AgentMemoryExtractionDO.builder()
                .id(id)
                .userId(USER_ID)
                .conversationId(CONVERSATION_ID)
                .status(AgentMemoryExtractionStatus.PENDING_APPROVAL.name())
                .planJson("{}")
                .build();
    }

    private AgentMessageDO message(String id) {
        AgentMessageDO message = new AgentMessageDO();
        message.setId(id);
        message.setUserId(USER_ID);
        message.setConversationId(CONVERSATION_ID);
        message.setRole("user");
        message.setCreateTime(new Date());
        return message;
    }
}
