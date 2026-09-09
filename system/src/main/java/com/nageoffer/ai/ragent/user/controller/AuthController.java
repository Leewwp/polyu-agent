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

import com.nageoffer.ai.ragent.user.controller.request.AccountDeleteRequest;
import com.nageoffer.ai.ragent.user.controller.request.AccountRestoreRequest;
import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.user.service.AccountLifecycleService;
import com.nageoffer.ai.ragent.user.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器
 * 处理用户登录和登出相关的请求
 */
@RestController
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    private final AccountLifecycleService accountLifecycleService;

    /**
     * 用户登录接口
     */
    @PostMapping("/auth/login")
    public Result<LoginVO> login(@RequestBody LoginRequest requestParam) {
        return Results.success(authService.login(requestParam));
    }

    /**
     * 用户登出接口，清除用户的认证信息和会话
     */
    @PostMapping("/auth/logout")
    public Result<Void> logout() {
        authService.logout();
        return Results.success();
    }

    /**
     * 匿名试用游客登录（T8）：flag 默认关，关闭时返回"匿名试用未开启"
     */
    @PostMapping("/auth/guest")
    public Result<LoginVO> guestLogin() {
        return Results.success(authService.guestLogin());
    }

    /**
     * 自助注销（U2）：登录态 + 密码确认 → 软删 30 天可撤销。不挂注册 flag——
     * 注销权不因注册通道开闭而失效（存量/管理员建号用户同样可注销）
     */
    @PostMapping("/auth/account/delete")
    public Result<Void> deleteAccount(@RequestBody AccountDeleteRequest requestParam) {
        accountLifecycleService.deleteAccount(requestParam);
        return Results.success();
    }

    /**
     * 撤销注销（U2）：冷静期内凭原账号 + 原密码恢复并直接登录
     */
    @PostMapping("/auth/account/restore")
    public Result<LoginVO> restoreAccount(@RequestBody AccountRestoreRequest requestParam) {
        return Results.success(accountLifecycleService.restoreAccount(requestParam));
    }
}
