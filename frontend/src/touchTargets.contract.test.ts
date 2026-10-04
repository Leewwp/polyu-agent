// @vitest-environment node
import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * #232 触控目标与 iOS 输入——源码锚定合同（jsdom 不加载 tailwind 样式表，
 * 按 staticPublicAssets.test.ts / globals.primaryTheme.test.ts / feedContrast.test.ts
 * 项目先例直接断言样式源）：
 * 1. 44px 主清单（Button 默认 h-10/40px 逐处抬升，不动全局——后台不受连带的票面分工）：
 *    移动菜单钮/语言切换/移动登录钮/FAB/侧栏导航条目/资讯详情底部操作排（含分享钮）/
 *    用户面主要表单提交钮（登录/注册/找回/账号/反馈）/轻量页主要操作（继续提问/返回/
 *    回到首页/分享失效 CTA/agent 分享 CTA）；
 * 2. 辅助链接 ≥24px 命中面：卡片原文链/页脚两套链接/多选入口（留白外扩不靠装饰容器）；
 * 3. ≤860px 可聚焦输入 ≥16px（两档聊天输入+登录/注册/找回/账号/反馈表单）——
 *    根治 iPhone 聚焦自动页面放大，不用 maximum-scale 折衷（63a D/N1）。
 */

const read = (rel: string): string => readFileSync(join(process.cwd(), rel), "utf8");

describe("touch targets ≥44px（主清单源锚定）", () => {
  it("FeedShell：语言切换 pill 与移动菜单钮", () => {
    const s = read("src/components/feed/FeedShell.tsx");
    expect(s).toContain('"flex min-h-[44px] items-center px-3"');
    expect(s).toContain("h-11 w-11 flex-none items-center justify-center rounded-lg");
  });

  it("MobileTabbar：FAB ≥44（底部 tab min-h-44 由 #230 落地，此处只增量）", () => {
    expect(read("src/components/feed/MobileTabbar.tsx")).toContain(
      "px-[18px] py-[11px] min-h-[44px]"
    );
  });

  it("UserMenu：移动登录钮 ≥44", () => {
    expect(read("src/components/feed/UserMenu.tsx")).toContain(
      '"flex min-h-[44px] flex-none items-center justify-center px-3 text-[12px]"'
    );
  });

  it("FeedSidebar：导航条目 ≥44、多选入口 ≥24 档", () => {
    const s = read("src/components/feed/FeedSidebar.tsx");
    expect(s).toContain('"flex min-h-[44px] cursor-pointer items-center gap-2.5');
    expect(s).toContain("rounded-md px-2 py-1.5 text-[11px]");
  });

  it("资讯详情底部操作排：原文钮 + 分享钮 ≥44", () => {
    const s = read("src/pages/NewsDetailPage.tsx");
    expect(s).toContain("min-h-[44px] px-[15px] text-[13px] font-semibold");
    expect(s).toContain('<ShareButton item={item} zh={zh} className="h-11 w-11" />');
  });

  it("用户面主要表单提交钮 ≥44（登录/注册/找回/账号/反馈）", () => {
    expect(read("src/pages/LoginPage.tsx")).toContain('className="min-h-[44px] w-full"');
    const reg = read("src/pages/RegisterPage.tsx");
    expect(reg).toContain('className="min-h-[44px] w-full"');
    expect(reg).toContain('className="min-h-[44px] flex-1"');
    const forgot = read("src/pages/ForgotPasswordPage.tsx");
    expect(forgot).toContain('className="min-h-[44px] w-full" disabled={!canRequest}');
    expect(forgot).toContain('className="min-h-[44px] w-full" disabled={!canReset}');
    const account = read("src/pages/AccountPage.tsx");
    expect(account.match(/<Button type="submit" className="min-h-\[44px\]" disabled=/g)?.length).toBe(3);
    const feedback = read("src/components/site/FeedbackDialog.tsx");
    expect(feedback).toContain('<Button variant="outline" className="min-h-[44px]" onClick={onClose}>');
    expect(feedback).toContain('<Button className="min-h-[44px]" onClick={handleSubmit}');
  });

  it("轻量页主要操作 ≥44（404/法务返回/分享页失效 CTA/继续提问出口）", () => {
    expect(read("src/pages/NotFoundPage.tsx")).toContain('className="mt-6 min-h-[44px]"');
    expect(read("src/components/legal/LegalShell.tsx")).toContain(
      'className="min-h-[44px] text-[#666666]"'
    );
    const share = read("src/pages/SharePage.tsx");
    expect(share).toContain('className="mt-2 min-h-[44px]"');
    expect(share.match(/cn\("min-h-\[44px\]", className\)/g)?.length).toBe(2);
    const css = read("src/styles/globals.css");
    expect(css).toMatch(/\.agent-app \.agent-share-cta-primary \{[^}]*min-height: 44px;/s);
    expect(css).toMatch(/\.agent-app \.agent-share-cta-secondary \{[^}]*min-height: 44px;/s);
  });
});

describe("辅助链接 ≥24px 命中面（留白外扩）", () => {
  it("NewsCard 原文链接 py-[6px]", () => {
    expect(read("src/components/feed/NewsCard.tsx")).toContain(
      "gap-1 py-[6px] text-[12.5px] font-semibold"
    );
  });

  it("页脚两套链接与多选入口", () => {
    expect(read("src/components/feed/FeedFooter.tsx")).toContain(
      '"-my-1.5 py-1.5 font-semibold hover:text-[var(--polyu-red-dark)]"'
    );
    expect(read("src/components/layout/SiteFooter.tsx")).toContain('"-my-1.5 py-1.5 hover:underline"');
  });
});

describe("≤860px 可聚焦输入 ≥16px（iOS 聚焦缩放根治）", () => {
  it("ui-input 基类：登录/注册/找回/账号/反馈全系", () => {
    expect(read("src/components/ui/input.tsx")).toContain("text-sm max-[860px]:text-[16px]");
  });

  it("两档聊天输入：workflow 档组件类 + agent 档 composer CSS", () => {
    expect(read("src/components/chat/ChatInput.tsx")).toContain(
      "text-[15px] max-[860px]:text-[16px]"
    );
    const css = read("src/styles/globals.css");
    expect(css).toMatch(
      /@media \(max-width: 860px\) \{\s*\.agent-app \.agent-composer-input \{\s*font-size: 16px;\s*\}/
    );
  });

  it("不用 maximum-scale 折衷（viewport meta 不动）", () => {
    const html = read("index.html");
    expect(html).not.toContain("maximum-scale");
  });
});
