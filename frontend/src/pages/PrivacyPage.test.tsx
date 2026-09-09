import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";

import { PrivacyPage } from "./PrivacyPage";

/**
 * U8 验收门的机检面：doc 15 §2.2.10 隐私声明清单逐条存在
 * （email/对话/IP 30 天/cookie 用途、第三方模型传输披露、注销权、联系方式）+ 双语段落。
 */
describe("PrivacyPage", () => {
  afterEach(() => {
    cleanup();
  });

  it("covers every §2.2.10 disclosure item in Chinese", () => {
    render(
      <MemoryRouter>
        <PrivacyPage />
      </MemoryRouter>
    );

    const body = document.body.textContent ?? "";
    expect(body).toContain("注册邮箱");
    expect(body).toContain("30 天");
    expect(body).toContain("cookie");
    expect(body).toContain("第三方大模型");
    expect(body).toContain("注销");
    expect(body).toContain("wayfinder@polyuguide.com");
  });

  it("mirrors disclosures in English", () => {
    render(
      <MemoryRouter>
        <PrivacyPage />
      </MemoryRouter>
    );

    const body = document.body.textContent ?? "";
    expect(body).toContain("What we collect");
    expect(body).toContain("Third-party");
    expect(body).toContain("30 days");
    expect(body).toContain("Right to delete your account");
  });
});
