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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密码编解码单元测试：{bcrypt} 新值闭环 + 明文存量兼容 + 升级标记
 */
class PasswordCodecTest {

    private final PasswordCodec codec = new PasswordCodec();

    @Test
    void encodeProducesBcryptPrefixedHash() {
        String encoded = codec.encode("s3cret-Pa55");
        assertTrue(encoded.startsWith("{bcrypt}"), "应为 {bcrypt} 前缀: " + encoded);
        assertNotEquals("s3cret-Pa55", encoded);
        assertTrue(codec.matches("s3cret-Pa55", encoded));
        assertFalse(codec.matches("wrong", encoded));
        assertFalse(codec.needsUpgrade(encoded));
    }

    @Test
    void encodeIsSaltedPerCall() {
        assertNotEquals(codec.encode("same-input"), codec.encode("same-input"));
    }

    @Test
    void legacyPlaintextMatchesAndNeedsUpgrade() {
        assertTrue(codec.matches("admin", "admin"));
        assertFalse(codec.matches("admin", "not-admin"));
        assertTrue(codec.needsUpgrade("admin"));
    }

    @Test
    void nullStoredNeverMatches() {
        assertFalse(codec.matches("any", null));
        assertFalse(codec.matches(null, null));
        assertFalse(codec.needsUpgrade(null));
    }

    @Test
    void unknownPrefixIdTreatedAsMismatch() {
        // 带 {id} 前缀但算法未注册：吞掉 IllegalArgumentException，按不匹配处理
        assertFalse(codec.matches("any", "{nope}abcdef"));
        assertFalse(codec.needsUpgrade("{nope}abcdef"));
    }
}
