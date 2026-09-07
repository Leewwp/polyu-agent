import { describe, expect, it } from "vitest";

import { markdownToPlainText } from "@/lib/markdownToText";

describe("markdownToPlainText", () => {
  it("strips common markdown syntax", () => {
    const input = "# 标题\n\n**粗体** 与 _斜体_\n\n- 列表项\n\n[链接](https://example.com)";
    const output = markdownToPlainText(input);
    expect(output).not.toContain("#");
    expect(output).not.toContain("**");
    expect(output).toContain("标题");
    expect(output).toContain("粗体");
    expect(output).toContain("列表项");
  });

  it("keeps CJK content intact", () => {
    const output = markdownToPlainText("香港理工大学住宿申请流程");
    expect(output).toContain("香港理工大学");
  });
});
