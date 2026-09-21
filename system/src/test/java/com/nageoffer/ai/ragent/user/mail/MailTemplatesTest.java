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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双语邮件模板族单元测试（#102 HTML 化）：四验证场景+两通知态的主题/正文/HTML、
 * 双语并列、TTL 动态注入、大号验证码样式，以及反钓鱼硬约束——全文零链接。
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
    void ttlIsInjectedDynamicallyInBothParts() {
        MailMessage message = MailTemplates.build("user@example.com", MailTemplates.Scene.RESET, "654321", 7);
        assertTrue(message.textBody().contains("7 分钟内有效"));
        assertTrue(message.textBody().contains("valid for 7 minutes"));
        assertTrue(message.htmlBody().contains("7 分钟内有效"));
        assertTrue(message.htmlBody().contains("valid for 7 minutes"));
        assertFalse(message.htmlBody().contains("10 分钟"));
    }

    @Test
    void resetDeleteAndChangeTemplatesDifferByScene() {
        MailMessage reset = MailTemplates.build("user@example.com", MailTemplates.Scene.RESET, "654321", 5);
        assertTrue(reset.subject().contains("密码重置"));
        assertTrue(reset.textBody().contains("重置码"));
        assertTrue(reset.htmlBody().contains("重置码"));

        MailMessage delete = MailTemplates.build("user@example.com", MailTemplates.Scene.DELETE, "000000", 15);
        assertTrue(delete.subject().contains("账号注销确认"));
        assertTrue(delete.textBody().contains("确认码"));
        assertTrue(delete.textBody().contains("隐私政策"));

        MailMessage change = MailTemplates.build("user@example.com", MailTemplates.Scene.CHANGE, "111222", 10);
        assertTrue(change.subject().contains("更改绑定邮箱"));
        assertTrue(change.textBody().contains("验证码"));
        assertTrue(change.htmlBody().contains("更改绑定邮箱"));
    }

    @Test
    void htmlUsesBrandShellAndLargeCenteredCode() {
        MailMessage message = MailTemplates.build("user@example.com", MailTemplates.Scene.VERIFY, "012345", 10);
        String html = message.htmlBody();
        assertTrue(html.contains("#a6192e"), "PolyU 红品牌色");
        assertTrue(html.contains("PolyUGuide"));
        assertTrue(html.contains("请勿直接回复"), "自动邮件页脚");
        assertTrue(html.contains("unofficial project"), "非官方声明页脚");
        // 大号居中等宽验证码：≥32px + letter-spacing 加宽 + 验证码原文
        assertTrue(html.contains("font-size:36px"));
        assertTrue(html.contains("letter-spacing"));
        assertTrue(html.contains("monospace"));
        assertTrue(html.contains(">012345<"));
        assertTrue(html.contains("如果这不是您本人的操作"));
        assertTrue(html.contains("If you did not request this"));
    }

    @Test
    void notifyTemplatesCarryNoCode() {
        MailMessage requested = MailTemplates.buildEmailChangeNotify("old@example.com", MailTemplates.NotifyKind.REQUESTED);
        assertTrue(requested.subject().contains("邮箱更改请求通知"));
        assertTrue(requested.textBody().contains("更改请求"));
        assertTrue(requested.htmlBody().contains("更改请求已发起"));
        assertTrue(requested.textBody().contains("请立即登录并修改密码"));
        assertNull(requested.code());
        assertFalse(requested.htmlBody().contains("font-size:36px"), "通知类无验证码色块");

        MailMessage completed = MailTemplates.buildEmailChangeNotify("old@example.com", MailTemplates.NotifyKind.COMPLETED);
        assertTrue(completed.subject().contains("绑定邮箱已更改"));
        assertTrue(completed.textBody().contains("已成功更改"));
        assertTrue(completed.htmlBody().contains("已更改"));
        assertNull(completed.code());
    }

    @Test
    void wholeFamilyIsLinkFree() {
        List<MailMessage> family = List.of(
                MailTemplates.build("u@example.com", MailTemplates.Scene.VERIFY, "123456", 10),
                MailTemplates.build("u@example.com", MailTemplates.Scene.RESET, "123456", 10),
                MailTemplates.build("u@example.com", MailTemplates.Scene.DELETE, "123456", 10),
                MailTemplates.build("u@example.com", MailTemplates.Scene.CHANGE, "123456", 10),
                MailTemplates.buildEmailChangeNotify("u@example.com", MailTemplates.NotifyKind.REQUESTED),
                MailTemplates.buildEmailChangeNotify("u@example.com", MailTemplates.NotifyKind.COMPLETED));
        for (MailMessage message : family) {
            String all = message.subject() + "\n" + message.textBody() + "\n" + message.htmlBody();
            assertFalse(all.contains("<a"), "反钓鱼：不允许 a 标签 → " + message.subject());
            assertFalse(all.toLowerCase().contains("http"), "反钓鱼：不允许任何 URL → " + message.subject());
        }
    }

    @Test
    void codeIsRenderedVerbatimNoMasking() {
        MailMessage message = MailTemplates.build("user@example.com", MailTemplates.Scene.VERIFY, "012345", 10);
        assertTrue(message.textBody().contains("012345"));
        assertTrue(message.htmlBody().contains("012345"));
        assertEquals("user@example.com", message.to());
    }
}
