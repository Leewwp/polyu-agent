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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * SMTP 模式邮件发送（2026-09-14 接线落地：阿里云 DirectMail 465 SSL）
 *
 * <p>ragent.mail.mode=smtp 时装配；连接参数见 application.yaml spring.mail 段
 * （MAIL_* 环境注入，凭据按密钥纪律入 ~/.polyu-agent/secrets.yaml，不入仓库）。
 * 发信身份=独立别名 no-reply@polyuguide.com；
 * DirectMail 要求 From=认证发信地址，from 未配置时回落 username（Spring 不自动带 From）。
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
        SimpleMailMessage mail = new SimpleMailMessage();
        if (StringUtils.hasText(from)) {
            mail.setFrom(from);
        }
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.textBody());
        javaMailSender.send(mail);
        log.info("[mail:smtp] sent to={} subject={}", message.to(), message.subject());
    }
}
