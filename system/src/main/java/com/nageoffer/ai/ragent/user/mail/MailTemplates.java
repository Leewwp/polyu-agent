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
 * 双语邮件模板（T10，doc 13 §8.1：邮箱注册、验证、忘记密码、账号注销）
 *
 * <p>每封邮件中英双语并列（V1 正式支持简中+英文，用户语言偏好未知时双写最稳）；
 * 纯文本模板，不引入模板引擎（V1 最低可用）。
 */
public final class MailTemplates {

    /**
     * 产品名：随发信身份门批复后可配（ragent.mail.product-name），暂以常量占位
     */
    private static final String PRODUCT = "PolyU Wayfinder";

    private MailTemplates() {
    }

    public enum Scene {
        /** 邮箱验证（注册/换绑） */
        VERIFY,
        /** 忘记密码重置 */
        RESET,
        /** 账号注销确认 */
        DELETE
    }

    public static MailMessage build(String to, Scene scene, String code, int ttlMinutes) {
        return switch (scene) {
            case VERIFY -> new MailMessage(to, subject("邮箱验证", "Email verification"), bodyVerify(code, ttlMinutes), code);
            case RESET -> new MailMessage(to, subject("密码重置", "Password reset"), bodyReset(code, ttlMinutes), code);
            case DELETE -> new MailMessage(to, subject("账号注销确认", "Account deletion confirmation"), bodyDelete(code, ttlMinutes), code);
        };
    }

    private static String subject(String zh, String en) {
        return PRODUCT + " · " + zh + " / " + en;
    }

    private static String bodyVerify(String code, int ttlMinutes) {
        return """
                您的 %s 验证码：%s（%d 分钟内有效）

                Your %s verification code: %s (valid for %d minutes)

                如果这不是您本人的操作，请忽略本邮件。
                If you did not request this, please ignore this email.
                """.strip().formatted(PRODUCT, code, ttlMinutes, PRODUCT, code, ttlMinutes);
    }

    private static String bodyReset(String code, int ttlMinutes) {
        return """
                您正在重置 %s 账号密码。重置码：%s（%d 分钟内有效）

                You are resetting your %s password. Reset code: %s (valid for %d minutes)

                如果这不是您本人的操作，请立即检查账号安全并忽略本邮件。
                If you did not request this, please secure your account and ignore this email.
                """.strip().formatted(PRODUCT, code, ttlMinutes, PRODUCT, code, ttlMinutes);
    }

    private static String bodyDelete(String code, int ttlMinutes) {
        return """
                您正在注销 %s 账号。确认码：%s（%d 分钟内有效）。注销后关联个人数据将按隐私政策清理。

                You are deleting your %s account. Confirmation code: %s (valid for %d minutes).
                Associated personal data will be purged according to the privacy policy.
                """.strip().formatted(PRODUCT, code, ttlMinutes, PRODUCT, code, ttlMinutes);
    }
}
