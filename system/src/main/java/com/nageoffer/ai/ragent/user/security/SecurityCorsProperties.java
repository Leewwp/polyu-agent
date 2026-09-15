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

package com.nageoffer.ai.ragent.user.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CORS 与 Origin 校验共用配置。
 *
 * <p>默认只放行生产域名（锁单 origin）——本地开发靠 application-local.yaml 追加
 * vite dev 档（http://localhost:5173 等），不进仓库、生产零额外暴露面。
 * 环境覆盖名（relaxed binding）：RAGENT_SECURITY_CORS_ALLOWEDORIGINS。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "ragent.security.cors")
public class SecurityCorsProperties {

    /**
     * 允许的请求来源（scheme://host[:port] 精确匹配）。生产部署为同源架构，
     * 该清单只服务跨域场景与 Origin/Referer 校验兜底。
     */
    private List<String> allowedOrigins = List.of("https://polyuguide.com");
}
