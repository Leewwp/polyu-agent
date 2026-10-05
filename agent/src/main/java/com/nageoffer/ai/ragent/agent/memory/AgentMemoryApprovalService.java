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

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMemoryExtractionDO;
import com.nageoffer.ai.ragent.agent.dao.entity.AgentMessageDO;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMemoryExtractionMapper;
import com.nageoffer.ai.ragent.agent.dao.mapper.AgentMessageMapper;
import com.nageoffer.ai.ragent.agent.dto.AgentBlock;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmCall;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmField;
import com.nageoffer.ai.ragent.agent.enums.AgentMessageStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryExtractionStatus;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.tool.MemoryApplyTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 记忆变更计划的审批编排：冻结、领取、批准、拒绝、按需到期与卡片投影都从这里走
 * 台账行是唯一事实源——批准/拒绝只能由确认端点写入，执行只认 APPROVED 行，其余入口一律拒绝
 * 不依赖会话服务（反向依赖会成环），确认卡失效由聊天入口拿着这里返回的消息号去办
 */
@Slf4j
@Component
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentMemoryApprovalService {

    /**
     * 计划初始有效期，票面固定 30 分钟；到期在读取/确认/执行/新请求检查时按需结算，不建清扫服务
     */
    public static final int APPROVAL_EXPIRY_MINUTES = 30;

    private final AgentMemoryRepository memoryRepository;
    private final AgentMemoryExtractionMapper extractionMapper;
    private final AgentMessageMapper messageMapper;

    /**
     * 先按需结算到期计划，再读该用户当前待审（或已批未执行）的计划行，没有返回 null
     */
    public AgentMemoryExtractionDO settleExpiredAndLoadPending(String userId) {
        int expired = memoryRepository.expireStalePlans(userId);
        if (expired > 0) {
            log.warn("长期记忆待审计划到期结算, userId: {}, 条数: {}", userId, expired);
        }
        return memoryRepository.pendingPlan(userId);
    }

    /**
     * 把受审批次冻结成计划：目标正文、消息范围与快照凭证一并入快照，用户批准的就是这一份
     */
    public AgentMemoryPlan buildPlan(String extractionId, String userId, String conversationId,
                                     List<AgentMessageDO> pending, List<AgentMemoryItem> existing,
                                     List<AgentMemoryDecision> decisions, long expectedRevision,
                                     String expectedWatermark, AgentMemoryTriggerType trigger) {
        Map<String, String> targetContents = new LinkedHashMap<>();
        for (AgentMemoryDecision decision : decisions) {
            if (decision.targetId() == null) {
                continue;
            }
            existing.stream()
                    .filter(item -> Objects.equals(item.id(), decision.targetId()))
                    .findFirst()
                    .ifPresent(item -> targetContents.put(item.id(), item.content()));
        }
        return new AgentMemoryPlan(extractionId, userId, conversationId,
                pending.isEmpty() ? null : pending.get(0).getId(),
                pending.isEmpty() ? null : pending.get(pending.size() - 1).getId(),
                pending.size(), existing.size(), expectedRevision, expectedWatermark,
                trigger.name(), decisions, targetContents);
    }

    /**
     * 冻结入台账；冻结不改变任何生效记忆，抽取互斥位随状态迁移腾出
     */
    public boolean freeze(AgentMemoryExtractionDO extraction, AgentMemoryPlan plan) {
        return memoryRepository.freezePlan(extraction, plan, APPROVAL_EXPIRY_MINUTES);
    }

    /**
     * 确认端点的批准入口：入参来自服务端状态里的工具调用，前端只递 boolean
     * 失败原因分三类返回，调用方据此失效卡片并提示重新发起
     *
     * <p>APPROVED 行的幂等重批（改绑新 toolCallId/确认消息）是 #278 决议 10 的恢复路径：
     * 批准后尚未执行时（进程重启、回包丢失），用户在新确认卡上再次点同意即改绑重执行。
     * 决议 9「不能套用旧批准」指的是不经用户新确认就复用旧批准——重批每次都有新卡新点击，
     * 且仍受有效期、expectedRevision、水位与执行时 toolCallId 校验约束；APPLIED 行永不重批（只回放结果）。
     */
    public ApprovalTransition approve(String operationId, String userId, String toolCallId, String confirmMessageId) {
        AgentMemoryExtractionDO row = extractionMapper.selectById(operationId);
        if (row == null || !Objects.equals(row.getUserId(), userId)) {
            return ApprovalTransition.notFound("该记忆变更计划不存在或不属于当前用户");
        }
        if (AgentMemoryExtractionStatus.APPLIED.name().equals(row.getStatus())) {
            return ApprovalTransition.alreadyApplied(row.getPlanResultJson());
        }
        boolean approved = memoryRepository.approvePlan(operationId, userId, toolCallId, confirmMessageId);
        if (!approved) {
            // 条件更新落空：已拒绝/已失效，或恰好过期
            return switch (AgentMemoryExtractionStatus.valueOf(row.getStatus())) {
                case REJECTED -> ApprovalTransition.notFound("该计划已被取消，不能再确认");
                case INVALIDATED -> ApprovalTransition.notFound("该计划已失效，请重新发起");
                case EXPIRED, PENDING_APPROVAL, APPROVED -> ApprovalTransition.expired();
                default -> ApprovalTransition.notFound("该计划已不可确认，请重新发起");
            };
        }
        log.info("长期记忆计划已批准待执行, userId: {}, operationId: {}, toolCallId: {}",
                userId, operationId, toolCallId);
        return ApprovalTransition.approved();
    }

    /**
     * 确认端点的拒绝入口：终局结算并推水位，旧「忘记/清空」指令不再重放；落空说明已被旁路裁决
     */
    public void reject(String operationId, String userId) {
        if (!memoryRepository.rejectPlan(operationId, userId)) {
            log.info("长期记忆计划拒绝落空, 状态已被旁路裁决, userId: {}, operationId: {}", userId, operationId);
            return;
        }
        log.info("长期记忆计划已拒绝, userId: {}, operationId: {}", userId, operationId);
    }

    /**
     * 执行入口：仅批准后的执行工具调用；重复执行回放原结果
     */
    public AgentMemoryPlanExecution execute(String operationId, String userId, String toolCallId) {
        return memoryRepository.executeApprovedPlan(operationId, userId, toolCallId);
    }

    /**
     * 新请求检查：先按需结算到期计划（幂等——后台/前台谁先结都收敛到同一状态），
     * 再扫该会话的待确认消息，找出计划已走不下去（过期/拒绝/失效/已执行/不存在）的死卡消息号
     * 返回的消息号由调用方走既有 expirePendingConfirm 失效，30 分钟到期不永久阻塞原会话
     */
    public List<String> settleExpiredAndLocateDeadCards(String userId, String conversationId) {
        memoryRepository.expireStalePlans(userId);
        List<AgentMessageDO> awaiting = messageMapper.selectList(Wrappers.lambdaQuery(AgentMessageDO.class)
                .eq(AgentMessageDO::getConversationId, conversationId)
                .eq(AgentMessageDO::getUserId, userId)
                .eq(AgentMessageDO::getMessageStatus, AgentMessageStatus.AWAITING_CONFIRM.name()));
        List<String> deadCards = new ArrayList<>();
        Map<String, Boolean> confirmableCache = new LinkedHashMap<>();
        for (AgentMessageDO message : awaiting) {
            if (message.getBlocks() == null) {
                continue;
            }
            List<String> planIds = message.getBlocks().stream()
                    .filter(block -> AgentBlock.KIND_CONFIRM.equals(block.getKind()))
                    .flatMap(block -> block.getCalls() == null ? Stream.<AgentConfirmCall>empty()
                            : block.getCalls().stream())
                    .map(AgentMemoryApprovalService::operationIdOf)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (planIds.isEmpty()) {
                continue;
            }
            boolean dead = planIds.stream().anyMatch(planId -> !confirmableCache.computeIfAbsent(planId,
                    id -> planConfirmable(id, userId)));
            if (dead) {
                deadCards.add(message.getId());
            }
        }
        if (!deadCards.isEmpty()) {
            log.warn("长期记忆计划已到期/终局, 会话内关联确认卡一并失效, userId: {}, conversationId: {}, 消息: {}",
                    userId, conversationId, deadCards);
        }
        return deadCards;
    }

    /**
     * 计划此刻是否还能被用户确认：只认结算后仍是待审/已批未执行的行
     * 刚被结算成 EXPIRED（含后台先结）或早已终局的行都算走不下去
     */
    private boolean planConfirmable(String operationId, String userId) {
        AgentMemoryExtractionDO row = extractionMapper.selectById(operationId);
        if (row == null || !Objects.equals(row.getUserId(), userId)) {
            return false;
        }
        return AgentMemoryExtractionStatus.PENDING_APPROVAL.name().equals(row.getStatus())
                || AgentMemoryExtractionStatus.APPROVED.name().equals(row.getStatus());
    }

    /**
     * 记忆确认卡上的计划编号：真实卡片的编号在服务端注入的 arguments JSON（describeForCard 折叠区）里，
     * fields 是中文标签主视图不带编号——两个位置都扫，老形状 fields 兜底；MCP 卡片不受影响
     */
    private static String operationIdOf(AgentConfirmCall call) {
        if (call == null || !MemoryApplyTool.TOOL_NAME.equals(call.getName())) {
            return null;
        }
        String arguments = call.getArguments();
        if (StrUtil.isNotBlank(arguments)) {
            try {
                String candidate = JSONUtil.parseObj(arguments).getStr("operationId");
                if (StrUtil.isNotBlank(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // arguments 不是 JSON 对象（如纯文本折叠区）→ 落回 fields 扫描
            }
        }
        if (call.getFields() == null) {
            return null;
        }
        return call.getFields().stream()
                .filter(field -> "operationId".equals(field.getName()))
                .map(AgentConfirmField::getValue)
                .filter(StrUtil::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    /**
     * 源会话删除：该会话名下的待审/已批未执行计划整批置失效，不留可继续执行的孤立计划
     * PROCESSING 在飞批由冻结口的会话存在性守卫兜底（judge 结束时源会话已删则冻结落空按失败结算）
     */
    public void invalidateForDeletedConversation(String userId, String conversationId) {
        int invalidated = memoryRepository.invalidatePlans(userId, null, conversationId);
        if (invalidated > 0) {
            log.warn("源会话删除, 记忆变更计划随之失效, userId: {}, conversationId: {}, 条数: {}",
                    userId, conversationId, invalidated);
        }
    }

    /**
     * 确认卡投影：把冻结计划渲染成 fields（主视图）+ 完整计划 JSON（折叠区），复用现有卡片形状
     * 找不到计划（含他人计划）或计划已终局/不在待审面（过期/拒绝/失效/已执行）返回 null，
     * 卡片退化为只展示编号，确认端点与执行侧自会拒绝——终局计划不允许再渲染成可确认的样子
     */
    public PlanCardProjection describeForCard(String operationId, String userId) {
        if (StrUtil.isBlank(operationId)) {
            return null;
        }
        AgentMemoryExtractionDO row = extractionMapper.selectById(operationId);
        if (row == null || !Objects.equals(row.getUserId(), userId)) {
            return null;
        }
        if (!AgentMemoryExtractionStatus.PENDING_APPROVAL.name().equals(row.getStatus())
                && !AgentMemoryExtractionStatus.APPROVED.name().equals(row.getStatus())) {
            return null;
        }
        AgentMemoryPlan plan = AgentMemoryPlan.fromJson(row.getPlanJson());
        if (plan == null) {
            return null;
        }
        List<AgentConfirmField> fields = new ArrayList<>();
        fields.add(field("变更内容", scopeText(plan)));
        List<AgentMemoryDecision> retracts = plan.decisions().stream()
                .filter(decision -> decision.action() == AgentMemoryDecision.Action.RETRACT)
                .toList();
        if (!retracts.isEmpty()) {
            fields.add(field("撤回条目", retracts.stream()
                    .map(decision -> plan.targetContents().containsKey(decision.targetId())
                            ? "「" + plan.targetContents().get(decision.targetId()) + "」"
                            : "条目 " + decision.targetId())
                    .reduce((a, b) -> a + "；" + b).orElse("")));
        }
        List<AgentMemoryDecision> additions = plan.additions();
        if (!additions.isEmpty()) {
            fields.add(field("联合新增", additions.stream()
                    .map(decision -> "「" + decision.content() + "」")
                    .reduce((a, b) -> a + "；" + b).orElse("")));
        }
        fields.add(field("覆盖范围", "本次计划覆盖 " + plan.pendingMessageCount() + " 条尚未整理的发言"));
        fields.add(field("生效方式", "确认后从生效长期记忆中移除；取消则不执行，可随时重新发起"));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("operationId", operationId);
        raw.put("decisions", plan.decisions());
        raw.put("fromMessageId", plan.fromMessageId());
        raw.put("toMessageId", plan.toMessageId());
        raw.put("triggerType", plan.triggerType());
        return new PlanCardProjection(fields, JSONUtil.toJsonPrettyStr(raw));
    }

    private static String scopeText(AgentMemoryPlan plan) {
        boolean clear = plan.containsClear();
        String base = clear
                ? "清空全部长期记忆（现有 " + plan.activeItemCount() + " 条）"
                : "撤回 " + plan.retractCount() + " 条指定记忆";
        return plan.additions().isEmpty() ? base : base + "，并新增 " + plan.additions().size() + " 条";
    }

    private static AgentConfirmField field(String label, String value) {
        return AgentConfirmField.builder().name(label).label(label).value(value).build();
    }

    /**
     * 批准入口的三类结局：同意 / 已执行（回放结果）/ 不可确认（带原因）
     */
    public record ApprovalTransition(Outcome outcome, String reason, String appliedResult) {

        public enum Outcome {
            APPROVED, ALREADY_APPLIED, NOT_CONFIRMABLE
        }

        public static ApprovalTransition approved() {
            return new ApprovalTransition(Outcome.APPROVED, null, null);
        }

        public static ApprovalTransition alreadyApplied(String resultJson) {
            return new ApprovalTransition(Outcome.ALREADY_APPLIED, null, resultJson);
        }

        public static ApprovalTransition notFound(String reason) {
            return new ApprovalTransition(Outcome.NOT_CONFIRMABLE, reason, null);
        }

        public static ApprovalTransition expired() {
            return new ApprovalTransition(Outcome.NOT_CONFIRMABLE,
                    "该计划已超过 30 分钟有效期，请重新发起", null);
        }
    }

    /**
     * 确认卡投影：fields 进主视图，arguments 进折叠区，沿用既有 MCP 卡片的两个位置
     */
    public record PlanCardProjection(List<AgentConfirmField> fields, String arguments) {
    }
}
