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

package com.nageoffer.ai.ragent.rag.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.vo.GuestQuotaVO;
import com.nageoffer.ai.ragent.rag.service.AnonymousTrialGuard;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 游客配额状态查询（U11-⑤「配额/游客试用状态可见」的后端信号源）
 *
 * <p>路径在 /auth/** 下（登录拦截器放行面），登录态与角色在这里自查。
 * 只读不消耗额度；非游客返回不受限语义（dailyLimit/remaining 为 null）。
 */
@RestController
@RequiredArgsConstructor
public class GuestQuotaController {

    private static final String ROLE_GUEST = "guest";

    private final AnonymousTrialGuard anonymousTrialGuard;
    private final UserMapper userMapper;

    @GetMapping("/auth/guest/quota")
    public Result<GuestQuotaVO> quota(HttpServletRequest request) {
        Object loginId = StpUtil.getLoginIdDefaultNull();
        if (loginId == null) {
            throw new ClientException("请先登录");
        }
        UserDO user = userMapper.selectById(String.valueOf(loginId));
        if (user == null) {
            throw new ClientException("请先登录");
        }
        LoginUser loginUser = LoginUser.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole())
                .build();
        if (!ROLE_GUEST.equals(user.getRole())) {
            return Results.success(new GuestQuotaVO(user.getRole(), null, null));
        }
        return Results.success(new GuestQuotaVO(
                user.getRole(),
                anonymousTrialGuard.currentDailyLimit(loginUser),
                anonymousTrialGuard.remainingQuota(loginUser, resolveClientIp(request))));
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
    }
}
