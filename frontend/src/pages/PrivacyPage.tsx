import { LegalSection, LegalShell } from "@/components/legal/LegalShell";

const CONTACT_EMAIL = "ppp@polyuguide.com";

/**
 * 隐私声明（清单逐条）：收集什么（email/对话/IP 30 天/cookie 用途）、
 * 第三方模型传输披露、注销权、联系方式。双语文案沿用现状内联双语（先例=SharePage）。
 */
export function PrivacyPage() {
  return (
    <LegalShell title="隐私声明" titleEn="Privacy Notice">
      <LegalSection heading="我们收集什么" headingEn="What we collect">
        <p>
          注册邮箱（仅登录用户；游客试用无需提供任何个人信息）；对话与消息内容（用于提供问答服务：登录用户的记录保留至账号注销，游客对话保留
          30 天）；IP 地址与基本访问信息（仅用于安全防护与反滥用，访问类记录保留不超过 30
          天）；必要的 cookie（仅用于保持登录会话与防滥用配额控制，不用于广告或第三方跟踪）。
        </p>
        <p className="text-muted-foreground">
          Registered email address (signed-in users only; guest trial requires no personal
          information); conversation and message content (to provide the Q&amp;A service: records of
          signed-in users are kept until account deletion, guest conversations are kept for 30
          days); IP address and basic access information (used solely for security and anti-abuse,
          access records are kept for no more than 30 days); strictly necessary cookies (login
          session and abuse-prevention quota control only — never for advertising or third-party
          tracking).
        </p>
      </LegalSection>

      <LegalSection heading="第三方模型传输" headingEn="Third-party model transmission">
        <p>
          为生成回答，你的提问内容及相关检索片段会发送给第三方大模型 API
          供应商处理。请勿在提问中输入身份证件、银行账户等敏感个人信息。
        </p>
        <p className="text-muted-foreground">
          To generate answers, your question and related retrieved passages are sent to third-party
          large-model API providers. Please do not enter sensitive personal information such as ID
          numbers or bank accounts in your questions.
        </p>
      </LegalSection>

      <LegalSection heading="账号注销权" headingEn="Right to delete your account">
        <p>
          登录用户可随时自助注销账号：密码确认后进入 30
          天冷静期（期间可登录撤销）；到期后账号与对话记录将被删除，反馈记录匿名化保留，注册邮箱以
          SHA-256 哈希形式保留 180
          天用于防止重复注册滥用。游客无需注销——停止使用即可，其对话与账号在 30 天后自动清理。
        </p>
        <p className="text-muted-foreground">
          Signed-in users may delete their own account at any time: after password confirmation a
          30-day grace period starts (during which you can sign in to cancel); after it expires the
          account and conversations are deleted, feedback records are anonymized, and the registered
          email is kept as a SHA-256 hash for 180 days to prevent registration abuse. Guests need no
          deletion — stop using the service and guest conversations and accounts are cleaned up
          automatically after 30 days.
        </p>
      </LegalSection>

      <LegalSection heading="联系方式" headingEn="Contact">
        <p>
          对本声明或数据处理有疑问，请联系：
          <a className="underline" href={`mailto:${CONTACT_EMAIL}`}>
            {CONTACT_EMAIL}
          </a>
        </p>
        <p className="text-muted-foreground">
          Questions about this notice or our data handling:{" "}
          <a className="underline" href={`mailto:${CONTACT_EMAIL}`}>
            {CONTACT_EMAIL}
          </a>
        </p>
      </LegalSection>
    </LegalShell>
  );
}
