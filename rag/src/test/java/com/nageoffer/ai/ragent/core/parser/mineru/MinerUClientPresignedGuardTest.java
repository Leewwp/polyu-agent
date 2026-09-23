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

package com.nageoffer.ai.ragent.core.parser.mineru;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.rag.config.FetchLimits;
import com.nageoffer.ai.ragent.rag.security.IngestionUrlGuard;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.unit.DataSize;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MinerU 预签名传输守卫测试（issue #125 真缺口收口）：uploadUrl/zipUrl 是 SaaS 响应
 * 回填的 URL，按不可信内容对待——严格档守卫下伪内网/元数据 presigned URL 在预检即被拒
 * （不发出任何网络请求）；宽松档（本地回环）下正常传输不受扰；zip 下载带上限。
 */
class MinerUClientPresignedGuardTest {

    private MockWebServer server;
    private MinerUProperties properties;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        properties = new MinerUProperties();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    /** 严格档=生产装配（allow-private-hosts=false）；客户端为守卫无关的普通 client，验证预检先行 */
    private MinerUClient strictGuardClient() {
        return new MinerUClient(new OkHttpClient(), new OkHttpClient(),
                new IngestionUrlGuard(false), limits("1024B"), new ObjectMapper(), properties);
    }

    /** 宽松档：回环 MockWebServer 可通，验证守卫化后的正常路径不受扰 */
    private MinerUClient looseGuardClient(String rawLimit) {
        return new MinerUClient(new OkHttpClient(), new OkHttpClient(),
                new IngestionUrlGuard(true), limits(rawLimit), new ObjectMapper(), properties);
    }

    private static FetchLimits limits(String raw) {
        FetchLimits limits = new FetchLimits();
        ReflectionTestUtils.setField(limits, "maxFileSize", raw);
        return limits;
    }

    @Test
    void pseudoInternalPresignedUploadUrlIsRejectedBeforeAnyRequest() {
        // SaaS 响应被劫持回填内网地址（含云元数据）：预检拒绝，零网络请求
        assertThrows(ClientException.class,
                () -> strictGuardClient().uploadFile("http://169.254.169.254/oss/presigned-put", new byte[]{1}));
        assertThrows(ClientException.class,
                () -> strictGuardClient().uploadFile("http://10.0.0.5/oss/presigned-put", new byte[]{1}));
    }

    @Test
    void pseudoInternalZipUrlIsRejectedBeforeAnyRequest() {
        assertThrows(ClientException.class,
                () -> strictGuardClient().downloadZip("http://127.0.0.1:9000/mineru/result.zip"));
        assertThrows(ClientException.class,
                () -> strictGuardClient().downloadZip("file:///etc/passwd"));
    }

    @Test
    void presignedQuerySignatureDoesNotInterfereWithGuard() {
        // presigned URL 的鉴权全部在 query 参数：守卫只看 scheme/形状/主机地址，签名零妨碍
        String presigned = server.url("/mineru/result.zip?X-Amz-Signature=abc&X-Amz-Expires=600").toString();
        server.enqueue(new MockResponse.Builder().body("zip-bytes").build());

        byte[] bytes = looseGuardClient("1024B").downloadZip(presigned);

        assertArrayEquals("zip-bytes".getBytes(), bytes);
    }

    @Test
    void oversizedZipDownloadIsRejected() {
        server.enqueue(new MockResponse.Builder().body("x".repeat(64)).build());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> looseGuardClient("16B").downloadZip(server.url("/mineru/huge.zip").toString()));
        assertTrue(ex.getMessage().contains("文件大小超过限制"));
    }

    @Test
    void presignedUploadNormalPathIsUndisturbed() {
        server.enqueue(new MockResponse.Builder().code(200).build());

        assertDoesNotThrow(() ->
                looseGuardClient("1024B").uploadFile(server.url("/oss/presigned-put?sig=1").toString(), new byte[]{1, 2, 3}));
    }
}
