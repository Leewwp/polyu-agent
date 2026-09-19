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
import com.nageoffer.ai.ragent.user.security.WindowCounter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Set;

/**
 * 邮件验证码服务
 *
 * <p>生成 6 位数字码 → Redis 存 SHA-256 摘要（TTL=有效期）→ 经 MailSender 发出；
 * 校验走摘要比对，防 Redis 泄露即泄露码值。频控两层：60s 重发冷却（scene+email 键控）
 * + 每小时每邮箱 3 封（跨场景合并计数）。校验侧另有猜码失败计数（O1/M2）：
 * 同一码连续猜错 5 次即作废须重发；换发新码清零计数，故猜码速率受发码频控约束。
 */
@Service
@RequiredArgsConstructor
public class MailVerificationService {

    private static final String CODE_KEY_PREFIX = "mail:code:";
    private static final String COOLDOWN_KEY_PREFIX = "mail:cooldown:";
    private static final String HOURLY_KEY_PREFIX = "mail:hourly:";
    private static final String FAIL_KEY_PREFIX = "mail:fail:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Set<String> SCENES = Set.of("verify", "reset", "delete");

    private final StringRedisTemplate stringRedisTemplate;
    private final MailSender mailSender;
    private final WindowCounter windowCounter;

    /**
     * 验证码有效期（分钟）
     */
    @Value("${ragent.mail.code-ttl-minutes:10}")
    private int codeTtlMinutes;

    /**
     * 重发冷却（秒），默认 60
     */
    @Value("${ragent.mail.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds;

    /**
     * 每小时每邮箱发信上限（3 封/小时/邮箱；0/负=不限制）
     */
    @Value("${ragent.mail.hourly-limit-per-email:3}")
    private int hourlyLimitPerEmail;

    /**
     * 同一验证码允许的连续校验失败次数（O1/M2：超限作废须重发；0/负=不限制）
     */
    @Value("${ragent.mail.verify-max-failures:5}")
    private int verifyMaxFailures;

    /**
     * 猜码失败计数窗口=锁码时长（秒），口径同登录锁定 15 分钟
     */
    @Value("${ragent.mail.verify-fail-window-seconds:900}")
    private int verifyFailWindowSeconds;

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
        if (hourlyLimitPerEmail > 0) {
            long sent = windowCounter.increment(HOURLY_KEY_PREFIX + email, Duration.ofHours(1));
            if (sent > hourlyLimitPerEmail) {
                throw new ClientException("该邮箱本小时发送次数已达上限，请稍后再试");
            }
        }
        String code = generateCode();
        String codeKey = CODE_KEY_PREFIX + scene + ":" + email;
        stringRedisTemplate.opsForValue().set(codeKey, sha256(code), Duration.ofMinutes(codeTtlMinutes));
        // 换发新码清零猜码失败计数——「作废须重发」的救济路径真实可用；
        // 猜码总速率因此受发码频控（3 封/小时/邮箱）硬约束
        stringRedisTemplate.delete(FAIL_KEY_PREFIX + scene + ":" + email);
        mailSender.send(MailTemplates.build(email, MailTemplates.Scene.valueOf(scene.toUpperCase()), code, codeTtlMinutes));
    }

    /**
     * 校验验证码；通过即销毁（一次性）。猜码失败计数达限后当前码作废（O1/M2）。
     */
    public boolean verifyCode(String email, String scene, String code) {
        if (email == null || scene == null || code == null || code.isBlank()) {
            return false;
        }
        String codeKey = CODE_KEY_PREFIX + scene + ":" + email;
        String failKey = FAIL_KEY_PREFIX + scene + ":" + email;
        // 已因连续猜错锁码：正确码同样拒绝（口径同登录锁定），换发新码才解锁
        if (verifyMaxFailures > 0 && windowCounter.current(failKey) >= verifyMaxFailures) {
            return false;
        }
        String stored = stringRedisTemplate.opsForValue().get(codeKey);
        if (stored == null) {
            return false;
        }
        boolean matched = stored.equals(sha256(code));
        if (matched) {
            stringRedisTemplate.delete(codeKey);
            return true;
        }
        if (verifyMaxFailures > 0) {
            long failures = windowCounter.increment(failKey, Duration.ofSeconds(verifyFailWindowSeconds));
            if (failures >= verifyMaxFailures) {
                stringRedisTemplate.delete(codeKey);
            }
        }
        return false;
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
