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
    private com.nageoffer.ai.ragent.user.security.WindowCounter windowCounter;
    private MailVerificationService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        mailSender = mock(MailSender.class);
        windowCounter = mock(com.nageoffer.ai.ragent.user.security.WindowCounter.class);
        service = new MailVerificationService(stringRedisTemplate, mailSender, windowCounter);
        ReflectionTestUtils.setField(service, "codeTtlMinutes", 10);
        ReflectionTestUtils.setField(service, "resendCooldownSeconds", 60);
        ReflectionTestUtils.setField(service, "hourlyLimitPerEmail", 3);
        ReflectionTestUtils.setField(service, "verifyMaxFailures", 5);
        ReflectionTestUtils.setField(service, "verifyFailWindowSeconds", 900);
        when(windowCounter.increment(anyString(), any(Duration.class))).thenReturn(1L);
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
    void sendCodeRejectsWhenHourlyLimitExceeded() {
        // U5：3 封/小时/邮箱——第 4 封被拒（冷却已放行、跨场景合并计数）
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(windowCounter.increment(eq("mail:hourly:user@example.com"), any(Duration.class))).thenReturn(4L);

        assertThrows(ClientException.class, () -> service.sendCode("user@example.com", "verify"));
        verify(mailSender, never()).send(any(MailMessage.class));
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

    // ---------- O1/M2：猜码失败计数（5 次作废须重发） ----------

    @Test
    void verifyCodeCountsFailuresAndInvalidatesCodeAtLimit() throws Exception {
        String digest = digestOf("123456");
        when(valueOperations.get("mail:code:reset:user@example.com")).thenReturn(digest);
        when(windowCounter.increment(eq("mail:fail:reset:user@example.com"), any(Duration.class)))
                .thenReturn(1L, 2L, 3L, 4L, 5L);

        for (int i = 0; i < 5; i++) {
            assertFalse(service.verifyCode("user@example.com", "reset", "000000"));
        }

        // 第 5 次失败即作废当前码
        verify(stringRedisTemplate).delete("mail:code:reset:user@example.com");
    }

    @Test
    void verifyCodeRejectsEvenCorrectCodeWhileFailureLockActive() throws Exception {
        when(windowCounter.current("mail:fail:reset:user@example.com")).thenReturn(5L);
        when(valueOperations.get("mail:code:reset:user@example.com")).thenReturn(digestOf("123456"));

        // 锁码窗口内正确码同样拒绝（口径同登录锁定），换发新码才解锁
        assertFalse(service.verifyCode("user@example.com", "reset", "123456"));
        verify(stringRedisTemplate, never()).delete(anyString());
    }

    @Test
    void verifyCodeSuccessBelowLimitUnaffected() throws Exception {
        when(valueOperations.get("mail:code:verify:user@example.com")).thenReturn(digestOf("123456"));

        assertTrue(service.verifyCode("user@example.com", "verify", "123456"));
        // 成功路径不产生失败计数
        verify(windowCounter, never()).increment(eq("mail:fail:verify:user@example.com"), any(Duration.class));
    }

    @Test
    void sendCodeResetsFailureCounterForFreshCode() {
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        service.sendCode("user@example.com", "verify");

        // 「作废须重发」救济闭环：换发新码清零猜码失败计数
        verify(stringRedisTemplate).delete("mail:fail:verify:user@example.com");
    }

    private String digestOf(String code) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
