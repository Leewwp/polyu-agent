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

package com.nageoffer.ai.ragent.agent.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryExtractionDO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;


/**
 * 抽取台账 Mapper，水位是这张表上的聚合查询而不是独立游标行
 * 水位、在飞与重试都按用户不按会话：记忆只有一份，消费顺序必须是用户说话的先后
 */
@SuppressWarnings({"SqlDialectInspection", "SqlNoDataSourceInspection", "SqlResolve"})
public interface AgentMemoryExtractionMapper extends BaseMapper<AgentMemoryExtractionDO> {

    /**
     * 水位：已结束抽取的最大末条ID；IN 列表与 selectSettledStatusCovering 同一套终态——
     * 普通批的 WRITTEN/NOOP/DROPPED，加上审批计划终态 APPLIED/REJECTED/EXPIRED/INVALIDATED。
     * 受审区间一经裁决即消费，否则同一句「忘掉/清空」会被下一轮抽取再判一次
     */
    @Select("""
            SELECT max(to_message_id)
            FROM t_agent_memory_extraction
            WHERE user_id = #{userId}
              AND status IN ('WRITTEN', 'NOOP', 'DROPPED', 'APPLIED', 'REJECTED', 'EXPIRED', 'INVALIDATED')
            """)
    String selectWatermark(@Param("userId") String userId);

    /**
     * 覆盖某条用户消息的那次已结束抽取的状态，没有返回 null
     * 批次在用户维度首尾相接，区间包含即批内成员；改版前按会话切的旧批次区间会互相交叠，但只罩得住改版前的消息
     */
    @Select("""
            SELECT status
            FROM t_agent_memory_extraction
            WHERE user_id = #{userId}
              AND status IN ('WRITTEN', 'NOOP', 'DROPPED', 'APPLIED', 'REJECTED', 'EXPIRED', 'INVALIDATED')
              AND from_message_id <= #{messageId} AND to_message_id >= #{messageId}
            ORDER BY to_message_id DESC
            LIMIT 1
            """)
    String selectSettledStatusCovering(@Param("userId") String userId,
                                       @Param("messageId") String messageId);

    /**
     * 结算：只允许从 PROCESSING 出发，重复结算返回 0
     * attemptCount 一并回写，快照失配靠把它退回上一档来实现「不计入尝试次数」
     */
    @Update("""
            UPDATE t_agent_memory_extraction
            SET status = #{status}, decision_count = #{decisionCount},
                attempt_count = #{attemptCount}, settle_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND status = 'PROCESSING'
            """)
    int settle(@Param("id") String id,
               @Param("status") String status,
               @Param("decisionCount") int decisionCount,
               @Param("attemptCount") int attemptCount);

    /**
     * 僵尸回收：JVM 死在 Judge 中途会留下永不结算的在飞行，超时后按失配结掉腾出 claim
     * 这一路留着尝试次数不退档：能把进程带走的抽取不该无限重来
     */
    @Update("""
            UPDATE t_agent_memory_extraction
            SET status = 'CONFLICT', settle_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND status = 'PROCESSING'
              AND create_time < CURRENT_TIMESTAMP - make_interval(mins => #{staleMinutes})
            """)
    int recycleStale(@Param("userId") String userId,
                     @Param("staleMinutes") int staleMinutes);

    /**
     * 同一区间已耗掉的尝试次数，行上的 attempt_count 已是结算后的有效值，不必再按状态过滤
     */
    @Select("""
            SELECT coalesce(max(attempt_count), 0)
            FROM t_agent_memory_extraction
            WHERE user_id = #{userId}
              AND to_message_id = #{toMessageId}
            """)
    int selectSpentAttempts(@Param("userId") String userId,
                            @Param("toMessageId") String toMessageId);

    /**
     * 该用户当前待审（或已批未执行）的计划，至多一行，由部分唯一索引兜底；没有返回 null
     */
    @Select("""
            SELECT *
            FROM t_agent_memory_extraction
            WHERE user_id = #{userId}
              AND status IN ('PENDING_APPROVAL', 'APPROVED')
            ORDER BY id DESC
            LIMIT 1
            """)
    AgentMemoryExtractionDO selectPendingPlan(@Param("userId") String userId);

    /**
     * 冻结受审批次：把 claim 到的 PROCESSING 行连同计划快照一起转入待审态，条件更新防止重复冻结
     * 有效期由库时钟统一定（CURRENT_TIMESTAMP + expiryMinutes），应用侧不另持时钟
     * 会话存在性守卫：后台 judge 在飞期间源会话被删（invalidatePlan 只收已冻结态，会话删除是
     * @TableLogic 软删），这里按业务键+未删判活——否则会冻结出引用已删会话消息、
     * 仍可被领取确认执行的孤立计划
     */
    @Update("""
            UPDATE t_agent_memory_extraction
            SET status = 'PENDING_APPROVAL',
                plan_json = #{planJson},
                expected_revision = #{expectedRevision},
                plan_expires_at = CURRENT_TIMESTAMP + make_interval(mins => #{expiryMinutes})
            WHERE id = #{id} AND user_id = #{userId} AND status = 'PROCESSING'
              AND EXISTS (SELECT 1 FROM t_agent_conversation c
                          WHERE c.conversation_id = t_agent_memory_extraction.conversation_id
                            AND c.user_id = t_agent_memory_extraction.user_id
                            AND c.deleted = 0)
            """)
    int freezePlan(@Param("id") String id,
                   @Param("userId") String userId,
                   @Param("planJson") String planJson,
                   @Param("expectedRevision") long expectedRevision,
                   @Param("expiryMinutes") int expiryMinutes);

    /**
     * 批准：待审/已批行都允许（幂等重批=改绑新 toolCallId），过期行不许批；
     * 只能由确认端点这一条路写入，行是否可批以库内条件为准。
     * 重批是 #278 决议 10 的恢复路径（批准后未执行，用户新卡上再确认即改绑），
     * 决议 9 的「不能套用旧批准」由 APPLIED 永不进本条件（approve 先行短路成回放）与有效期约束共同保证
     */
    @Update("""
            UPDATE t_agent_memory_extraction
            SET status = 'APPROVED',
                plan_tool_call_id = #{toolCallId},
                plan_confirm_message_id = #{confirmMessageId}
            WHERE id = #{id}
              AND user_id = #{userId}
              AND status IN ('PENDING_APPROVAL', 'APPROVED')
              AND plan_expires_at > CURRENT_TIMESTAMP
            """)
    int approvePlan(@Param("id") String id,
                    @Param("userId") String userId,
                    @Param("toolCallId") String toolCallId,
                    @Param("confirmMessageId") String confirmMessageId);

    /**
     * 拒绝：用户点取消即终局，过期与否不影响——用户意图是事实，过期只决定还能不能执行
     */
    @Update("""
            UPDATE t_agent_memory_extraction
            SET status = 'REJECTED', settle_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND user_id = #{userId}
              AND status IN ('PENDING_APPROVAL', 'APPROVED')
            """)
    int rejectPlan(@Param("id") String id, @Param("userId") String userId);

    /**
     * 执行结算：只在批准且未过期的行上成立，0 行说明已被旁路裁决（重复执行/过期），由调用方重读行分辨
     * 记忆变更与本句同一事务，落空即整体回滚
     */
    @Update("""
            UPDATE t_agent_memory_extraction
            SET status = 'APPLIED',
                plan_result_json = #{resultJson},
                decision_count = #{decisionCount},
                settle_time = CURRENT_TIMESTAMP
            WHERE id = #{id}
              AND user_id = #{userId}
              AND status = 'APPROVED'
              AND plan_expires_at > CURRENT_TIMESTAMP
            """)
    int settlePlanApplied(@Param("id") String id,
                          @Param("userId") String userId,
                          @Param("resultJson") String resultJson,
                          @Param("decisionCount") int decisionCount);

    /**
     * 失效结算：执行时版本/水位复核不过，或源会话被删除；会话删除按会话整批置失效
     */
    @Update("""
            UPDATE t_agent_memory_extraction
            SET status = 'INVALIDATED', settle_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND (#{id}::varchar IS NULL OR id = #{id})
              AND (#{conversationId}::varchar IS NULL OR conversation_id = #{conversationId})
              AND status IN ('PENDING_APPROVAL', 'APPROVED')
            """)
    int invalidatePlan(@Param("userId") String userId,
                       @Param("id") String id,
                       @Param("conversationId") String conversationId);

    /**
     * 按需结算到期：读取、确认、执行、新请求检查都先走这里，不建定时清扫
     * 返回结算掉的行数；行 id 由调用方另行查询定位待失效的确认卡
     */
    @Update("""
            UPDATE t_agent_memory_extraction
            SET status = 'EXPIRED', settle_time = CURRENT_TIMESTAMP
            WHERE user_id = #{userId}
              AND status IN ('PENDING_APPROVAL', 'APPROVED')
              AND plan_expires_at <= CURRENT_TIMESTAMP
            """)
    int expireStalePlans(@Param("userId") String userId);
}
