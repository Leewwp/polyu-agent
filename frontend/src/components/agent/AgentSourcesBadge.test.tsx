import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";

import { AgentSourcesBadge } from "./AgentSourcesBadge";

const sources = [
  { docId: "doc-42", docName: "图书馆服务指南", excerpt: "游泳池开放时间为早七至晚十…" },
  { docId: "doc-43", docName: "校园设施手册", excerpt: "体育馆分层介绍…" },
  { docId: "doc-44", docName: "学生事务处指引", excerpt: "办理学生证流程…" },
  { docId: "doc-45", docName: "第四篇超预览数量", excerpt: "不出现在图标预览里" }
];

describe("AgentSourcesBadge", () => {
  afterEach(() => {
    cleanup();
  });

  it("renders null when sources are absent or empty", () => {
    const { container } = render(<AgentSourcesBadge sources={undefined} />);
    expect(container.firstChild).toBeNull();

    const { container: empty } = render(<AgentSourcesBadge sources={[]} />);
    expect(empty.firstChild).toBeNull();
  });

  it("collapsed badge shows count and expands to inline list with doc links", () => {
    render(<AgentSourcesBadge sources={sources} />);

    const badge = screen.getByText("4 篇来源").closest("button");
    expect(badge).toBeTruthy();
    expect(badge?.getAttribute("aria-expanded")).toBe("false");
    // 折叠态只有计数 无列表
    expect(screen.queryByText("图书馆服务指南")).toBeNull();

    fireEvent.click(badge as HTMLElement);

    expect(badge?.getAttribute("aria-expanded")).toBe("true");
    expect(screen.getByText("图书馆服务指南")).toBeTruthy();
    expect(screen.getByText("游泳池开放时间为早七至晚十…")).toBeTruthy();
    const links = screen.getAllByText("查看原文");
    expect(links).toHaveLength(4);
    expect(links[0].getAttribute("href")).toBe("/preview/doc/doc-42");
    // 四篇来源全部列出
    expect(screen.getByText("第四篇超预览数量")).toBeTruthy();
  });

  it("two-state links per doc 32 decision 1: url-type external view, file-type download button", () => {
    render(
      <AgentSourcesBadge
        sources={[
          {
            docId: "doc-1",
            docName: "图书馆开放时间",
            excerpt: "…",
            sourceType: "url",
            url: "https://www.polyu.edu.hk/library/hours/"
          },
          {
            docId: "doc-2",
            docName: "Student_Handbook_2026-27_English.pdf",
            excerpt: "第一章全文段落…",
            sourceType: "file",
            url: "https://www.polyu.edu.hk/ar/student-handbook/"
          }
        ]}
      />
    );
    fireEvent.click(screen.getByText("2 篇来源").closest("button") as HTMLElement);

    // url 型：查看原文外链官网页（不开站内预览）
    const viewOriginal = screen.getByText("查看原文 ↗");
    expect(viewOriginal.getAttribute("href")).toBe("https://www.polyu.edu.hk/library/hours/");
    expect(viewOriginal.getAttribute("target")).toBe("_blank");

    // file 型：底部「获取《手册》」按钮外链官网下载页，PDF 文件名去扩展名
    const download = screen.getByText("获取《Student_Handbook_2026-27_English》↗");
    expect(download.getAttribute("href")).toBe("https://www.polyu.edu.hk/ar/student-handbook/");
    expect(download.getAttribute("target")).toBe("_blank");
  });
});
