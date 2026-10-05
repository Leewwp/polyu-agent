// @vitest-environment node
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * #228 用户面主色反转——主色 token 合同（源文件锚定，jsdom css:false 不加载样式表，
 * 故按 staticPublicAssets.test.ts 先例直接断言样式源）：
 * 1. :root --primary 反转为品牌红，且精确对齐 --polyu-red #a6192e（HSL 回转校验）；
 * 2. --ring/--glow 等引用主色的变量同步反转（聚焦环随主色属预期）；
 * 3. .admin-layout 作用域显式保紫（后台 DOM 轨）；
 * 4. body[data-ui-theme="admin"] 作用域保紫（Radix Portal 弹层轨，弹层直挂 body
 *    不在 .admin-layout 根内）；
 * 5. Button 默认变体的 shadow-glow 由硬编码蓝改由 --primary 派生（红紫两主题联动）。
 */

const css = readFileSync(join(process.cwd(), "src/styles/globals.css"), "utf8");
const tailwindConfig = readFileSync(join(process.cwd(), "tailwind.config.cjs"), "utf8");

/** 取选择器首个声明块原文（花括号配平；要求选择器后紧跟 `{`，跳过注释中的同名提及），多 :root 块时命中文件首个） */
function cssBlock(selector: string): string {
  const re = new RegExp(`${selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\s*\\{`);
  const found = re.exec(css);
  expect(found, `globals.css 应包含选择器 ${selector} 的规则`).toBeTruthy();
  const open = css.indexOf("{", found!.index);
  let depth = 0;
  for (let i = open; i < css.length; i++) {
    if (css[i] === "{") depth++;
    if (css[i] === "}") {
      depth--;
      if (depth === 0) return css.slice(open + 1, i);
    }
  }
  throw new Error(`选择器 ${selector} 的声明块未闭合`);
}

/** 声明块中某自定义属性的值（去注释后首个匹配） */
function decl(block: string, prop: string): string {
  const cleaned = block.replace(/\/\*[\s\S]*?\*\//g, "");
  const match = cleaned.match(new RegExp(`--${prop}:\\s*([^;]+);`));
  expect(match, `声明块应定义 --${prop}`).toBeTruthy();
  return match![1].trim();
}

/** CSS Color 4 空间语法 hsl(h s% l%) → sRGB（与浏览器换算一致，四舍五入到 0-255） */
function hslToHex(h: number, s: number, l: number): string {
  const c = (1 - Math.abs(2 * l - 1)) * s;
  const hp = h / 60;
  const x = c * (1 - Math.abs((hp % 2) - 1));
  const [r, g, b] =
    hp < 1 ? [c, x, 0] : hp < 2 ? [x, c, 0] : hp < 3 ? [0, c, x] : hp < 4 ? [0, x, c] : hp < 5 ? [x, 0, c] : [c, 0, x];
  const m = l - c / 2;
  return [r, g, b]
    .map((v) => Math.round((v + m) * 255).toString(16).padStart(2, "0"))
    .join("")
    .toUpperCase();
}

const RED = "351 73.8% 37.5%";
const PURPLE = "239 84% 67%";

describe("#228 主色 token 合同（globals.css / tailwind.config.cjs）", () => {
  it(":root --primary 反转为品牌红，且精确对齐 --polyu-red #a6192e", () => {
    const root = cssBlock(":root");
    expect(decl(root, "primary")).toBe(RED);
    expect(root).not.toContain(`--primary: ${PURPLE}`);
    // 品牌锚：换算回 sRGB 必须逐字节等于站点品牌红（hsl 回转防漂移）
    expect(hslToHex(351, 0.738, 0.375)).toBe("A6192E");
    expect(css).toContain("--polyu-red: #a6192e");
  });

  it(":root --ring/--glow 与 --primary 同步反转；红底白字前景不变", () => {
    const root = cssBlock(":root");
    expect(decl(root, "ring")).toBe(RED);
    expect(decl(root, "glow")).toBe(RED);
    expect(decl(root, "primary-foreground")).toBe("0 0% 100%");
  });

  it(".admin-layout 作用域显式保紫（后台 DOM 轨；#259 起三值引 :root --admin-* 单源）", () => {
    const admin = cssBlock(".admin-layout");
    const root = cssBlock(":root");
    // 单源值钉在 :root（改值只动一处），两作用域引用同一变量
    expect(decl(root, "admin-primary")).toBe(PURPLE);
    expect(decl(root, "admin-primary-foreground")).toBe("0 0% 100%");
    expect(decl(root, "admin-glow")).toBe(PURPLE);
    expect(decl(admin, "primary")).toBe("var(--admin-primary)");
    expect(decl(admin, "primary-foreground")).toBe("var(--admin-primary-foreground)");
    expect(decl(admin, "glow")).toBe("var(--admin-glow)");
    // 后台布局自身的 224 焦点环为现状，保留不动
    expect(decl(admin, "ring")).toBe("224 76% 48%");
  });

  it("body[data-ui-theme=\"admin\"] 作用域保紫（Radix Portal 弹层轨，挂 body 的弹层继承；同引 --admin-* 单源）", () => {
    const portal = cssBlock('body[data-ui-theme="admin"]');
    expect(decl(portal, "primary")).toBe("var(--admin-primary)");
    expect(decl(portal, "primary-foreground")).toBe("var(--admin-primary-foreground)");
    expect(decl(portal, "glow")).toBe("var(--admin-glow)");
    expect(decl(portal, "ring")).toBe(PURPLE);
  });

  it("Button 默认变体 shadow-glow 由 --primary 派生，不再硬编码蓝", () => {
    expect(tailwindConfig).toContain(
      'glow: "0 0 0 1px hsl(var(--primary) / 0.2), 0 16px 40px hsl(var(--primary) / 0.25)"'
    );
    const glowLine = tailwindConfig.split("\n").find((line) => line.includes('glow: "0 0 0'));
    expect(glowLine).not.toContain("rgba(59, 130, 246");
  });

  it("outline/destructive 变体不走 --primary（反转不受影响）", () => {
    const button = readFileSync(join(process.cwd(), "src/components/ui/button.tsx"), "utf8");
    const outline = /outline:\s*"([^"]*)"/.exec(button)![1];
    const destructive = /destructive:\s*"([^"]*)"/.exec(button)![1];
    expect(outline).not.toContain("primary");
    expect(destructive).not.toContain("primary");
    expect(destructive).toContain("bg-destructive");
    // 默认变体继续以主色变量为底（反转后用户面红、后台两轨紫）
    const def = /default:\s*"([^"]*)"/.exec(button)![1];
    expect(def).toContain("bg-primary text-primary-foreground");
  });
});
