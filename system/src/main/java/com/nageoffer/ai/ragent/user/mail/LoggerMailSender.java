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

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * logger 模式邮件发送（默认）：只写日志不外发，本地与联调默认档
 * （对应 ragent.mail.mode=logger 档）
 *
 * <p>验证码原文随日志打印（U2 起）：logger 档不外发邮件，码值不可见则注册/重置内测无从取码，
 * 与档位定位冲突；生产开注册前必须切 smtp 档（该档不打印码值），码值日志即随切换消失。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ragent.mail.mode", havingValue = "logger", matchIfMissing = true)
public class LoggerMailSender implements MailSender {

    @Override
    public void send(MailMessage message) {
        // HTML 不整段进日志（体积大且无信息量），只记字符数作 multipart 双 part 的在场证据
        log.info("[mail:logger] to={} subject={} code={} textChars={} htmlChars={}",
                message.to(), message.subject(), message.code(),
                message.textBody() == null ? 0 : message.textBody().length(),
                message.htmlBody() == null ? 0 : message.htmlBody().length());
    }
}
