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

/**
 * 待发送邮件（multipart/alternative：HTML 主体 + 纯文本降级）
 *
 * @param htmlBody HTML 主体（#102 模板族：内联 CSS + 表格布局，Gmail/Outlook 剥 style 块仍完整呈现）
 * @param code     验证码原文（仅验证码类邮件非 null）。logger 模式靠它在日志中取码完成内测闭环；
 *                 smtp 模式的发送器不得打印该值
 */
public record MailMessage(String to, String subject, String textBody, String htmlBody, String code) {
}
