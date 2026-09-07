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

package com.nageoffer.ai.ragent.user.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.controller.vo.LoginVO;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.security.LoginRateLimiter;
import com.nageoffer.ai.ragent.user.security.PasswordCodec;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String DEFAULT_AVATAR_URL = "https://avatars.githubusercontent.com/u/583231?v=4";

    private final UserMapper userMapper;
    private final PasswordCodec passwordCodec;
    private final LoginRateLimiter loginRateLimiter;

    @Override
    public LoginVO login(LoginRequest requestParam) {
        String username = requestParam.getUsername();
        String password = requestParam.getPassword();
        if (StrUtil.isBlank(username) || StrUtil.isBlank(password)) {
            throw new ClientException("用户名或密码不能为空");
        }
        loginRateLimiter.acquire(resolveClientIp(), username);
        UserDO user = findByUsername(username);
        if (user == null || !passwordCodec.matches(password, user.getPassword())) {
            throw new ClientException("用户名或密码错误");
        }
        // 存量明文密码：校验通过后立即升级为哈希（透明收敛，不需要用户操作）
        if (passwordCodec.needsUpgrade(user.getPassword())) {
            user.setPassword(passwordCodec.encode(password));
            userMapper.updateById(user);
        }
        if (user.getId() == null) {
            throw new ClientException("用户信息异常");
        }
        String loginId = user.getId().toString();
        StpUtil.login(loginId);
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        return new LoginVO(loginId, user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    @Override
    public void logout() {
        StpUtil.logout();
    }

    /**
     * guest 角色标记：与 admin/user 并列；管理面角色拦截器只认 admin，guest 等同普通用户权限面
     */
    private static final String ROLE_GUEST = "guest";

    /**
     * 匿名试用开关（T8）：默认 false；启用与额度终值属维护者门（doc 13 §8.2/§16）
     */
    @Value("${ragent.anonymous.enabled:false}")
    private boolean anonymousEnabled;

    @Override
    public LoginVO guestLogin() {
        if (!anonymousEnabled) {
            throw new ClientException("匿名试用未开启");
        }
        // 已登录且是 guest：复用当前会话，不重复铸造游客账号
        Object existingLoginIdObj = StpUtil.getLoginIdDefaultNull();
        if (existingLoginIdObj != null) {
            UserDO current = userMapper.selectById(String.valueOf(existingLoginIdObj));
            if (current != null && ROLE_GUEST.equals(current.getRole())) {
                return buildLoginVO(current);
            }
        }
        // 游客铸造限速：按 IP 复用登录限速器（固定窗口），防批量刷 guest 账号
        loginRateLimiter.acquire(resolveClientIp(), "guest-trial");
        UserDO guest = UserDO.builder()
                .username("guest-" + IdUtil.fastSimpleUUID().substring(0, 12))
                // 随机双 UUID 加密落库：guest 不走密码登录，此值仅满足非空约束
                .password(passwordCodec.encode(IdUtil.fastSimpleUUID() + IdUtil.fastSimpleUUID()))
                .role(ROLE_GUEST)
                .build();
        userMapper.insert(guest);
        if (guest.getId() == null) {
            throw new ClientException("游客账号创建失败");
        }
        StpUtil.login(guest.getId().toString());
        return buildLoginVO(guest);
    }

    private LoginVO buildLoginVO(UserDO user) {
        String avatar = StrUtil.isBlank(user.getAvatar()) ? DEFAULT_AVATAR_URL : user.getAvatar();
        return new LoginVO(user.getId().toString(), user.getRole(), StpUtil.getTokenValue(), avatar);
    }

    private UserDO findByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        return userMapper.selectOne(
                Wrappers.lambdaQuery(UserDO.class)
                        .eq(UserDO::getUsername, username)
                        .eq(UserDO::getDeleted, 0)
        );
    }

    private String resolveClientIp() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return "unknown";
        }
        HttpServletRequest request = attrs.getRequest();
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        return StringUtils.hasText(realIp) ? realIp : request.getRemoteAddr();
    }
}
