import { describe, expect, it } from "vitest";

import { isSafeUrl } from "./urlSafety";

/** L30 外链安全闸：仅 http/https 绝对地址放行。 */
describe("isSafeUrl", () => {
  it("http/https 绝对地址放行", () => {
    expect(isSafeUrl("https://polyuguide.com/minio/x.jpg")).toBe(true);
    expect(isSafeUrl("http://example.com/a")).toBe(true);
    expect(isSafeUrl("https://example.com")).toBe(true);
  });

  it("危险协议与畸形值一律拒绝", () => {
    expect(isSafeUrl("javascript:alert(1)")).toBe(false);
    expect(isSafeUrl("JaVaScRiPt:alert(1)")).toBe(false);
    expect(isSafeUrl("data:text/html;base64,PHNjcmlwdD4=")).toBe(false);
    expect(isSafeUrl("vbscript:msgbox")).toBe(false);
    expect(isSafeUrl("file:///etc/passwd")).toBe(false);
    expect(isSafeUrl("")).toBe(false);
    expect(isSafeUrl(null)).toBe(false);
    expect(isSafeUrl(undefined)).toBe(false);
    expect(isSafeUrl("not a url")).toBe(false);
  });

  it("相对路径不放行（站内路径由我方代码自行构造，不经此闸）", () => {
    expect(isSafeUrl("/preview/doc/1")).toBe(false);
    expect(isSafeUrl("//evil.com/x")).toBe(false);
  });
});
