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

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * multipart 上传入口守卫测试（对齐安全审查 §6-2a）。
 *
 * <p>覆盖：url 源指向内网被拒（守卫消息透传、请求不到控制器）、file 源与正常形态直通、
 * 非 multipart/非目标路径/GET 不触发守卫、Filter 层解析超限时复刻全局处理器的响应文案。
 * 全部用字面量 IP，不解析真实域名。
 */
class MultipartSourceGuardFilterTest {

    private MultipartSourceGuardFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MultipartSourceGuardFilter(new IngestionUrlGuard(false));
        ReflectionTestUtils.setField(filter, "maxFileSize", "50MB");
        ReflectionTestUtils.setField(filter, "maxRequestSize", "100MB");
    }

    private MockHttpServletRequest uploadRequest(String sourceType, String sourceLocation) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/knowledge-base/kb-1/docs/upload");
        request.setContentType("multipart/form-data; boundary=----boundary");
        request.setServletPath("/knowledge-base/kb-1/docs/upload");
        if (sourceType != null) {
            request.addParameter("sourceType", sourceType);
        }
        if (sourceLocation != null) {
            request.addParameter("sourceLocation", sourceLocation);
        }
        return request;
    }

    @Test
    void rejectsUrlSourcePointingToLoopback() throws Exception {
        MockHttpServletRequest request = uploadRequest("url", "http://127.0.0.1:5432/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains(BaseErrorCode.CLIENT_ERROR.code())
                .contains("文档源地址解析到内网或保留地址，已拒绝");
    }

    @Test
    void rejectsUrlSourcePointingToRfc1918() throws Exception {
        MockHttpServletRequest request = uploadRequest("url", "http://192.168.1.10/secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("已拒绝");
    }

    @Test
    void rejectsBlankUrlLocation() throws Exception {
        MockHttpServletRequest request = uploadRequest("url", " ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("文档源地址不能为空");
    }

    @Test
    void passesFileSourceThroughToController() throws Exception {
        MockHttpServletRequest request = uploadRequest("file", null);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void passesPublicUrlSourceThroughToController() throws Exception {
        // 公网字面量 IP：守卫放行（不依赖 DNS），请求继续走控制器
        MockHttpServletRequest request = uploadRequest("url", "https://93.184.216.34/doc.pdf");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void skipsNonMultipartAndOtherPathsAndMethods() throws Exception {
        // 同路径但非 multipart：端点 consumes 约束下 Spring 会 415，守卫不触发
        MockHttpServletRequest urlencoded = new MockHttpServletRequest("POST", "/knowledge-base/kb-1/docs/upload");
        urlencoded.setContentType("application/x-www-form-urlencoded");
        urlencoded.setServletPath("/knowledge-base/kb-1/docs/upload");
        urlencoded.addParameter("sourceType", "url");
        urlencoded.addParameter("sourceLocation", "http://127.0.0.1:5432/");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(urlencoded, response, chain);
        assertThat(chain.getRequest()).isNotNull();

        // 其他路径与 GET 同样不触发
        MockHttpServletRequest otherPath = new MockHttpServletRequest("POST", "/ingestion/tasks");
        otherPath.setContentType("multipart/form-data; boundary=b");
        otherPath.setServletPath("/ingestion/tasks");
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(otherPath, response, chain2);
        assertThat(chain2.getRequest()).isNotNull();

        MockHttpServletRequest getRequest = new MockHttpServletRequest("GET", "/knowledge-base/kb-1/docs/upload");
        getRequest.setContentType("multipart/form-data; boundary=b");
        getRequest.setServletPath("/knowledge-base/kb-1/docs/upload");
        MockFilterChain chain3 = new MockFilterChain();
        filter.doFilter(getRequest, response, chain3);
        assertThat(chain3.getRequest()).isNotNull();
    }

    @Test
    void replicatesGlobalHandlerMessageOnOversizeParseFailure() throws Exception {
        // Filter 层读参数提前触发容器解析：复刻 MaxUploadSizeExceededException 处理器的
        // 文案分支（单文件超限），行为不回归。匿名子类模拟 Tomcat 解析失败
        //（IllegalStateException 包 FileSizeLimitExceededException，与容器实况同形）。
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/knowledge-base/kb-1/docs/upload") {
            @Override
            public String getParameter(String name) {
                if ("sourceType".equals(name)) {
                    throw new IllegalStateException(new FileSizeLimitExceededException("a.pdf", 101, 100));
                }
                return null;
            }
        };
        request.setContentType("multipart/form-data; boundary=b");
        request.setServletPath("/knowledge-base/kb-1/docs/upload");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("上传文件大小超过限制，单个文件最大允许 50MB");
    }

    @Test
    void replicatesGlobalHandlerMessageOnRequestSizeParseFailure() throws Exception {
        // 无 FileSizeLimitExceededException 内因的解析失败按「单次请求超限」文案复刻
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/knowledge-base/kb-1/docs/upload") {
            @Override
            public String getParameter(String name) {
                throw new IllegalStateException("request size limit exceeded");
            }
        };
        request.setContentType("multipart/form-data; boundary=b");
        request.setServletPath("/knowledge-base/kb-1/docs/upload");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString(StandardCharsets.UTF_8))
                .contains("上传请求大小超过限制，单次请求最大允许 100MB");
    }
}
