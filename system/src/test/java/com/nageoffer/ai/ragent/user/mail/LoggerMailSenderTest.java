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

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * logger 档冒烟（#102）：四验证场景 + 通知类两态各渲染一封，
 * 日志行含 subject/code/textChars/htmlChars（multipart 双 part 在场证据），
 * 且 HTML 不整段进日志。
 */
class LoggerMailSenderTest {

    @Test
    void wholeFamilyLogsSubjectCodeAndDualPartCharCounts() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggerMailSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            LoggerMailSender sender = new LoggerMailSender();
            sender.send(MailTemplates.build("u@example.com", MailTemplates.Scene.VERIFY, "111111", 10));
            sender.send(MailTemplates.build("u@example.com", MailTemplates.Scene.RESET, "222222", 10));
            sender.send(MailTemplates.build("u@example.com", MailTemplates.Scene.DELETE, "333333", 10));
            sender.send(MailTemplates.build("u@example.com", MailTemplates.Scene.CHANGE, "444444", 10));
            sender.send(MailTemplates.buildEmailChangeNotify("old@example.com", MailTemplates.NotifyKind.REQUESTED));
            sender.send(MailTemplates.buildEmailChangeNotify("old@example.com", MailTemplates.NotifyKind.COMPLETED));

            List<String> lines = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(6, lines.size());
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                assertTrue(line.contains("[mail:logger]"), "行 " + i);
                assertTrue(line.contains("subject="), "行 " + i);
                assertTrue(line.contains("textChars="), "行 " + i);
                assertTrue(line.contains("htmlChars="), "行 " + i);
                assertFalse(line.contains("<"), "HTML 不整段进日志，行 " + i);
            }
            // 验证码类带码原文（logger 档定位即内测取码），通知类无码
            assertTrue(lines.get(0).contains("code=111111"));
            assertTrue(lines.get(0).contains("subject=PolyUGuide · 邮箱验证"));
            assertTrue(lines.get(4).contains("code=null"));
            assertTrue(lines.get(5).contains("code=null"));
            // 双 part 都在场：HTML 侧字符数应显著大于纯文本侧
            assertTrue(lines.get(0).matches(".*htmlChars=[0-9]{4,}.*"), lines.get(0));
        } finally {
            logger.detachAppender(appender);
        }
    }
}
