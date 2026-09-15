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

package com.nageoffer.ai.ragent.user.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 密码编解码统一入口：DelegatingPasswordEncoder（新密码一律 {bcrypt} 前缀哈希）。
 *
 * <p>存量兼容：历史上明文落库的密码不带 {id} 前缀，DelegatingPasswordEncoder 对无前缀密文
 * 会直接抛 IllegalArgumentException。这里对无前缀值退回明文比对，并要求调用方在
 * {@link #needsUpgrade} 为真时尽快重哈希落库（登录成功即升级），逐步收敛到全哈希态。
 */
@Component
public class PasswordCodec {

    private final PasswordEncoder delegate = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    /**
     * 加密新密码（带 {bcrypt} 前缀，encode 结果永不为 null）
     */
    public String encode(String rawPassword) {
        return delegate.encode(rawPassword);
    }

    /**
     * 校验密码；stored 为 null 一律不通过（不存在“空密码等于空输入”）
     */
    public boolean matches(String rawPassword, String storedPassword) {
        if (storedPassword == null) {
            return false;
        }
        if (isLegacyPlaintext(storedPassword)) {
            return rawPassword != null && constantTimeEquals(rawPassword, storedPassword);
        }
        try {
            return delegate.matches(rawPassword, storedPassword);
        } catch (IllegalArgumentException ex) {
            // 带 {id} 前缀但算法未注册（历史残留的其他格式）：按不匹配处理
            return false;
        }
    }

    /**
     * 该存储值是否需要升级为哈希（明文存量）
     */
    public boolean needsUpgrade(String storedPassword) {
        return storedPassword != null && isLegacyPlaintext(storedPassword);
    }

    private boolean isLegacyPlaintext(String storedPassword) {
        return !storedPassword.startsWith("{");
    }

    /**
     * 明文存量比对的恒时比较，避免短路泄露长度信息
     */
    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
