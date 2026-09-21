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
 * 双语邮件模板族（#102 HTML 化）
 *
 * <p>每封邮件中英双语并列（V1 正式支持简中+英文，用户语言偏好未知时双写最稳）；
 * 仍不引入模板引擎，HTML 手写拼装（票面约束）。反钓鱼纪律：全文零链接——
 * 不出现任何 URL 与 a 标签，品牌名纯文字，用户想去的页面自己去站内找。
 *
 * <p>占位符统一 {@code {{token}}} 字面替换（不用 String.format：HTML/CSS 里的 % 与
 * 格式说明符互相干扰）。HTML 外壳统一：PolyU 红（#a6192e，对齐前端 --polyu-red）
 * 顶条 + 品牌头 + 卡片 + 自动邮件页脚；内联 CSS + 表格布局（Gmail/Outlook 剥
 * style 块兼容）。验证码类（verify/reset/delete/change）大号居中等宽验证码独立色块；
 * 通知类（改邮箱请求/成功）无验证码无链接。SCENES 门（MailVerificationService）
 * 本票不动，change 场景由 #104 随改邮箱端点一并放行，模板侧先行支持渲染。
 */
public final class MailTemplates {

    /**
     * 产品名：可经 ragent.mail.product-name 配置，暂以常量占位；
     * 2026-09-13 更名：PolyU Wayfinder → PolyUGuide（品牌统一漏网位）
     */
    private static final String PRODUCT = "PolyUGuide";

    /**
     * PolyU 品牌红（对齐前端 --polyu-red）
     */
    private static final String RED = "#a6192e";

    private static final String FONT = "font-family:Arial,'Helvetica Neue',Helvetica,sans-serif;";

    private static final String MONO_FONT = "font-family:'Courier New',Courier,monospace;";

    private MailTemplates() {
    }

    public enum Scene {
        /** 邮箱验证（注册/换绑） */
        VERIFY,
        /** 忘记密码重置 */
        RESET,
        /** 账号注销确认 */
        DELETE,
        /** 更改绑定邮箱（#104 接线；模板侧先行渲染） */
        CHANGE
    }

    /** 通知类子场景（无验证码无链接，「如非本人操作请立即修改密码」） */
    public enum NotifyKind {
        /** 改邮箱请求已发起（发往老邮箱） */
        REQUESTED,
        /** 改邮箱已完成（发往老邮箱） */
        COMPLETED
    }

    public static MailMessage build(String to, Scene scene, String code, int ttlMinutes) {
        return switch (scene) {
            case VERIFY -> new MailMessage(to, subject("邮箱验证", "Email verification"), plain("""
                    您的 {{product}} 验证码：{{code}}（{{ttl}} 分钟内有效）

                    Your {{product}} verification code: {{code}} (valid for {{ttl}} minutes)

                    如果这不是您本人的操作，请忽略本邮件。
                    If you did not request this, please ignore this email.
                    """, code, ttlMinutes),
                    codeSceneHtml("您的验证码", "Your verification code",
                            "验证码", "Verification code", code, ttlMinutes,
                            "如果这不是您本人的操作，请忽略本邮件。",
                            "If you did not request this, please ignore this email."), code);
            case RESET -> new MailMessage(to, subject("密码重置", "Password reset"), plain("""
                    您正在重置 {{product}} 账号密码。重置码：{{code}}（{{ttl}} 分钟内有效）

                    You are resetting your {{product}} password. Reset code: {{code}} (valid for {{ttl}} minutes)

                    如果这不是您本人的操作，请立即检查账号安全并忽略本邮件。
                    If you did not request this, please secure your account and ignore this email.
                    """, code, ttlMinutes),
                    codeSceneHtml("您正在重置账号密码", "You are resetting your account password",
                            "重置码", "Reset code", code, ttlMinutes,
                            "如果这不是您本人的操作，请立即检查账号安全并忽略本邮件。",
                            "If you did not request this, please secure your account and ignore this email."), code);
            case DELETE -> new MailMessage(to, subject("账号注销确认", "Account deletion confirmation"), plain("""
                    您正在注销 {{product}} 账号。确认码：{{code}}（{{ttl}} 分钟内有效）。注销后关联个人数据将按隐私政策清理。

                    You are deleting your {{product}} account. Confirmation code: {{code}} (valid for {{ttl}} minutes).
                    Associated personal data will be purged according to the privacy policy.
                    """, code, ttlMinutes),
                    codeSceneHtml("您正在注销账号", "You are deleting your account",
                            "确认码", "Confirmation code", code, ttlMinutes,
                            "注销后关联个人数据将按隐私政策清理。",
                            "Associated personal data will be purged according to the privacy policy."), code);
            case CHANGE -> new MailMessage(to, subject("更改绑定邮箱", "Email address change"), plain("""
                    您正在更改 {{product}} 账号绑定的邮箱。验证码：{{code}}（{{ttl}} 分钟内有效）

                    You are changing the email address on your {{product}} account. Verification code: {{code}} (valid for {{ttl}} minutes)

                    如果这不是您本人的操作，请立即检查账号安全。
                    If this was not you, please secure your account immediately.
                    """, code, ttlMinutes),
                    codeSceneHtml("您正在更改绑定邮箱", "You are changing the email address on your account",
                            "验证码", "Verification code", code, ttlMinutes,
                            "如果这不是您本人的操作，请立即检查账号安全。",
                            "If this was not you, please secure your account immediately."), code);
        };
    }

    /**
     * 改邮箱通知信（#104 接线，发往老邮箱）：请求/成功两态，无验证码无链接
     */
    public static MailMessage buildEmailChangeNotify(String to, NotifyKind kind) {
        return switch (kind) {
            case REQUESTED -> new MailMessage(to, subject("邮箱更改请求通知", "Email change request notice"), plain("""
                    您的 {{product}} 账号刚刚发起了一次绑定邮箱更改请求。新邮箱验证通过后，账号绑定邮箱将被更换。

                    A request to change the email address on your {{product}} account was just initiated.
                    Once the new email is verified, the account's email address will be replaced.

                    如果这不是您本人的操作，请立即登录并修改密码。
                    If this was not you, please sign in and change your password immediately.
                    """),
                    notifyHtml("绑定邮箱更改请求已发起", "Email change request initiated",
                            "您的账号刚刚发起了一次绑定邮箱更改请求。新邮箱验证通过后，账号绑定邮箱将被更换。",
                            "A request to change the email address on your account was just initiated. "
                                    + "Once the new email is verified, the account's email address will be replaced."),
                    null);
            case COMPLETED -> new MailMessage(to, subject("绑定邮箱已更改", "Email address changed"), plain("""
                    您的 {{product}} 账号绑定邮箱已成功更改。此后验证与通知邮件将发往新邮箱。

                    The email address on your {{product}} account has been changed successfully.
                    Future verification and notification emails will be sent to the new address.

                    如果这不是您本人的操作，请立即登录并修改密码。
                    If this was not you, please sign in and change your password immediately.
                    """),
                    notifyHtml("绑定邮箱已更改", "Email address changed",
                            "您的账号绑定邮箱已成功更改。此后验证与通知邮件将发往新邮箱。",
                            "The email address on your account has been changed successfully. "
                                    + "Future verification and notification emails will be sent to the new address."),
                    null);
        };
    }

    private static String subject(String zh, String en) {
        return PRODUCT + " · " + zh + " / " + en;
    }

    /** 纯文本降级 part：{{token}} 字面展开（code/ttl 通知类不传则不替换） */
    private static String plain(String template) {
        return expand(template.strip(), null, null);
    }

    private static String plain(String template, String code, int ttlMinutes) {
        return expand(template.strip(), code, String.valueOf(ttlMinutes));
    }

    private static String expand(String template, String code, String ttl) {
        String result = template.replace("{{product}}", PRODUCT);
        if (code != null) {
            result = result.replace("{{code}}", code);
        }
        if (ttl != null) {
            result = result.replace("{{ttl}}", ttl);
        }
        return result;
    }

    /** 验证码类 HTML 内容体：说明行 + 大号居中验证码色块 + 有效期 + 安全提示 */
    private static String codeSceneHtml(String titleZh, String titleEn, String codeLabelZh, String codeLabelEn,
                                        String code, int ttlMinutes, String noteZh, String noteEn) {
        String content = ""
                + row("padding:4px 32px 0 32px;", titleStyle(), titleZh + "<br>" + titleEn)
                + codeBlock(codeLabelZh, codeLabelEn, code)
                + row("padding:4px 32px 0 32px;", bodyStyle(),
                ttlMinutes + " 分钟内有效 / valid for " + ttlMinutes + " minutes")
                + securityRow(noteZh, noteEn);
        return shell(content);
    }

    /** 通知类 HTML 内容体：标题 + 说明 + 安全提示（无验证码无链接） */
    private static String notifyHtml(String titleZh, String titleEn, String bodyZh, String bodyEn) {
        String content = ""
                + row("padding:4px 32px 0 32px;", titleStyle(), titleZh + "<br>" + titleEn)
                + row("padding:12px 32px 0 32px;", bodyStyle(), bodyZh + "<br><br>" + bodyEn)
                + securityRow("如果这不是您本人的操作，请立即登录并修改密码。",
                "If this was not you, please sign in and change your password immediately.");
        return shell(content);
    }

    /** 大号居中等宽验证码独立色块（36px ≥ 票面 32px 下限、letter-spacing 加宽） */
    private static String codeBlock(String labelZh, String labelEn, String code) {
        return ""
                + "<tr><td style=\"padding:18px 32px 6px 32px;\">"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" width=\"100%\" style=\""
                + "background-color:#fdf2f3;border:1px solid " + RED + ";border-radius:6px;\">"
                + "<tr><td align=\"center\" style=\"padding:16px 8px 6px 8px;" + FONT
                + "font-size:13px;color:#6b7280;\">" + labelZh + " / " + labelEn + "</td></tr>"
                + "<tr><td align=\"center\" style=\"padding:2px 8px 18px 8px;" + MONO_FONT
                + "font-size:36px;font-weight:bold;letter-spacing:10px;color:" + RED + ";\">" + code + "</td></tr>"
                + "</table></td></tr>";
    }

    private static String securityRow(String zh, String en) {
        return row("padding:16px 32px 8px 32px;", "font-size:13px;line-height:1.7;color:#6b7280;" + FONT,
                zh + "<br>" + en);
    }

    private static String row(String padding, String style, String inner) {
        return "<tr><td style=\"" + padding + style + "\">" + inner + "</td></tr>";
    }

    private static String titleStyle() {
        return "font-size:17px;font-weight:bold;line-height:1.6;color:#1f2937;" + FONT;
    }

    private static String bodyStyle() {
        return "font-size:14px;line-height:1.8;color:#374151;" + FONT;
    }

    /**
     * 统一 HTML 外壳：PolyU 红顶条 + 品牌头 + 内容区 + 自动邮件页脚。
     * 全内联样式、表格布局；doctype 不带 xmlns（零链接断言连 http 子串都不允许出现）
     */
    private static String shell(String contentRows) {
        return """
                <!DOCTYPE html>
                <html lang="zh">
                <body style="margin:0;padding:0;background-color:#f4f4f5;">
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background-color:#f4f4f5;">
                <tr><td align="center" style="padding:24px 12px;">
                <table role="presentation" cellpadding="0" cellspacing="0" style="max-width:560px;width:100%;background-color:#ffffff;border-radius:8px;overflow:hidden;">
                <tr><td style="background-color:{{red}};height:6px;font-size:0;line-height:6px;">&nbsp;</td></tr>
                <tr><td style="padding:26px 32px 10px 32px;{{font}}">
                <span style="font-size:20px;font-weight:bold;color:#1f2937;">{{product}}</span>
                </td></tr>
                {{content}}
                <tr><td style="padding:20px 32px 26px 32px;border-top:1px solid #e5e7eb;font-size:12px;line-height:1.8;color:#9ca3af;{{font}}">
                此邮件由系统自动发送，请勿直接回复。This is an automated message; please do not reply.<br>
                {{product}} 为非官方项目，与香港理工大学无隶属关系。{{product}} is an unofficial project and is not affiliated with The Hong Kong Polytechnic University.
                </td></tr>
                </table>
                </td></tr>
                </table>
                </body>
                </html>
                """
                .replace("{{red}}", RED)
                .replace("{{font}}", FONT)
                .replace("{{product}}", PRODUCT)
                .replace("{{content}}", contentRows);
    }
}
