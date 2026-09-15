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

import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.Type;

/**
 * 把 {@link IngestionUrlGuard} 挂到 /ingestion/tasks 的 JSON 请求体上。
 *
 * <p>用 RequestBodyAdvice 而不是在控制器里加校验，是为了不改上游的
 * IngestionTaskController —— 该文件属上游仓库，就地修改会在收编上游时冲突。
 * 这里只认参数类型，不认路径，所以将来任何以同类请求体接收文档源的端点都会被覆盖。
 */
@ControllerAdvice
@RequiredArgsConstructor
public class IngestionSourceValidationAdvice extends RequestBodyAdviceAdapter {

    private final IngestionUrlGuard ingestionUrlGuard;

    @Override
    public boolean supports(MethodParameter methodParameter,
                           Type targetType,
                           Class<? extends HttpMessageConverter<?>> converterType) {
        return IngestionTaskCreateRequest.class.isAssignableFrom(methodParameter.getParameterType());
    }

    @Override
    public Object afterBodyRead(Object body,
                                HttpInputMessage inputMessage,
                                MethodParameter parameter,
                                Type targetType,
                                Class<? extends HttpMessageConverter<?>> converterType) {
        if (body instanceof IngestionTaskCreateRequest request) {
            ingestionUrlGuard.validate(request.getSource());
        }
        return body;
    }
}
