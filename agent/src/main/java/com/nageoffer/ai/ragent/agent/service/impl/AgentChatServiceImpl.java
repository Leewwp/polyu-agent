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

package com.nageoffer.ai.ragent.agent.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.agent.config.ConditionalOnAgentEngine;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider;
import com.nageoffer.ai.ragent.agent.config.ReActAgentProvider.ActiveAgent;
import com.nageoffer.ai.ragent.agent.dto.AgentConfirmSettlement;
import com.nageoffer.ai.ragent.agent.dto.AgentMetaPayload;
import com.nageoffer.ai.ragent.agent.enums.AgentMemoryTriggerType;
import com.nageoffer.ai.ragent.agent.enums.AgentSSEEventType;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryOutcome;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryPipeline;
import com.nageoffer.ai.ragent.agent.memory.AgentMemoryProperties;
import com.nageoffer.ai.ragent.agent.service.AgentChatService;
import com.nageoffer.ai.ragent.agent.service.AgentConversationService;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunGate;
import com.nageoffer.ai.ragent.agent.service.handler.AgentRunHandle;
import com.nageoffer.ai.ragent.agent.service.handler.AgentStreamEventBridge;
import com.nageoffer.ai.ragent.agent.tool.AgentToolExecutionFacts;
import com.nageoffer.ai.ragent.agent.trace.AgentTraceContextKeys;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.framework.web.StreamTaskManager;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Agent 流式对话，负责发起提问与用户确认两条入口
 */
@Slf4j
@Service
@ConditionalOnAgentEngine
@RequiredArgsConstructor
public class AgentChatServiceImpl implements AgentChatService {

    private final ReActAgentProvider agentProvider;
    private final AgentConversationService conversationService;
    private final StreamTaskManager taskManager;
    private final AgentRunGate runGate;
    private final AgentMemoryProperties memoryProperties;
    private final AgentMemoryPipeline memoryPipeline;
    /**
     * 服务端时刻来源，事实源与桥共用
     */
    private final Clock clock = Clock.systemDefaultZone();

    @Override
    public void streamChat(String question, String conversationId, SseEmitter emitter) {
        String userId = UserContext.getUserId();
        String actualConversationId = StrUtil.isBlank(conversationId)
                ? IdUtil.getSnowflakeNextIdStr()
                : conversationId;
        // 有待确认的工具调用时不接受新问题，否则框架会报英文技术异常
        if (StrUtil.isNotBlank(conversationId)
                && conversationService.hasPendingConfirm(actualConversationId, userId)) {
            throw new ClientException("上一步操作还在等你确认，请先确认或取消");
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();

        guardedStart(userId, actualConversationId, taskId,
                releaseGate -> startRun(question, userId, actualConversationId, taskId, emitter, releaseGate));
    }

    @Override
    public void confirmPendingTool(String conversationId, String messageId, boolean approved, SseEmitter emitter) {
        String userId = UserContext.getUserId();
        if (StrUtil.isBlank(conversationId) || StrUtil.isBlank(messageId)) {
            throw new ClientException("确认参数不完整");
        }
        String taskId = IdUtil.getSnowflakeNextIdStr();

        guardedStart(userId, conversationId, taskId,
                releaseGate -> startConfirmRun(userId, conversationId, messageId, approved, taskId, emitter, releaseGate));
    }

    /**
     * 先拿并发锁再启动流，启动失败时立即释放锁并注销任务，防止用户被锁半小时
     */
    private void guardedStart(String userId, String conversationId, String taskId, Consumer<Runnable> starter) {
        Runnable releaseGate = runGate.acquire(userId, taskId, conversationId);
        boolean started = false;
        try {
            starter.accept(releaseGate);
            started = true;
        } finally {
            if (!started) {
                releaseGate.run();
                taskManager.unregister(taskId);
            }
        }
    }

    private void startRun(String question, String userId, String conversationId, String taskId,
                          SseEmitter emitter, Runnable releaseGate) {
        SseEmitterSender sender = new SseEmitterSender(emitter);
        sender.sendEvent(AgentSSEEventType.META.value(), new AgentMetaPayload(conversationId, taskId));

        String title = conversationService.touchConversation(conversationId, userId, question);
        // 必须在 addUserMessage 之前建立基线，否则本轮消息会被划进历史、漏抽
        if (memoryProperties.isLongTermEnabled()) {
            memoryPipeline.ensureExtractionBaseline(userId);
        }
        String questionMessageId = conversationService.addUserMessage(conversationId, userId, question);

        launchStream(new UserMessage(question), agentProvider.getAgent(), RunScope.builder()
                .sender(sender)
                .emitter(emitter)
                .userId(userId)
                .conversationId(conversationId)
                .taskId(taskId)
                .title(title)
                .replyToMessageId(questionMessageId)
                .releaseGate(releaseGate)
                .build());
    }

    /**
     * 用户确认/拒绝后继续执行：先结算确认卡片再启动流，避免中途断掉后卡片还是 pending 诱导重复点击
     */
    private void startConfirmRun(String userId, String conversationId, String messageId, boolean approved,
                                 String taskId, SseEmitter emitter, Runnable releaseGate) {
        ActiveAgent activeAgent = agentProvider.getAgent();
        List<ConfirmResult> confirmResults = resolveConfirmResults(activeAgent, userId, conversationId, messageId, approved);
        AgentConfirmSettlement settlement = conversationService.settlePendingConfirm(
                conversationId, userId, messageId, approved);

        SseEmitterSender sender = new SseEmitterSender(emitter);
        sender.sendEvent(AgentSSEEventType.META.value(), new AgentMetaPayload(conversationId, taskId));

        // 空正文消息仅携带确认/拒绝结果，框架不会把它并进对话上下文
        Msg resumeMsg = UserMessage.builder()
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, confirmResults))
                .build();
        launchStream(resumeMsg, activeAgent, RunScope.builder()
                .sender(sender)
                .emitter(emitter)
                .userId(userId)
                .conversationId(conversationId)
                .taskId(taskId)
                .title(settlement.title())
                .replyToMessageId(settlement.replyToMessageId())
                .confirmMessageId(messageId)
                .releaseGate(releaseGate)
                .build());
    }

    /**
     * 工具入参从 Agent 状态取，前端只传同意/拒绝，防止篡改
     */
    private List<ConfirmResult> resolveConfirmResults(ActiveAgent activeAgent, String userId,
                                                      String conversationId, String messageId, boolean approved) {
        @SuppressWarnings("resource")
        ReActAgent agent = activeAgent.agent();
        List<ToolUseBlock> asking = askingToolCalls(agent.getAgentState(userId, conversationId).getContext());
        if (asking.isEmpty()) {
            // 状态里没有待确认工具了，先结算卡片再报错，否则会话会一直卡住
            conversationService.expirePendingConfirm(conversationId, userId, messageId);
            throw new ClientException("待确认的操作已失效，请重新提问");
        }
        return asking.stream().map(toolCall -> new ConfirmResult(approved, toolCall)).toList();
    }

    /**
     * 待批工具只在上下文最后一条 assistant 消息上，与框架内部判据一致
     */
    private static List<ToolUseBlock> askingToolCalls(List<Msg> context) {
        for (int i = context.size() - 1; i >= 0; i--) {
            Msg msg = context.get(i);
            if (msg.getRole() != MsgRole.ASSISTANT) {
                continue;
            }
            return msg.getContent().stream()
                    .filter(ToolUseBlock.class::isInstance)
                    .map(ToolUseBlock.class::cast)
                    .filter(toolCall -> toolCall.getState() == ToolCallState.ASKING)
                    .toList();
        }
        return List.of();
    }

    /**
     * 首问与确认后继续执行共用的流式启动逻辑
     */
    private void launchStream(Msg input, ActiveAgent activeAgent, RunScope scope) {
        String userId = scope.userId();
        String conversationId = scope.conversationId();
        String taskId = scope.taskId();

        @SuppressWarnings("resource")
        ReActAgent agent = activeAgent.agent();

        // 事实源：桥、上下文与句柄共用，ID 与时刻只在这里产生
        AgentToolExecutionFacts facts = new AgentToolExecutionFacts(taskId, clock);
        // 上下文传到底，中断时句柄从这里取根 span
        RuntimeContext runtimeContext = buildRuntimeContext(scope, facts);
        AgentRunHandle runHandle = new AgentRunHandle(taskId, scope.sender(), taskManager, facts, runtimeContext);
        runHandle.onRelease(scope.releaseGate());
        // 流结束后驱逐内存缓存，下一轮从 PG 重新加载
        runHandle.onRelease(() -> {
            // 错误路径与强制断流框架都来不及存盘，驱逐前补存一次，否则本轮工具执行结果会丢
            // 优雅中断已由框架中断分支存盘，不重复保存
            if (runHandle.isFailed() || runHandle.isForcedDisposal()) {
                saveAgentStateQuietly(agent, userId, conversationId);
            }
            agentProvider.evictStateCache(userId, conversationId);
        });
        // 放在释放并发锁之后，确保记忆抽取时名额已归还
        runHandle.onRelease(() -> scheduleMemoryExtraction(userId, conversationId));
        bindEmitterLifecycle(scope.emitter(), runHandle, taskId);
        // agent 和 catalog 从同一个 ActiveAgent 取出，保证展示名一致
        AgentStreamEventBridge bridge = new AgentStreamEventBridge(AgentStreamEventBridge.Params.builder()
                .runHandle(runHandle)
                .conversationService(conversationService)
                .catalog(activeAgent.catalog())
                .conversationId(conversationId)
                .userId(userId)
                .title(scope.title())
                .replyToMessageId(scope.replyToMessageId())
                .clock(clock)
                .facts(facts)
                .build());
        taskManager.register(taskId, userId, bridge::finishCancelledStream);
        // 预埋取消标记会让 register 当场跑完收尾，此时不再启动 Agent
        if (runHandle.isSettled()) {
            return;
        }

        Flux<AgentEvent> events = agent.streamEvents(input, runtimeContext)
                // 让 runHandle 知道框架流已结束，取消时不必再强行断流
                .doFinally(signal -> runHandle.markUpstreamTerminated());
        Disposable disposable = events.subscribe(bridge::onEvent, bridge::onError, bridge::onComplete);

        // 取消时先中断框架等其存盘，超时才断流
        runHandle.bindStream(disposable, () -> agent.interrupt(userId, conversationId));
        taskManager.bindHandle(taskId, runHandle::interruptUpstream);
        // 取消抢在绑定前结算时收尾已驱逐状态缓存，优雅打断只会命中新加载的状态白等两秒，直接断流
        if (runHandle.isCancelledExit()) {
            disposable.dispose();
        }
    }

    /**
     * 事实源在构建时就位，中间件从上下文取它；其余 key 建完再 put——底层 ConcurrentHashMap 不接受 null
     */
    private static RuntimeContext buildRuntimeContext(RunScope scope, AgentToolExecutionFacts facts) {
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(scope.userId())
                .sessionId(scope.conversationId())
                .put(AgentToolExecutionFacts.RUNTIME_CONTEXT_KEY, facts)
                .build();
        runtimeContext.put(AgentTraceContextKeys.TASK_ID, scope.taskId());
        runtimeContext.put(AgentTraceContextKeys.REPLY_TO_MESSAGE_ID, scope.replyToMessageId());
        runtimeContext.put(AgentTraceContextKeys.CONFIRM_MESSAGE_ID, scope.confirmMessageId());
        return runtimeContext;
    }

    /**
     * 收尾阶段补存盘，失败只记日志不抛异常，避免吞掉本轮真正的错误
     */
    private void saveAgentStateQuietly(ReActAgent agent, String userId, String conversationId) {
        try {
            agent.saveAgentState(userId, conversationId);
        } catch (Exception e) {
            log.error("Agent 失败收尾补存盘失败, conversationId: {}", conversationId, e);
        }
    }

    /**
     * 单次运行的上下文参数，在 startRun/startConfirmRun 与 launchStream 之间传递
     */
    @Builder
    private record RunScope(SseEmitterSender sender, SseEmitter emitter, String userId, String conversationId,
                            String taskId, String title, String replyToMessageId, String confirmMessageId,
                            Runnable releaseGate) {
    }

    /**
     * 轮次结束后异步抽取长期记忆
     */
    private void scheduleMemoryExtraction(String userId, String conversationId) {
        if (!memoryProperties.isLongTermEnabled()) {
            return;
        }
        Mono.fromCallable(() -> memoryPipeline.extract(userId, conversationId, AgentMemoryTriggerType.BACKGROUND))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(outcome -> logExtraction(userId, conversationId, outcome),
                        // 用户已拿到答复，失败只记日志
                        e -> log.error("轮次结束后台记忆抽取异常, userId: {}, conversationId: {}",
                                userId, conversationId, e));
    }

    private void logExtraction(String userId, String conversationId, AgentMemoryOutcome outcome) {
        if (outcome.idle()) {
            log.debug("轮次结束未触发记忆抽取, conversationId: {}, 结局: {}, 待处理: {}",
                    conversationId, outcome.status(), outcome.pending());
            return;
        }
        log.info("轮次结束后台记忆抽取完成, userId: {}, conversationId: {}, 结局: {}, 落库: {}",
                userId, conversationId, outcome.status(), outcome.applied());
    }

    /**
     * SSE 断开（关页/断网/超时）时触发取消，否则 ReAct 循环会空跑到迭代上限
     */
    private void bindEmitterLifecycle(SseEmitter emitter, AgentRunHandle runHandle, String taskId) {
        AtomicBoolean recycled = new AtomicBoolean(false);
        Runnable recycleUpstream = () -> {
            // 已结算的不再取消，避免往 Redis 留死标记
            if (!runHandle.isSettled() && recycled.compareAndSet(false, true)) {
                taskManager.cancel(taskId);
            }
        };
        emitter.onTimeout(recycleUpstream);
        emitter.onError(e -> recycleUpstream.run());
        // 用户关闭页面走 completeWithError，容器不触发 onTimeout/onError，只有 onCompletion 能兜住
        emitter.onCompletion(recycleUpstream);
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancelByUser(taskId);
    }
}
