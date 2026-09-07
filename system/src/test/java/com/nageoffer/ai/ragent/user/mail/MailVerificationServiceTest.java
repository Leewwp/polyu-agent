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

package com.nageoffer.ai.ragent.user.mail;

import com.nageoffer.ai.ragent.framework.exception.ClientException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 邮件验证码服务单元测试：冷却、摘要落库、一次性校验、场景白名单
 */
class MailVerificationServiceTest {

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private MailSender mailSender;
    private MailVerificationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        mailSender = mock(MailSender.class);
        service = new MailVerificationService(stringRedisTemplate, mailSender);
        ReflectionTestUtils.setField(service, "codeTtlMinutes", 10);
        ReflectionTestUtils.setField(service, "resendCooldownSeconds", 60);
    }

    @Test
    void sendCodeStoresDigestAndSends() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        service.sendCode("user@example.com", "verify");

        // 存的是 SHA-256 摘要（64 hex）不是明文码
        verify(valueOperations).set(eq("mail:code:verify:user@example.com"),
                org.mockito.ArgumentMatchers.argThat(v -> v != null && v.length() == 64), any(Duration.class));
        verify(mailSender).send(any(MailMessage.class));
    }

    @Test
    void sendCodeRejectsWhenCooldownActive() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        assertThrows(ClientException.class, () -> service.sendCode("user@example.com", "verify"));
        verify(mailSender, never()).send(any(MailMessage.class));
    }

    @Test
    void sendCodeRejectsUnknownScene() {
        assertThrows(ClientException.class, () -> service.sendCode("user@example.com", "register"));
    }

    @Test
    void verifyCodeMatchesDigestAndConsumesOnce() throws Exception {
        String code = "123456";
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        when(valueOperations.get("mail:code:reset:user@example.com")).thenReturn(digest);

        assertTrue(service.verifyCode("user@example.com", "reset", code));
        verify(stringRedisTemplate).delete("mail:code:reset:user@example.com");
        // 一次性：第二次同码不再命中（存储已删）
        when(valueOperations.get("mail:code:reset:user@example.com")).thenReturn(null);
        assertFalse(service.verifyCode("user@example.com", "reset", code));
    }
}
