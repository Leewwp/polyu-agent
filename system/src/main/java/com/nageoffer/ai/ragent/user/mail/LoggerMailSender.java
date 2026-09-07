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
 * （对应 OmniCraft smtp.mode=logger 口径；验证码日志脱敏为存在性，不打印明文码值）
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "ragent.mail.mode", havingValue = "logger", matchIfMissing = true)
public class LoggerMailSender implements MailSender {

    @Override
    public void send(MailMessage message) {
        log.info("[mail:logger] to={} subject={} bodyChars={}",
                message.to(), message.subject(),
                message.textBody() == null ? 0 : message.textBody().length());
    }
}
