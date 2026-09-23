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

package com.nageoffer.ai.ragent.rag.security;

import com.nageoffer.ai.ragent.rag.config.FetchLimits;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.knowledge.enums.SourceType;
import com.nageoffer.ai.ragent.rag.controller.request.DocumentSourceRequest;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * multipart 上传入口的文档源守卫（补齐安全审查发现的第三缺口）。
 *
 * <p>{@code POST /knowledge-base/{kb-id}/docs/upload} 用 {@code @ModelAttribute} 绑定
 * {@code sourceType/sourceLocation} 表单字段，不经 {@link IngestionSourceValidationAdvice}
 * 的 RequestBodyAdvice 钩子——本 Filter 补齐该第二入口，复用 {@link IngestionUrlGuard}
 * 的同一套校验（scheme/userinfo/回环/RFC1918/元数据段/凭证头），零上游文件改动。
 *
 * <p>仅拦 multipart/form-data 的该端点；其余请求（含同路径的 urlencoded——端点
 * {@code consumes} 约束下 Spring 直接 415，到不了抓取器）原样放行。
 *
 * <p>响应形态与 JSON 路径的守卫拒绝保持一致：HTTP 200 + Result 包裹（ClientException
 * 在 GlobalExceptionHandler 的既有语义）。在 Filter 内读 multipart 参数会提前触发容器
 * 解析，大小超限原本由 Spring 包装成 MaxUploadSizeExceededException 进全局处理器——
 * 此处捕获后按同一文案复刻，行为不回归。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultipartSourceGuardFilter extends OncePerRequestFilter {

    private static final Pattern UPLOAD_PATH = Pattern.compile("^/knowledge-base/[^/]+/docs/upload$");

    private static final String MULTIPART_PREFIX = "multipart/form-data";

    private final IngestionUrlGuard ingestionUrlGuard;

    /**
     * 上传=抓取同口径单点（issue #125）：超限消息文案取自 FetchLimits
     */
    private final FetchLimits fetchLimits;

    @Value("${spring.servlet.multipart.max-request-size:100MB}")
    private String maxRequestSize;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.toLowerCase().startsWith(MULTIPART_PREFIX)) {
            return true;
        }
        return !UPLOAD_PATH.matcher(request.getServletPath()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String sourceType;
        String sourceLocation;
        try {
            sourceType = request.getParameter("sourceType");
            sourceLocation = request.getParameter("sourceLocation");
        } catch (IllegalStateException parseFailure) {
            // Filter 层读参数提前触发了容器 multipart 解析：超限异常在此浮现，
            // 复刻 GlobalExceptionHandler.maxUploadSizeExceededException 的同一响应
            log.warn("[{}] {} [upload] 文件上传大小超限: {}",
                    request.getMethod(), request.getRequestURI(), parseFailure.getMessage());
            Throwable cause = parseFailure.getCause();
            String message = cause instanceof FileSizeLimitExceededException
                    ? "上传文件大小超过限制，单个文件最大允许 " + fetchLimits.maxFileSizeDisplay()
                    : "上传请求大小超过限制，单次请求最大允许 " + maxRequestSize;
            writeRejection(response, message);
            return;
        }
        if (SourceType.fromValue(sourceType) == SourceType.URL) {
            DocumentSourceRequest source = new DocumentSourceRequest();
            source.setType(com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType.URL);
            source.setLocation(sourceLocation);
            try {
                ingestionUrlGuard.validate(source);
            } catch (ClientException reject) {
                log.warn("[{}] {} [url-guard] multipart 上传源被拒: {}",
                        request.getMethod(), request.getRequestURI(), reject.getMessage());
                writeRejection(response, reject.getMessage());
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * 与 GlobalExceptionHandler 的业务拒绝形态一致（HTTP 200 + Result 包裹）。
     * message 全部来自守卫常量文案，无请求方输入回显，无需 JSON 转义。
     */
    private void writeRejection(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"code\":\"A000001\",\"message\":\"" + message + "\",\"data\":null,\"requestId\":null}");
    }
}
