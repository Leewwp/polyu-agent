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

/**
 * LLM 调用预算耗尽（#184 资讯预算护栏引入）
 *
 * <p>语义边界：调用方侧的预算护栏在「下一次真实发出前」判定超额并抛出本异常。
 * 它<b>不是模型故障</b>——{@link ModelRoutingExecutor} 对其特判豁免：
 * 不进 ModelHealthStore.markFailure、不触发模型健康熔断、不续走 fallback
 * （预算与具体候选模型无关，换模型照样付费），直接向调用方上抛由业务侧降级。
 *
 * <p>该异常只会由注册了 {@link LlmAttemptScope} 观察者的调用链产生
 * （现行唯一注册方=资讯摘要预算护栏），主 RAG 链路无观察者、永不触发。
 */
public class LlmBudgetExhaustedException extends RuntimeException {

    public LlmBudgetExhaustedException(String message) {
        super(message);
    }
}
