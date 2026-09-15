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

package com.nageoffer.ai.ragent.user.audit;

import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * admin 审计拦截器单元测试：
 * 三类 admin 写操作（知识库/智能体/用户管理）日志行三要素完整（operator/action/status），
 * 读方法与异步调度不产生审计行。
 */
class AdminAuditLogInterceptorTest {

    private final AdminAuditLogInterceptor interceptor = new AdminAuditLogInterceptor();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private HttpServletRequest request(String method, String uri, DispatcherType dispatcherType) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getDispatcherType()).thenReturn(dispatcherType);
        return request;
    }

    private HttpServletResponse response(int status) {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getStatus()).thenReturn(status);
        return response;
    }

    private void loginAsAdmin() {
        UserContext.set(LoginUser.builder()
                .userId("2001523723396308993")
                .username("admin")
                .role("admin")
                .build());
    }

    @Test
    void 三类管理面写操作日志行三要素完整() {
        loginAsAdmin();

        // 知识库：文档直传
        HttpServletRequest upload = request("POST", "/api/ragent/knowledge-base/doc/upload", DispatcherType.REQUEST);
        when(upload.getQueryString()).thenReturn(null);
        String uploadLine = AdminAuditLogInterceptor.formatAuditLine(upload, response(200), null);
        assertTrue(uploadLine.contains("operator=admin(2001523723396308993)"), uploadLine);
        assertTrue(uploadLine.contains("action=\"POST /api/ragent/knowledge-base/doc/upload\""), uploadLine);
        assertTrue(uploadLine.contains("status=200"), uploadLine);

        // 智能体：配置更新
        HttpServletRequest update = request("PUT", "/api/ragent/agents/2096913519017512960", DispatcherType.REQUEST);
        when(update.getQueryString()).thenReturn(null);
        String updateLine = AdminAuditLogInterceptor.formatAuditLine(update, response(200), null);
        assertTrue(updateLine.contains("operator=admin("), updateLine);
        assertTrue(updateLine.contains("action=\"PUT /api/ragent/agents/2096913519017512960\""), updateLine);
        assertTrue(updateLine.contains("status=200"), updateLine);

        // 用户管理：删号（带查询串与异常形态）
        HttpServletRequest delete = request("DELETE", "/api/ragent/users/2", DispatcherType.REQUEST);
        when(delete.getQueryString()).thenReturn("confirm=true");
        String deleteLine = AdminAuditLogInterceptor.formatAuditLine(delete, response(500),
                new IllegalStateException("cascade"));
        assertTrue(deleteLine.contains("action=\"DELETE /api/ragent/users/2?confirm=true\""), deleteLine);
        assertTrue(deleteLine.contains("status=500"), deleteLine);
        assertTrue(deleteLine.contains("error=IllegalStateException"), deleteLine);
    }

    @Test
    void 上下文缺失时操作者记unknown兜底() {
        HttpServletRequest request = request("POST", "/api/ragent/rag/settings", DispatcherType.REQUEST);
        when(request.getQueryString()).thenReturn(null);
        String line = AdminAuditLogInterceptor.formatAuditLine(request, response(200), null);
        assertTrue(line.contains("operator=unknown"), line);
    }

    @Test
    void 只有状态变更方法产生审计行() {
        assertTrue(AdminAuditLogInterceptor.shouldAudit(request("POST", "/api/ragent/users", DispatcherType.REQUEST)));
        assertTrue(AdminAuditLogInterceptor.shouldAudit(request("PUT", "/api/ragent/users/1", DispatcherType.REQUEST)));
        assertTrue(AdminAuditLogInterceptor.shouldAudit(request("DELETE", "/api/ragent/users/1", DispatcherType.REQUEST)));
        assertTrue(AdminAuditLogInterceptor.shouldAudit(request("PATCH", "/api/ragent/users/1", DispatcherType.REQUEST)));
        assertFalse(AdminAuditLogInterceptor.shouldAudit(request("GET", "/api/ragent/users", DispatcherType.REQUEST)));
        assertFalse(AdminAuditLogInterceptor.shouldAudit(request("HEAD", "/api/ragent/users", DispatcherType.REQUEST)));
        assertFalse(AdminAuditLogInterceptor.shouldAudit(request("OPTIONS", "/api/ragent/users", DispatcherType.REQUEST)));
    }

    @Test
    void 异步调度请求不产生审计行() {
        assertFalse(AdminAuditLogInterceptor.shouldAudit(request("POST", "/api/ragent/knowledge-base/doc/upload", DispatcherType.ASYNC)));
    }

    @Test
    void 注册面与管理面角色拦截共用同一份路径清单() {
        // 防漂移：U9 审计面必须与 S9 角色面同宽（SaTokenConfig.ADMIN_PATH_PATTERNS 单一来源）
        assertEquals(15, com.nageoffer.ai.ragent.user.config.SaTokenConfig.ADMIN_PATH_PATTERNS.length);
        // 采集管道/任务属管理面：此前漏在清单外，任一登录用户即可经 /ingestion/tasks 驱动
        // 服务端抓取任意 URL。存在性断言比长度断言更能钉住意图
        assertTrue(java.util.Arrays.asList(com.nageoffer.ai.ragent.user.config.SaTokenConfig.ADMIN_PATH_PATTERNS)
                .contains("/ingestion/**"));
    }
}
