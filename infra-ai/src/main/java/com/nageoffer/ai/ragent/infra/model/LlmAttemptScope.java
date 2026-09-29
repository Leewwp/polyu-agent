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

package com.nageoffer.ai.ragent.infra.model;

import java.util.function.Supplier;

/**
 * LLM 逐次调用观察域（#184 资讯预算护栏引入）
 *
 * <p>调用方在发起一次<b>逻辑调用</b>（如资讯摘要的一次 chat）前注册
 * {@link AttemptObserver}，{@link ModelRoutingExecutor} 在<b>每次真实发出</b>
 * （含 fallback 换候选与调用方自身重试）前回调观察者。观察者可借抛出
 * {@link LlmBudgetExhaustedException} 否决该次发出（预算护栏口径）。
 *
 * <p>实现为 ThreadLocal：路由执行器与底层客户端均在调用线程上同步执行，
 * 域随逻辑调用开合，嵌套注册时内层退出恢复外层。未注册观察者的链路
 * （主 RAG、嵌入、重排等）零开销直通。
 */
public final class LlmAttemptScope {

    /**
     * 逐次发出观察者：抛 {@link LlmBudgetExhaustedException} 可否决该次发出
     */
    @FunctionalInterface
    public interface AttemptObserver {

        /**
         * 每次真实发出前回调（fallback 换候选、重试各计一次）
         *
         * @param target 即将调用的目标模型
         */
        void beforeAttempt(ModelTarget target);
    }

    private static final ThreadLocal<AttemptObserver> CURRENT = new ThreadLocal<>();

    private LlmAttemptScope() {
    }

    /**
     * 在观察域内执行一次逻辑调用，退出时恢复外层观察者
     */
    public static <T> T callWithin(AttemptObserver observer, Supplier<T> action) {
        AttemptObserver previous = CURRENT.get();
        CURRENT.set(observer);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    /**
     * 逐次发出前通知（无观察者时零开销直通）；由 ModelRoutingExecutor 在每次
     * 真实发出前调用，观察者抛出的异常原样向调用链上抛
     */
    public static void notifyBeforeAttempt(ModelTarget target) {
        AttemptObserver observer = CURRENT.get();
        if (observer != null) {
            observer.beforeAttempt(target);
        }
    }
}
