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

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.ingestion.controller.request.IngestionTaskCreateRequest;
import com.nageoffer.ai.ragent.ingestion.domain.enums.SourceType;
import com.nageoffer.ai.ragent.rag.controller.request.DocumentSourceRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 钩子装配面的经验：supports 必须只对摄取任务请求体生效，
 * 否则要么漏校验（返回 false）要么误伤其他端点（对无关类型返回 true）。
 */
class IngestionSourceValidationAdviceTest {

    private final IngestionUrlGuard guard = new IngestionUrlGuard(false);
    private final IngestionSourceValidationAdvice advice = new IngestionSourceValidationAdvice(guard);

    @SuppressWarnings("unused")
    private void ingestSample(IngestionTaskCreateRequest request) {
    }

    @SuppressWarnings("unused")
    private void unrelatedSample(String payload) {
    }

    private static MethodParameter firstParameterOf(String name, Class<?> parameterType) throws NoSuchMethodException {
        Method method = IngestionSourceValidationAdviceTest.class.getDeclaredMethod(name, parameterType);
        return new MethodParameter(method, 0);
    }

    @Test
    void supportsIngestionTaskCreateRequestOnly() throws NoSuchMethodException {
        assertThat(advice.supports(firstParameterOf("ingestSample", IngestionTaskCreateRequest.class), null, null))
                .isTrue();
        assertThat(advice.supports(firstParameterOf("unrelatedSample", String.class), null, null))
                .isFalse();
    }

    @Test
    void afterBodyReadRejectsInternalTarget() {
        IngestionTaskCreateRequest request = new IngestionTaskCreateRequest();
        DocumentSourceRequest source = new DocumentSourceRequest();
        source.setType(SourceType.URL);
        source.setLocation("http://169.254.169.254/latest/meta-data/");
        request.setSource(source);

        assertThatThrownBy(() -> advice.afterBodyRead(request, null, null, null, null))
                .isInstanceOf(ClientException.class)
                .hasMessageContaining("内网或保留地址");
    }

    @Test
    void afterBodyReadPassesThroughValidRequestAndReturnsBody() {
        IngestionTaskCreateRequest request = new IngestionTaskCreateRequest();
        DocumentSourceRequest source = new DocumentSourceRequest();
        source.setType(SourceType.URL);
        source.setLocation("https://1.1.1.1/doc.pdf");
        request.setSource(source);

        assertThatCode(() -> assertThat(advice.afterBodyRead(request, null, null, null, null)).isSameAs(request))
                .doesNotThrowAnyException();
    }

    @Test
    void afterBodyReadToleratesMissingSource() {
        IngestionTaskCreateRequest request = new IngestionTaskCreateRequest();
        assertThatCode(() -> advice.afterBodyRead(request, null, null, null, null)).doesNotThrowAnyException();
    }
}
