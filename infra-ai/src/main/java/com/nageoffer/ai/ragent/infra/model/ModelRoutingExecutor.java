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

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.cancellation.TaskCancellation;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 模型路由执行器
 * 负责在多个模型候选者之间进行调度执行，并提供故障转移（Fallback）和健康检查机制
 *
 * <p>两类异常不走故障转移（#184）：任务取消（TaskCancellation，用户行为）与
 * 预算耗尽（{@link LlmBudgetExhaustedException}，调用方护栏否决）——均不计模型
 * 失败、不触发熔断；其余异常维持原语义：markFailure 后续走下一候选。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRoutingExecutor {

    private final ModelHealthStore healthStore;

    public <C, T> T executeWithFallback(
            ModelCapability capability,
            List<ModelTarget> targets,
            Function<ModelTarget, C> clientResolver,
            ModelCaller<C, T> caller) {
        String label = capability.getDisplayName();
        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null;
        for (ModelTarget target : targets) {
            C client = clientResolver.apply(target);
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}", label, target.candidate().getProvider(), target.id());
                continue;
            }
            ModelHealthStore.CallPermit permit = healthStore.allowCall(target.id());
            if (permit == null) {
                continue;
            }

            try {
                // 逐次发出通知（#184 预算护栏）：观察者可否决本次发出（抛 LlmBudgetExhaustedException）；
                // 未注册观察者的链路（主 RAG/嵌入/重排）零开销直通
                LlmAttemptScope.notifyBeforeAttempt(target);
                T response = caller.call(client, target);
                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                if (TaskCancellation.isCancellation(e)) {
                    // 任务取消不是模型故障：不降级、不累计失败，半开探测只归还占用的名额
                    healthStore.releaseHalfOpenPermit(permit);
                    throw TaskCancellation.asCancellation(e);
                }
                if (e instanceof LlmBudgetExhaustedException budget) {
                    // 预算耗尽不是模型故障（#184 隔离边界）：不 markFailure、不触发熔断、
                    // 不续走 fallback（预算与候选模型无关，换模型照样付费）；
                    // 本次发出被观察者否决、未实际占用半开名额，归还后原样上抛由调用方降级
                    healthStore.releaseHalfOpenPermit(permit);
                    throw budget;
                }
                last = e;
                healthStore.markFailure(target.id());
                log.warn("{} model failed, fallback to next. modelId={}, provider={}", label, target.id(), target.candidate().getProvider(), e);
            }
        }

        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }
}
