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
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * SMTP 发信 From 语义（2026-09-14 接线）：阿里云 DirectMail 要求 From=认证发信地址，
 * Spring 的 JavaMailSender 不会自动带 From——未设置时多数服务商直接 501 拒信，
 * 故 SmtpMailSender 必须显式 setFrom(ragent.mail.from)。
 */
class SmtpMailSenderTest {

    private SimpleMailMessage sendAndGetCaptured(String from) {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        SmtpMailSender sender = new SmtpMailSender(javaMailSender);
        ReflectionTestUtils.setField(sender, "from", from);
        sender.send(new MailMessage("to@example.com", "PolyUGuide · 邮箱验证", "正文", "123456"));
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(javaMailSender).send(captor.capture());
        return captor.getValue();
    }

    @Test
    void 发信显式携带From与收件人主题正文() {
        SimpleMailMessage mail = sendAndGetCaptured("no-reply@polyuguide.com");
        assertEquals("no-reply@polyuguide.com", mail.getFrom());
        assertEquals("to@example.com", mail.getTo()[0]);
        assertEquals("PolyUGuide · 邮箱验证", mail.getSubject());
        assertEquals("正文", mail.getText());
    }

    @Test
    void From未配置时不设置回落会话用户名() {
        SimpleMailMessage mail = sendAndGetCaptured("");
        assertNull(mail.getFrom());
    }
}
