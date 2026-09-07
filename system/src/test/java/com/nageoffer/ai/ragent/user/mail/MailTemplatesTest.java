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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双语邮件模板单元测试：三场景主题/正文、双语并列、TTL 注入
 */
class MailTemplatesTest {

    @Test
    void verifyTemplateIsBilingualWithCodeAndTtl() {
        MailMessage message = MailTemplates.build("user@example.com", MailTemplates.Scene.VERIFY, "123456", 10);
        assertTrue(message.subject().contains("邮箱验证"));
        assertTrue(message.subject().contains("Email verification"));
        assertTrue(message.textBody().contains("123456"));
        assertTrue(message.textBody().contains("验证码"));
        assertTrue(message.textBody().contains("verification code"));
        assertTrue(message.textBody().contains("10 分钟内有效"));
        assertTrue(message.textBody().contains("valid for 10 minutes"));
    }

    @Test
    void resetAndDeleteTemplatesDifferByScene() {
        MailMessage reset = MailTemplates.build("user@example.com", MailTemplates.Scene.RESET, "654321", 5);
        assertTrue(reset.subject().contains("密码重置"));
        assertTrue(reset.textBody().contains("重置码"));
        assertTrue(reset.textBody().contains("valid for 5 minutes"));

        MailMessage delete = MailTemplates.build("user@example.com", MailTemplates.Scene.DELETE, "000000", 15);
        assertTrue(delete.subject().contains("账号注销确认"));
        assertTrue(delete.textBody().contains("确认码"));
    }

    @Test
    void codeIsRenderedVerbatimNoMasking() {
        MailMessage message = MailTemplates.build("user@example.com", MailTemplates.Scene.VERIFY, "012345", 10);
        assertTrue(message.textBody().contains("012345"));
        assertEquals("user@example.com", message.to());
    }
}
