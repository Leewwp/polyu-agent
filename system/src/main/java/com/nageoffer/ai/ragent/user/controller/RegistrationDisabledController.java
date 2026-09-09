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

package com.nageoffer.ai.ragent.user.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册/恢复通道关闭态兜底（U2，判例同 T7/T8）：flag 关（默认）时 RegistrationController
 * 不装配，本兜底接管同路径——端点明确「不可达」（业务拒绝）而非落到系统错误（B000001）。
 * 通道开闭属产品运营状态，不是邮箱注册状态，不适用反枚举口径。
 */
@RestController
@ConditionalOnProperty(name = "ragent.registration.enabled", havingValue = "false", matchIfMissing = true)
public class RegistrationDisabledController {

    private static final String DISABLED_MESSAGE = "注册通道当前未开放";

    @PostMapping("/auth/register")
    public Result<Void> register() {
        throw new ClientException(DISABLED_MESSAGE);
    }

    @PostMapping("/auth/email/verify")
    public Result<Void> verifyEmail() {
        throw new ClientException(DISABLED_MESSAGE);
    }

    @PostMapping("/auth/email/resend")
    public Result<Void> resendVerificationCode() {
        throw new ClientException(DISABLED_MESSAGE);
    }

    @PostMapping("/auth/password/forgot")
    public Result<Void> requestPasswordReset() {
        throw new ClientException(DISABLED_MESSAGE);
    }

    @PostMapping("/auth/password/reset")
    public Result<Void> resetPassword() {
        throw new ClientException(DISABLED_MESSAGE);
    }
}
