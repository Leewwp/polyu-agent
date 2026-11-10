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

package com.nageoffer.ai.ragent.rag.service.ratelimit;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.rag.config.MemoryProperties;
import com.nageoffer.ai.ragent.rag.config.RAGRateLimitProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatQueueLimiterTest {

    private static final String USER_ID = "user-1";

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RPermitExpirableSemaphore semaphore;

    @Mock
    private RScoredSortedSet<String> queue;

    @Mock
    private RAtomicLong queueSequence;

    @Mock
    private RBucket<String> entryMarker;

    @Mock
    private RScript script;

    @Mock
    private RTopic topic;

    @Mock
    private ConversationMemoryService memoryService;

    @Mock
    private ConversationGroupService conversationGroupService;

    private FairDistributedRateLimiter rateLimiter;
    private ChatQueueLimiter chatQueueLimiter;

    @BeforeEach
    void setUp() throws Exception {
        // lenient：拒答出口类用例不触碰排队限流器，redisson 打桩允许闲置
        lenient().when(redissonClient.getPermitExpirableSemaphore(anyString())).thenReturn(semaphore);
        lenient().doReturn(queue).when(redissonClient).getScoredSortedSet(anyString(), eq(StringCodec.INSTANCE));
        lenient().when(redissonClient.getAtomicLong(anyString())).thenReturn(queueSequence);
        lenient().doReturn(entryMarker).when(redissonClient).getBucket(anyString(), eq(StringCodec.INSTANCE));
        lenient().when(redissonClient.getTopic(anyString())).thenReturn(topic);
        lenient().when(queueSequence.incrementAndGet()).thenReturn(1L);

        rateLimiter = new FairDistributedRateLimiter("test:chat", redissonClient, () -> 1, () -> 30, () -> 50);
        rateLimiter.start();
        prestartSchedulerThreads(rateLimiter);

        RAGRateLimitProperties rateLimitProperties = new RAGRateLimitProperties();
        rateLimitProperties.setGlobalEnabled(true);
        rateLimitProperties.setGlobalMaxWaitSeconds(1);

        MemoryProperties memoryProperties = new MemoryProperties();
        memoryProperties.setTitleMaxLength(30);

        chatQueueLimiter = new ChatQueueLimiter(
                rateLimiter,
                Runnable::run,
                rateLimitProperties,
                memoryService,
                conversationGroupService,
                memoryProperties);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        rateLimiter.stop();
    }

    @Test
    void shouldKeepUserContextWhenQueuedRequestIsAcquiredByScheduler() throws Exception {
        when(semaphore.availablePermits()).thenReturn(0, 1);
        when(semaphore.tryAcquire(anyLong(), anyLong(), any())).thenReturn("permit-1");
        when(redissonClient.getScript(eq(StringCodec.INSTANCE))).thenReturn(script);
        doReturn(List.of(1L, 1L)).when(script).eval(
                eq(RScript.Mode.READ_WRITE),
                anyString(),
                eq(RScript.ReturnType.LIST),
                anyList(),
                any(),
                any(),
                any());

        AtomicReference<String> actualUserId = new AtomicReference<>();
        CountDownLatch acquired = new CountDownLatch(1);
        setUserContext();

        chatQueueLimiter.enqueue("排队问题", "conversation-1", new SseEmitter(), () -> {
            actualUserId.set(UserContext.getUserId());
            acquired.countDown();
        });
        UserContext.clear();

        assertTrue(acquired.await(3, TimeUnit.SECONDS));
        assertEquals(USER_ID, actualUserId.get());
    }

    @Test
    void shouldKeepUserContextWhenQueuedRequestTimesOutOnScheduler() throws Exception {
        when(semaphore.availablePermits()).thenReturn(0);
        CountDownLatch memoryWritten = new CountDownLatch(2);
        doAnswer(invocation -> {
            memoryWritten.countDown();
            return "message-1";
        }).when(memoryService).append(anyString(), eq(USER_ID), any());
        setUserContext();

        chatQueueLimiter.enqueue("排队问题", null, new SseEmitter(), () -> {
        });
        UserContext.clear();

        assertTrue(memoryWritten.await(3, TimeUnit.SECONDS));
        verify(memoryService, times(2)).append(anyString(), eq(USER_ID), any());
    }

    @Test
    void 分类器拒答走完整SSE拒绝流并落双消息() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        when(memoryService.append(anyString(), eq(USER_ID), any())).thenReturn("m-q-1", "m-a-1");
        setUserContext();

        chatQueueLimiter.rejectWithTemplate("法国的首都是哪座城市", "conversation-r1", emitter, "校园范围外模板");

        // 事件序=meta→reject→finish(REJECTED)→done，与排队拒绝的既有口径一致
        assertEquals(List.of("meta", "reject", "finish", "done"), emitter.eventNames());
        assertEquals("校园范围外模板", emitter.payloadAs(MessageDelta.class, 1).delta());
        CompletionPayload finish = emitter.payloadAs(CompletionPayload.class, 2);
        assertEquals("m-a-1", finish.messageId());
        assertEquals(ChatMessage.MessageStatus.REJECTED, finish.messageStatus());
        assertEquals("[DONE]", emitter.payload(3));

        // 拒绝问答双双落库：用户消息在前、REJECTED 助手消息在后
        InOrder inOrder = inOrder(memoryService);
        ArgumentCaptor<ChatMessage> appends = ArgumentCaptor.forClass(ChatMessage.class);
        inOrder.verify(memoryService, times(2)).append(eq("conversation-r1"), eq(USER_ID), appends.capture());
        assertEquals("法国的首都是哪座城市", appends.getAllValues().get(0).getContent());
        assertEquals(ChatMessage.Role.USER, appends.getAllValues().get(0).getRole());
        assertEquals("校园范围外模板", appends.getAllValues().get(1).getContent());
        assertEquals(ChatMessage.Role.ASSISTANT, appends.getAllValues().get(1).getRole());
        assertEquals(ChatMessage.MessageStatus.REJECTED, appends.getAllValues().get(1).getMessageStatus());
    }

    @Test
    void 分类器拒答落库失败仍发done不悬挂前端() {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        when(memoryService.append(anyString(), eq(USER_ID), any()))
                .thenThrow(new IllegalStateException("库不可用"));
        setUserContext();

        chatQueueLimiter.rejectWithTemplate("法国的首都是哪座城市", "conversation-r1", emitter, "模板");

        assertEquals(List.of("done"), emitter.eventNames());
    }

    private static void setUserContext() {
        UserContext.set(LoginUser.builder().userId(USER_ID).username("tester").role("user").build());
    }

    private static void prestartSchedulerThreads(FairDistributedRateLimiter limiter) throws Exception {
        Field schedulerField = FairDistributedRateLimiter.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        ScheduledThreadPoolExecutor scheduler = (ScheduledThreadPoolExecutor) schedulerField.get(limiter);
        scheduler.prestartAllCoreThreads();
    }

    /**
     * 测试用 SSE 捕获器：只经公共 API（SseEventBuilder.build/DataWithMediaType.getData）还原事件名与载荷
     */
    static final class RecordingSseEmitter extends SseEmitter {

        private static final java.util.regex.Pattern EVENT_LINE =
                java.util.regex.Pattern.compile("(?m)^event:\\s*(\\S+)\\s*$");

        private final List<String> eventNames = new java.util.ArrayList<>();
        private final List<Object> payloads = new java.util.ArrayList<>();

        RecordingSseEmitter() {
            super(0L);
        }

        @Override
        public synchronized void send(SseEventBuilder builder) {
            String currentEvent = null;
            for (org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType entry
                    : builder.build()) {
                if (entry.getData() instanceof String frame) {
                    java.util.regex.Matcher matcher = EVENT_LINE.matcher(frame);
                    if (matcher.find()) {
                        currentEvent = matcher.group(1);
                    } else if (currentEvent != null && !frame.isBlank()) {
                        eventNames.add(currentEvent);
                        payloads.add(frame);
                        currentEvent = null;
                    }
                } else if (currentEvent != null) {
                    eventNames.add(currentEvent);
                    payloads.add(entry.getData());
                    currentEvent = null;
                }
            }
        }

        @Override
        public synchronized void complete() {
            // 无容器初始化，仅记账
        }

        List<String> eventNames() {
            return eventNames;
        }

        Object payload(int index) {
            return payloads.get(index);
        }

        <T> T payloadAs(Class<T> type, int index) {
            return type.cast(payloads.get(index));
        }
    }
}
