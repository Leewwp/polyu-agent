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

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import com.nageoffer.ai.ragent.user.audit.AdminAuditLogInterceptor;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * SaToken 配置类
 * 配置登录拦截和用户上下文拦截器
 */
@Configuration
@RequiredArgsConstructor
public class SaTokenConfig implements WebMvcConfigurer {

    /**
     * 用户上下文拦截器
     */
    private final UserContextInterceptor userContextInterceptor;

    /**
     * 拦截器全局顺序：登录(0) → 管理面角色(5) → 演示只读(10，由 RagentWebMvcConfiguration 注册) → 用户上下文(20) → admin 审计(25)
     */
    public static final int ORDER_LOGIN = 0;
    public static final int ORDER_ADMIN_ROLE = 5;
    public static final int ORDER_DEMO_MODE = 10;
    public static final int ORDER_USER_CONTEXT = 20;
    public static final int ORDER_ADMIN_AUDIT = 25;

    /**
     * 管理面路径模式：admin 角色拦截与 U9 admin 审计共用同一份清单，防两份列表漂移
     */
    public static final String[] ADMIN_PATH_PATTERNS = {
            "/knowledge-base/**",
            // 采集管道/采集任务（2026-09-10 安全复核补漏）：前端入口在 pages/admin/ingestion，
            // 属管理面；此前漏在本清单外，任一登录用户即可驱动 HttpUrlFetcher 抓取任意 URL
            "/ingestion/**",
            "/agents/**",
            "/agent-skills/**",
            "/intent-tree",
            "/intent-tree/**",
            "/mappings",
            "/mappings/**",
            "/admin/**",
            "/rag/settings",
            "/rag/traces/**",
            "/rag/eval",
            "/rag/eval/**",
            "/biz-change-logs/**",
            "/users/**"};

    /**
     * 添加拦截器配置
     *
     * @param registry 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注册 SaToken 登录拦截器
        registry.addInterceptor(new SaInterceptor(handler -> {
                    // 异步调度请求跳过登录检查（SSE 完成回调会触发 asyncDispatch，此时 SaToken 上下文已丢失）
                    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
                    if (attrs != null) {
                        HttpServletRequest request = attrs.getRequest();
                        // 判断是否为异步调度请求，如果是则跳过登录检查
                        if (request.getDispatcherType() == DispatcherType.ASYNC) {
                            return;
                        }
                        // 预检请求直接放行，避免 CORS 被拦截
                        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
                            return;
                        }
                    }
                    // 执行登录检查
                    StpUtil.checkLogin();
                }))
                // 拦截所有路径
                .addPathPatterns("/**")
                // 排除认证相关路径、错误页面与公开只读面
                // （/public/share/**：E-1 公开答案分享匿名读，flag 默认关时端点本身 404，T7 2026-09-08；
                //  /public/news/**：U12-A 资讯流公开读，资讯浏览永久免登录，flag 默认关时 404 兜底，T4 2026-09-10）
                .excludePathPatterns("/auth/**", "/public/share/**", "/public/news/**", "/error")
                .order(ORDER_LOGIN);

        // 管理面角色拦截：登录态之上再要求 admin 角色（S9：服务端补齐 /admin 边界，
        // 覆盖知识库/采集/智能体/意图树/映射/设置/追踪/审计/用户管理；用户侧接口不受影响。
        // 2026-09-07 安全复核补充项：/rag/eval 效果评测端点可触发全链路检索，纳入 admin）
        // 注意：清单为手写维护，新增管理面 controller 时须同步补入，否则默认落在登录态即可访问
        registry.addInterceptor(new SaInterceptor(handler -> StpUtil.checkRole("admin")))
                .addPathPatterns(ADMIN_PATH_PATTERNS)
                .order(ORDER_ADMIN_ROLE);

        // admin 写操作审计拦截器（doc 15 §2.2.9 / doc 18 U9）：与角色拦截同一组路径，
        // order 25 晚于用户上下文(20)——afterCompletion 反序执行，先于 ThreadLocal 清理读到操作者；
        // 只记 POST/PUT/DELETE/PATCH，非写请求与异步调度零成本跳过
        registry.addInterceptor(new AdminAuditLogInterceptor())
                .addPathPatterns(ADMIN_PATH_PATTERNS)
                .order(ORDER_ADMIN_AUDIT);

        // 注册用户上下文拦截器
        registry.addInterceptor(userContextInterceptor)
                .addPathPatterns("/**")
                // 排除与登录拦截器同口径：认证路径、错误页面、公开只读面（含 /public/news/**，U12-A）
                // （公开面无登录态，UserContextInterceptor 的 getLoginIdAsString 会抛未登录；T7 2026-09-08）
                .excludePathPatterns("/auth/**", "/public/share/**", "/public/news/**", "/error")
                .order(ORDER_USER_CONTEXT);
    }
}
