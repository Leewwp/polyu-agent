// @vitest-environment node
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * #235 N4 对比度收口合同（源文件锚定 + WCAG 比值机算留痕）：
 * 页脚版权行与辅助小字（--feed-text-tertiary）两处灰值统一收口至 #6f6f74，
 * 按实际使用面背景验收——feed 底 #f6f6f7 与白卡 #ffffff 均 ≥4.5:1（AA 正文）。
 * 两值使用面均无 opacity/透明合成（FeedFooter 行内与 token 引用处无透明类），
 * 故直接以前景/背景原值计算，无需合成色换算。
 */

const css = readFileSync(join(process.cwd(), "src/styles/globals.css"), "utf8");
const footer = readFileSync(
  join(process.cwd(), "src/components/feed/FeedFooter.tsx"),
  "utf8"
);

function srgb(hex: string): number[] {
  const h = hex.replace("#", "");
  return [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
}

function channel(c: number): number {
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

/** WCAG 2.x 相对亮度（sRGB，与浏览器/对比度工具换算一致） */
function luminance(hex: string): number {
  const [r, g, b] = srgb(hex).map(channel);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG 2.x 对比度比值 */
function ratio(fg: string, bg: string): number {
  const l1 = luminance(fg);
  const l2 = luminance(bg);
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1];
  return (hi + 0.05) / (lo + 0.05);
}

const FEED_BG = "#f6f6f7";
const WHITE_CARD = "#ffffff";

describe("#235 N4 灰值收口（fg/bg/比值留痕）", () => {
  it("globals.css --feed-text-tertiary 与 FeedFooter © 行均收口至 #6f6f74", () => {
    expect(css).toContain("--feed-text-tertiary: #6f6f74");
    expect(css).not.toContain("--feed-text-tertiary: #8e8e96");
    // 断言类字面量（注释里的原值记录不参与判定）
    expect(footer).toContain("text-[#6f6f74]");
    expect(footer).not.toContain("text-[#B4B4BC]");
  });

  it("新值 #6f6f74 对实际使用面背景（feed 底/白卡）均 ≥4.5:1", () => {
    expect(ratio("#6f6f74", FEED_BG)).toBeCloseTo(4.627, 3);
    expect(ratio("#6f6f74", WHITE_CARD)).toBeCloseTo(4.998, 3);
  });

  it("原值确实不过线（复核记录：为何收口）", () => {
    expect(ratio("#8e8e96", FEED_BG)).toBeCloseTo(3.010, 3);
    expect(ratio("#B4B4BC", FEED_BG)).toBeCloseTo(1.907, 3);
  });

  it("视觉层级不塌：primary > secondary > tertiary 的亮度阶梯保留", () => {
    const tertiaryOnFeed = ratio("#6f6f74", FEED_BG);
    const secondaryOnFeed = ratio("#52525b", FEED_BG);
    const primaryOnFeed = ratio("#18181b", FEED_BG);
    expect(secondaryOnFeed).toBeCloseTo(7.157, 3);
    expect(primaryOnFeed).toBeCloseTo(16.404, 3);
    expect(primaryOnFeed).toBeGreaterThan(secondaryOnFeed);
    expect(secondaryOnFeed).toBeGreaterThan(tertiaryOnFeed);
    expect(tertiaryOnFeed).toBeGreaterThanOrEqual(4.5);
  });
});
