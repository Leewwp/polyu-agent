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
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.controller.request.EmailResendRequest;
import com.nageoffer.ai.ragent.user.controller.request.EmailVerifyRequest;
import com.nageoffer.ai.ragent.user.controller.request.ForgotPasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.RegisterRequest;
import com.nageoffer.ai.ragent.user.controller.request.ResetPasswordRequest;
import com.nageoffer.ai.ragent.user.service.AccountLifecycleService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自助注册与密码恢复控制器（U2）
 *
 * <p>feature flag：ragent.registration.enabled=false（默认关）时整个 Bean 不装配，
 * 由 {@link RegistrationDisabledController} 兜底同路径。开启与 T10 SMTP 凭据同窗
 * （开放注册窗，doc 15 §2.2.4）。
 */
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "ragent.registration.enabled", havingValue = "true")
public class RegistrationController {

    private final AccountLifecycleService accountLifecycleService;

    /**
     * 自助注册：受理口径统一（邮箱已注册时静默受理），是否发码只有邮箱主人可分辨
     */
    @PostMapping("/auth/register")
    public Result<Void> register(@RequestBody RegisterRequest requestParam) {
        accountLifecycleService.register(requestParam);
        return Results.success();
    }

    /**
     * 邮箱验证：提交注册验证码
     */
    @PostMapping("/auth/email/verify")
    public Result<Void> verifyEmail(@RequestBody EmailVerifyRequest requestParam) {
        accountLifecycleService.verifyEmail(requestParam);
        return Results.success();
    }

    /**
     * 重发邮箱验证码
     */
    @PostMapping("/auth/email/resend")
    public Result<Void> resendVerificationCode(@RequestBody EmailResendRequest requestParam) {
        accountLifecycleService.resendVerificationCode(requestParam);
        return Results.success();
    }

    /**
     * 忘记密码：受理口径统一，重置码只有邮箱主人可收
     */
    @PostMapping("/auth/password/forgot")
    public Result<Void> requestPasswordReset(@RequestBody ForgotPasswordRequest requestParam) {
        accountLifecycleService.requestPasswordReset(requestParam);
        return Results.success();
    }

    /**
     * 密码重置：重置码 + 新密码
     */
    @PostMapping("/auth/password/reset")
    public Result<Void> resetPassword(@RequestBody ResetPasswordRequest requestParam) {
        accountLifecycleService.resetPassword(requestParam);
        return Results.success();
    }
}
