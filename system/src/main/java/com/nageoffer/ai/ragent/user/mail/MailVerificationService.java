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

import cn.hutool.core.lang.Assert;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;

/**
 * 邮件验证码服务（T10 脚手架）
 *
 * <p>生成 6 位数字码 → Redis 存 SHA-256 摘要（TTL=有效期）→ 经 MailSender 发出；
 * 校验走摘要比对，防 Redis 泄露即泄露码值。重发冷却按 scene+email 键控。
 * 注册/重置/注销的业务端点在开放注册前另立单元接线（doc 13 §16 门）。
 */
@Service
@RequiredArgsConstructor
public class MailVerificationService {

    private static final String CODE_KEY_PREFIX = "mail:code:";
    private static final String COOLDOWN_KEY_PREFIX = "mail:cooldown:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<String> SCENES = Set.of("verify", "reset", "delete");

    private final StringRedisTemplate stringRedisTemplate;
    private final MailSender mailSender;

    /**
     * 验证码有效期（分钟）
     */
    @Value("${ragent.mail.code-ttl-minutes:10}")
    private int codeTtlMinutes;

    /**
     * 重发冷却（秒），对齐 OmniCraft resend_cooldown_sec=60
     */
    @Value("${ragent.mail.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds;

    /**
     * 发送验证码
     *
     * @param scene verify / reset / delete
     */
    public void sendCode(String email, String scene) {
        Assert.notBlank(email, () -> new ClientException("邮箱不能为空"));
        if (!SCENES.contains(scene)) {
            throw new ClientException("不支持的验证场景");
        }
        String cooldownKey = COOLDOWN_KEY_PREFIX + scene + ":" + email;
        Boolean first = stringRedisTemplate.opsForValue().setIfAbsent(cooldownKey, "1", Duration.ofSeconds(resendCooldownSeconds));
        if (!Boolean.TRUE.equals(first)) {
            throw new ClientException("发送过于频繁，请稍后再试");
        }
        String code = generateCode();
        String codeKey = CODE_KEY_PREFIX + scene + ":" + email;
        stringRedisTemplate.opsForValue().set(codeKey, sha256(code), Duration.ofMinutes(codeTtlMinutes));
        mailSender.send(MailTemplates.build(email, MailTemplates.Scene.valueOf(scene.toUpperCase()), code, codeTtlMinutes));
    }

    /**
     * 校验验证码；通过即销毁（一次性）
     */
    public boolean verifyCode(String email, String scene, String code) {
        if (email == null || scene == null || code == null || code.isBlank()) {
            return false;
        }
        String codeKey = CODE_KEY_PREFIX + scene + ":" + email;
        String stored = stringRedisTemplate.opsForValue().get(codeKey);
        if (stored == null) {
            return false;
        }
        boolean matched = stored.equals(sha256(code));
        if (matched) {
            stringRedisTemplate.delete(codeKey);
        }
        return matched;
    }

    private String generateCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
