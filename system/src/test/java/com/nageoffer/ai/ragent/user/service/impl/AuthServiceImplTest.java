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
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.controller.request.LoginRequest;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import com.nageoffer.ai.ragent.user.security.LoginRateLimiter;
import com.nageoffer.ai.ragent.user.security.PasswordCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 登录服务单元测试：哈希校验闭环、存量明文透明升级、限速前置、错误口令统一报错
 */
class AuthServiceImplTest {

    private UserMapper userMapper;
    private LoginRateLimiter loginRateLimiter;
    private AuthServiceImpl authService;
    private MockedStatic<StpUtil> stpUtil;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        loginRateLimiter = mock(LoginRateLimiter.class);
        authService = new AuthServiceImpl(userMapper, new PasswordCodec(), loginRateLimiter);
        stpUtil = mockStatic(StpUtil.class);
        stpUtil.when(StpUtil::getTokenValue).thenReturn("token");
    }

    @AfterEach
    void tearDown() {
        stpUtil.close();
    }

    private UserDO user(String password) {
        return UserDO.builder().id("1").username("admin").password(password).role("admin").build();
    }

    @Test
    void loginSucceedsWithHashedPassword() {
        String hash = new PasswordCodec().encode("right-pass");
        when(userMapper.selectActiveByUsernameOrEmail("admin")).thenReturn(user(hash));
        var vo = authService.login(req("admin", "right-pass"));
        assertEquals("admin", vo.getRole());
        verify(userMapper, never()).updateById(any(UserDO.class));
    }

    @Test
    void legacyPlaintextLoginUpgradesToHash() {
        when(userMapper.selectActiveByUsernameOrEmail("admin")).thenReturn(user("right-pass"));
        authService.login(req("admin", "right-pass"));
        var captor = org.mockito.ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).updateById(captor.capture());
        assertTrue(captor.getValue().getPassword().startsWith("{bcrypt}"));
    }

    @Test
    void wrongPasswordFailsWithoutUpgrade() {
        when(userMapper.selectActiveByUsernameOrEmail("admin")).thenReturn(user("right-pass"));
        assertThrows(ClientException.class, () -> authService.login(req("admin", "wrong")));
        verify(userMapper, never()).updateById(any(UserDO.class));
    }

    @Test
    void loginAcceptsEmailAsKey() {
        // 注册用户 username=email：双键查询命中同一路径
        when(userMapper.selectActiveByUsernameOrEmail("u2@example.com")).thenReturn(
                UserDO.builder().id("2").username("u2@example.com")
                        .password(new PasswordCodec().encode("right-pass"))
                        .role("user").email("u2@example.com").emailVerified(1).build());
        var vo = authService.login(req("u2@example.com", "right-pass"));
        assertEquals("user", vo.getRole());
    }

    @Test
    void loginBlocksUnverifiedEmailAccount() {
        when(userMapper.selectActiveByUsernameOrEmail("u2@example.com")).thenReturn(
                UserDO.builder().id("2").username("u2@example.com")
                        .password(new PasswordCodec().encode("right-pass"))
                        .role("user").email("u2@example.com").emailVerified(0).build());
        ClientException ex = assertThrows(ClientException.class,
                () -> authService.login(req("u2@example.com", "right-pass")));
        assertTrue(ex.getMessage().contains("邮箱未验证"));
    }

    @Test
    void loginRejectsOverlongPasswordWithGenericError() {
        // 密码输入上限 64 字符（doc 15 §2.2.1）：超限与凭据错误同文案，不另开口径
        ClientException ex = assertThrows(ClientException.class,
                () -> authService.login(req("admin", "x".repeat(65))));
        assertEquals("用户名或密码错误", ex.getMessage());
        verify(loginRateLimiter, never()).acquire(anyString(), anyString());
    }

    @Test
    void rateLimitedRequestNeverTouchesCredentials() {
        org.mockito.Mockito.doThrow(new ClientException("登录尝试过于频繁，请稍后再试"))
                .when(loginRateLimiter).acquire(anyString(), anyString());
        assertThrows(ClientException.class, () -> authService.login(req("admin", "whatever")));
        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void blankPasswordRejectedBeforeRateLimiter() {
        assertThrows(ClientException.class, () -> authService.login(req("admin", " ")));
        verify(loginRateLimiter, never()).acquire(anyString(), anyString());
    }

    @Test
    void guestLoginDisabledByDefault() {
        // flag 默认关（application.yaml ragent.anonymous.enabled=false）
        assertThrows(ClientException.class, () -> authService.guestLogin());
        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any(UserDO.class));
    }

    @Test
    void guestLoginMintsGuestAccountAndSession() {
        org.springframework.test.util.ReflectionTestUtils.setField(authService, "anonymousEnabled", true);
        stpUtil.when(StpUtil::getLoginIdDefaultNull).thenReturn(null);
        // 模拟 MyBatis-Plus ASSIGN_ID 主键回填
        when(userMapper.insert(org.mockito.ArgumentMatchers.any(UserDO.class))).thenAnswer(inv -> {
            inv.getArgument(0, UserDO.class).setId("900");
            return 1;
        });
        var vo = authService.guestLogin();
        var captor = org.mockito.ArgumentCaptor.forClass(UserDO.class);
        verify(userMapper).insert(captor.capture());
        UserDO guest = captor.getValue();
        assertTrue(guest.getUsername().startsWith("guest-"));
        assertEquals("guest", guest.getRole());
        assertTrue(guest.getPassword().startsWith("{bcrypt}"));
        stpUtil.verify(() -> StpUtil.login(org.mockito.ArgumentMatchers.anyString()));
        assertEquals("guest", vo.getRole());
        assertEquals("token", vo.getToken());
    }

    @Test
    void guestLoginReusesExistingGuestSession() {
        org.springframework.test.util.ReflectionTestUtils.setField(authService, "anonymousEnabled", true);
        stpUtil.when(StpUtil::getLoginIdDefaultNull).thenReturn("1");
        when(userMapper.selectById("1")).thenReturn(
                UserDO.builder().id("1").username("admin").password("{bcrypt}x").role("guest").build());
        var vo = authService.guestLogin();
        assertEquals("guest", vo.getRole());
        verify(userMapper, never()).insert(org.mockito.ArgumentMatchers.any(UserDO.class));
    }

    private LoginRequest req(String username, String password) {
        LoginRequest r = new LoginRequest();
        r.setUsername(username);
        r.setPassword(password);
        return r;
    }
}
