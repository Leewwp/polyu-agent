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

package com.nageoffer.ai.ragent.agent.audit;

import com.nageoffer.ai.ragent.agent.config.AgentProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

/**
 * 审计取数点装配（#486）：agent.audit.enabled 启动期绑定到静态捕获闸
 * （AgentToolBodyTracer 同层钩子无 Spring 上下文，沿其 serializer 的静态配置模式）。
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class AgentAuditConfiguration {

    private final AgentProperties properties;

    @PostConstruct
    void bindCaptureGate() {
        AgentToolInputAuditCapture.configure(
                properties.getAudit() != null && properties.getAudit().isEnabled(), null);
    }
}
