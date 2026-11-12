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

package com.nageoffer.ai.ragent.rag.config;

import org.apache.hc.core5.http.Header;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B2（2026-11-12 升级窗）：basic auth 头编码口径锚定（RFC 7617，UTF-8 user:pass 再 Base64）——
 * ES 端只认这一形态，编码口径漂移=全线 401，故用字面值回归锁死
 */
class EsClientConfigTests {

    @Test
    void basicAuthHeaderEncodesUtf8CredentialsPerRfc7617() {
        Header header = EsClientConfig.basicAuthHeader("elastic", "polyu-local-es");
        assertEquals("Authorization", header.getName());
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("elastic:polyu-local-es".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, header.getValue());
    }

    @Test
    void basicAuthHeaderToleratesNonAsciiPassword() {
        // 口令含多字节字符时不得退化为平台默认字符集（UTF-8 锚定）
        Header header = EsClientConfig.basicAuthHeader("elastic", "口令-Password-é");
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("elastic:口令-Password-é".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, header.getValue());
    }
}
