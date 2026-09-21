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

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;

/**
 * SMTP 模式邮件发送（2026-09-14 接线落地：阿里云 DirectMail 465 SSL；#102 HTML 化）
 *
 * <p>ragent.mail.mode=smtp 时装配；连接参数见 application.yaml spring.mail 段
 * （MAIL_* 环境注入，凭据按密钥纪律入 ~/.polyu-agent/secrets.yaml，不入仓库）。
 * 发信身份=独立别名 no-reply@polyuguide.com；
 * DirectMail 要求 From=认证发信地址，from 未配置时回落 username（Spring 不自动带 From）。
 *
 * <p>#102 起 multipart/alternative 双 part：HTML 主体 + 纯文本降级
 * （文本客户端可读、垃圾邮件评分友好），整体 UTF-8（中文主题编码一并覆盖）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ragent.mail.mode", havingValue = "smtp")
@RequiredArgsConstructor
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMailSender;

    @Value("${ragent.mail.from:}")
    private String from;

    @Override
    public void send(MailMessage message) {
        MimeMessage mimeMessage = javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());
            if (StringUtils.hasText(from)) {
                helper.setFrom(from);
            }
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            helper.setText(message.textBody(), message.htmlBody());
        } catch (MessagingException ex) {
            throw new MailSendException("邮件组装失败: " + message.subject(), ex);
        }
        javaMailSender.send(mimeMessage);
        log.info("[mail:smtp] sent to={} subject={}", message.to(), message.subject());
    }
}
