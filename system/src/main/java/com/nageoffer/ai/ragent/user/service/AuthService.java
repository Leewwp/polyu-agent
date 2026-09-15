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

import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;

public interface AuthService {

    LoginVO login(LoginRequest requestParam);

    void logout();

    /**
     * 匿名试用游客登录
     *
     * <p>feature flag：ragent.anonymous.enabled（默认 false，关闭时直接拒绝）。
     * 语义：为未登录访客铸造一次性 guest 账号并签发会话；已有 guest 会话则复用；
     * 问题配额由 rag 侧 AnonymousTrialGuard 按「游客身份+IP」每日原子扣减。
     */
    LoginVO guestLogin();
}
