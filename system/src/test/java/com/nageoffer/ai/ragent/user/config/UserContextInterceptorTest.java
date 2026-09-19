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

package com.nageoffer.ai.ragent.user.config;

import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.user.dao.entity.UserDO;
import com.nageoffer.ai.ragent.user.dao.mapper.UserMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 用户上下文拦截器单元测试：上下文装配、已删用户统一错误（O1/L4）、请求完成清理
 */
class UserContextInterceptorTest {

    private UserMapper userMapper;
    private UserContextInterceptor interceptor;
    private MockedStatic<StpUtil> stpUtil;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        interceptor = new UserContextInterceptor(userMapper);
        stpUtil = mockStatic(StpUtil.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(request.getDispatcherType()).thenReturn(DispatcherType.REQUEST);
        when(request.getMethod()).thenReturn("POST");
    }

    @AfterEach
    void tearDown() {
        stpUtil.close();
        UserContext.clear();
    }

    @Test
    void populatesUserContextFromPersistedUser() {
        stpUtil.when(StpUtil::getLoginIdAsString).thenReturn("100");
        when(userMapper.selectById("100")).thenReturn(UserDO.builder()
                .id("100").username("u@example.com").role("user").build());

        interceptor.preHandle(request, response, new Object());

        assertEquals("100", UserContext.getUserId());
        assertEquals("u@example.com", UserContext.get().getUsername());
    }

    @Test
    void deletedUserWithValidTokenGetsUnifiedErrorInsteadOf500() {
        // O1/L4：软删/硬删后 token 未过期的小窗口——统一「登录态已失效」客户端异常（全局处理器转标准响应），不再是 NPE 500
        stpUtil.when(StpUtil::getLoginIdAsString).thenReturn("100");
        when(userMapper.selectById("100")).thenReturn(null);

        ClientException ex = assertThrows(ClientException.class,
                () -> interceptor.preHandle(request, response, new Object()));

        assertEquals("登录态已失效，请重新登录", ex.getMessage());
        assertNull(UserContext.get());
    }

    @Test
    void afterCompletionClearsContext() {
        UserContext.set(com.nageoffer.ai.ragent.framework.context.LoginUser.builder()
                .userId("100").username("u@example.com").role("user").build());
        interceptor.afterCompletion(request, response, new Object(), null);
        assertNull(UserContext.get());
    }
}
