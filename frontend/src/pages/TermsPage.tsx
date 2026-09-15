import { LegalSection, LegalShell } from "@/components/legal/LegalShell";

/**
 * 服务条款（「服务条款」链接落点）：简明版，与隐私声明/非官方声明互援。
 */
export function TermsPage() {
  return (
    <LegalShell title="服务条款" titleEn="Terms of Service">
      <LegalSection heading="服务性质" headingEn="Nature of the service">
        <p>
          本服务基于检索增强生成技术提供校园信息问答。所有回答由 AI
          生成，仅供参考，不构成香港理工大学或任何机构的官方意见；重要事项（申请截止、课程要求、政策等）请以
          PolyU 官方页面为准，并通过官方渠道办理。
        </p>
        <p className="text-muted-foreground">
          This service provides campus information Q&amp;A using retrieval-augmented generation. All
          answers are AI-generated and for reference only; they do not represent official views of
          The Hong Kong Polytechnic University or any institution. For important matters
          (application deadlines, programme requirements, policies), rely on official PolyU pages
          and channels.
        </p>
      </LegalSection>

      <LegalSection heading="使用条件" headingEn="Acceptable use">
        <p>
          使用本服务即表示你同意：仅作合法与个人用途；不滥用试用配额、不批量注册账号、不以自动化手段高频访问；不对服务进行干扰性测试或试图未授权访问他人数据。违反上述条件时，我们可能限制或终止服务访问。
        </p>
        <p className="text-muted-foreground">
          By using this service you agree to: lawful, personal use only; no abuse of trial quotas,
          bulk account creation, or automated high-frequency access; no disruptive testing or
          attempts to access others&apos; data without authorization. We may restrict or terminate
          access when these conditions are violated.
        </p>
      </LegalSection>

      <LegalSection heading="服务变更与中断" headingEn="Changes and availability">
        <p>
          本服务持续演进，功能与内容可能随时调整、暂停或终止，恕不另行通知。数据收集与处理方式见
          <a className="underline" href="/privacy">
            隐私声明
          </a>
          ；服务与 PolyU 的关系见
          <a className="underline" href="/disclaimer">
            非官方声明
          </a>
          。
        </p>
        <p className="text-muted-foreground">
          The service evolves continuously; features and content may change, pause, or terminate at
          any time without notice. See the{" "}
          <a className="underline" href="/privacy">
            Privacy Notice
          </a>{" "}
          for data handling, and the{" "}
          <a className="underline" href="/disclaimer">
            Disclaimer
          </a>{" "}
          for our relationship with PolyU.
        </p>
      </LegalSection>
    </LegalShell>
  );
}
