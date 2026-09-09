import { LegalSection, LegalShell } from "@/components/legal/LegalShell";

/**
 * 非官方声明（U8，doc 15 §2.2.10 三链接之一）：口径对齐 SharePage 黄条与 U10 聊天声明行。
 */
export function DisclaimerPage() {
  return (
    <LegalShell title="非官方声明" titleEn="Unofficial Service Disclosure">
      <LegalSection heading="与香港理工大学的关系" headingEn="Relationship with PolyU">
        <p>
          PolyU Wayfinder 是一个由个人维护的非官方项目，与香港理工大学（The Hong Kong Polytechnic
          University）无隶属、授权或赞助关系。「PolyU」名称及大学标识归其权利人所有，此处仅作说明性引用。
        </p>
        <p className="text-muted-foreground">
          PolyU Wayfinder is an unofficial project maintained by an individual. It is not affiliated
          with, endorsed, or sponsored by The Hong Kong Polytechnic University. The name
          &quot;PolyU&quot; and university marks belong to their respective owners and are used here
          for identification only.
        </p>
      </LegalSection>

      <LegalSection heading="内容准确性" headingEn="Content accuracy">
        <p>
          回答由 AI
          基于公开来源生成，可能过期、不完整或有误。招生、课程、宿舍、政策等关键信息请始终以 PolyU
          官方网站与官方渠道为准。
        </p>
        <p className="text-muted-foreground">
          Answers are AI-generated from public sources and may be outdated, incomplete, or
          incorrect. For admissions, programmes, housing, and policies, always verify against
          official PolyU websites and channels.
        </p>
      </LegalSection>
    </LegalShell>
  );
}
