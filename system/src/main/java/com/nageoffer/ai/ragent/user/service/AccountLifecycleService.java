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

package com.nageoffer.ai.ragent.user.service;

import com.nageoffer.ai.ragent.user.controller.request.AccountDeleteRequest;
import com.nageoffer.ai.ragent.user.controller.request.AccountRestoreRequest;
import com.nageoffer.ai.ragent.user.controller.request.EmailResendRequest;
import com.nageoffer.ai.ragent.user.controller.request.EmailVerifyRequest;
import com.nageoffer.ai.ragent.user.controller.request.ForgotPasswordRequest;
import com.nageoffer.ai.ragent.user.controller.request.RegisterRequest;
import com.nageoffer.ai.ragent.user.controller.request.ResetPasswordRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;

/**
 * 账号生命周期服务：注册/邮箱验证/密码重置/自助注销与恢复
 *
 * <p>错误口径纪律：注册、验证码重发、忘记密码对「邮箱是否已注册」一律
 * 返回相同的受理成功语义，不发码不建号；只有持有有效验证码的一方能看到状态变化。
 */
public interface AccountLifecycleService {

    /**
     * 自助注册：邮箱可用则建号（未验证态）并发送验证码；邮箱已注册则静默受理（不泄漏）
     */
    void register(RegisterRequest requestParam);

    /**
     * 邮箱验证：通过后置 email_verified=1（幂等）
     */
    void verifyEmail(EmailVerifyRequest requestParam);

    /**
     * 重发邮箱验证码：仅对「已注册且未验证」的邮箱发码，其余静默受理
     */
    void resendVerificationCode(EmailResendRequest requestParam);

    /**
     * 忘记密码：仅对「已注册且已验证」的邮箱发送重置码，其余静默受理
     */
    void requestPasswordReset(ForgotPasswordRequest requestParam);

    /**
     * 密码重置：重置码 + 新密码
     */
    void resetPassword(ResetPasswordRequest requestParam);

    /**
     * 自助注销（需登录）：密码确认 → 软删进入 30 天可撤销冷静期并下线当前会话
     */
    void deleteAccount(AccountDeleteRequest requestParam);

    /**
     * 撤销注销：冷静期内凭原账号（用户名或邮箱）+ 原密码恢复并直接登录
     */
    LoginVO restoreAccount(AccountRestoreRequest requestParam);
}
