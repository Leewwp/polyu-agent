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

import jakarta.mail.BodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SMTP 发信 From 语义（2026-09-14 接线）与 #102 multipart 结构：
 * 阿里云 DirectMail 要求 From=认证发信地址，Spring 的 JavaMailSender 不会自动带
 * From——未设置时多数服务商直接 501 拒信，故 SmtpMailSender 必须显式
 * setFrom(ragent.mail.from)；HTML 化后须是 multipart 双 part（text/plain 降级 + text/html）。
 */
class SmtpMailSenderTest {

    private record Sent(String from, String to, String subject, List<BodyPart> leafParts) {
    }

    private Sent sendAndGetStructure(String from) throws Exception {
        JavaMailSender javaMailSender = mock(JavaMailSender.class);
        when(javaMailSender.createMimeMessage()).thenReturn(new MimeMessage((jakarta.mail.Session) null));
        SmtpMailSender sender = new SmtpMailSender(javaMailSender);
        ReflectionTestUtils.setField(sender, "from", from);
        sender.send(new MailMessage("to@example.com", "PolyUGuide · 邮箱验证", "正文降级",
                "<!DOCTYPE html><html lang=\"zh\"><body>正文 HTML</body></html>", "123456"));
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(javaMailSender).send(captor.capture());
        // 真实发送时 Transport.sendMessage 会物化各 part 头；单测里没有这一步，手补后再断言结构
        captor.getValue().saveChanges();
        MimeMessage sent = captor.getValue();
        jakarta.mail.Address[] fromHeaders = sent.getFrom();
        return new Sent(fromHeaders == null || fromHeaders.length == 0 ? null : fromHeaders[0].toString(),
                sent.getRecipients(jakarta.mail.Message.RecipientType.TO)[0].toString(),
                sent.getSubject(),
                collectLeafParts(sent));
    }

    /** 递归拍平 multipart，取全部叶子 BodyPart（multipart/mixed → alternative → 双 part） */
    private static List<BodyPart> collectLeafParts(MimeMessage message) throws Exception {
        List<BodyPart> leaves = new ArrayList<>();
        Object content = message.getContent();
        collect(content, leaves);
        return leaves;
    }

    private static void collect(Object content, List<BodyPart> leaves) throws Exception {
        if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart part = multipart.getBodyPart(i);
                Object inner = part.getContent();
                if (inner instanceof MimeMultipart innerMultipart) {
                    collect(innerMultipart, leaves);
                } else {
                    leaves.add(part);
                }
            }
        }
    }

    private static BodyPart partOfType(List<BodyPart> leaves, String mimeType) {
        return leaves.stream().filter(part -> isMime(part, mimeType)).findFirst().orElseThrow();
    }

    private static boolean isMime(BodyPart part, String mimeType) {
        try {
            return part.isMimeType(mimeType);
        } catch (jakarta.mail.MessagingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void 发信显式携带From与收件人主题() throws Exception {
        Sent sent = sendAndGetStructure("no-reply@polyuguide.com");
        assertEquals("no-reply@polyuguide.com", sent.from());
        assertEquals("to@example.com", sent.to());
        assertEquals("PolyUGuide · 邮箱验证", sent.subject());
    }

    @Test
    void From未配置时不设置回落会话用户名() throws Exception {
        Sent sent = sendAndGetStructure("");
        assertNull(sent.from());
    }

    @Test
    void multipart双part携带纯文本降级与HTML主体() throws Exception {
        Sent sent = sendAndGetStructure("no-reply@polyuguide.com");
        assertEquals(2, sent.leafParts().size(), "应为 alternative 双 part");
        BodyPart textPart = partOfType(sent.leafParts(), "text/plain");
        BodyPart htmlPart = partOfType(sent.leafParts(), "text/html");
        assertTrue(textPart.getContent().toString().contains("正文降级"));
        assertTrue(htmlPart.getContent().toString().contains("正文 HTML"));
    }
}
