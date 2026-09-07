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
 * 邮件发送端口（T10，doc 13 §8.1 v1.1/§16）
 *
 * <p>双实现按 ragent.mail.mode 切换：logger（默认，只记日志不外发——与 OmniCraft
 * 本地口径一致）/ smtp（云侧已验证链路移植后启用）。发信身份与凭据属维护者门。
 */
public interface MailSender {

    /**
     * 发送一封邮件；实现不得抛出包含凭据内容的异常
     */
    void send(MailMessage message);
}
