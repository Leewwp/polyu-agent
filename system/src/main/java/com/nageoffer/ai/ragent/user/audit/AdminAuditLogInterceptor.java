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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.LoginUser;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 管理操作审计拦截器（doc 15 §2.2.9 / doc 18 U9）：admin 写操作以 logger 记录
 * 谁（username+userId）/ 做了什么（HTTP 方法+URI+查询串）/ 结果（状态码+异常类名），
 * 何时由日志框架时间戳承载——不建审计表。
 *
 * <p>注册面与 admin 角色拦截器同一组路径模式（{@code SaTokenConfig.ADMIN_PATH_PATTERNS}），
 * 顺序在其后（order 25，晚于用户上下文 20）：afterCompletion 反序执行，
 * 本拦截器先于 UserContextInterceptor 清理 ThreadLocal 前读到操作者身份；
 * 仅当请求已通过登录(0)与 admin 角色(5)两道拦截后才会到达这里，非 admin 请求不产生审计行。
 *
 * <p>只记状态变更方法（POST/PUT/DELETE/PATCH），GET/HEAD/OPTIONS 与异步调度跳过；
 * 不记请求体（可能含口令与大文档载荷），路径与查询串已足够回答「做了什么」。
 *
 * @author nageoffer
 */
public class AdminAuditLogInterceptor implements HandlerInterceptor {

    /**
     * 独立 logger 名，运维侧可按名路由/检索审计行（消息另有 [admin-audit] 前缀双保险）
     */
    private static final Logger log = LoggerFactory.getLogger("ADMIN_AUDIT");

    /**
     * 纳入审计的状态变更方法
     */
    private static final Set<String> AUDITED_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull Object handler) {
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        if (shouldAudit(request)) {
            String line = formatAuditLine(request, response, ex);
            if (line != null) {
                log.info(line);
            }
        }
    }

    /**
     * 是否产生审计行：非异步调度 + 状态变更方法
     */
    static boolean shouldAudit(HttpServletRequest request) {
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            return false;
        }
        return AUDITED_METHODS.contains(request.getMethod());
    }

    /**
     * 组装审计行：operator=谁 / action=做了什么 / status(+error)=结果。
     * 操作者取 UserContext（用户上下文拦截器 order 20 填充）；缺失时记 unknown 兜底。
     */
    static String formatAuditLine(HttpServletRequest request, HttpServletResponse response, Exception ex) {
        LoginUser user = UserContext.get();
        String operator = user == null
                ? "unknown"
                : user.getUsername() + "(" + user.getUserId() + ")";
        String query = request.getQueryString();
        String action = request.getMethod() + " " + request.getRequestURI() + (StrUtil.isBlank(query) ? "" : "?" + query);
        StringBuilder line = new StringBuilder()
                .append("[admin-audit] operator=").append(operator)
                .append(" action=\"").append(action).append("\"")
                .append(" status=").append(response.getStatus());
        if (ex != null) {
            line.append(" error=").append(ex.getClass().getSimpleName());
        }
        return line.toString();
    }
}
