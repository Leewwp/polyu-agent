import { useEffect } from "react";

import { useOptionalFeedLang } from "@/components/feed/feedLang";
import type { FeedLang } from "@/components/feed/feedLang";

/**
 * 全站 document.title 单源机制（#231，63a C + R2）：
 * 「页面名 · PolyUGuide」由 base（页面名）+ override（详情实际标题）两值汇聚——
 * - `usePageTitle`：页面名（资讯壳 FeedShell 内部消费 title/fluid/shareView 优先值；
 *   壳外轻量页——登录/注册/找回/账号/404/法务/答案分享/变更审计/文档预览——各自调用）；
 * - `useDetailPageTitle`：详情实际标题在同一调用路径作**优先值**——后设覆盖、
 *   清理时回落父级页面名；父壳 effect 只写 base，经 apply 汇聚，父/详情两个
 *   effect 不再相互覆写。慢响应由页面 alive/cancelled 守卫拦在 setState 之前，
 *   离开页面或换参数后不写回旧标题。
 * 加载失败/标题缺失回落稳定页名，不展示空值；双语页面名随语言即时跟随
 * （语言切换重跑挂载页的 effect → 重新解析+apply）。
 */

export const SITE_TITLE_SUFFIX = "PolyUGuide";

export type BilingualTitle = { zh: string; en: string };
/** string = 不随 UI 语言翻译的实际标题（快照标题/文档名） */
export type LocalizedTitle = BilingualTitle | string;

function resolveTitle(value: LocalizedTitle, lang: FeedLang): string {
  return typeof value === "string" ? value : lang === "zh" ? value.zh : value.en;
}

/** 模块级单源：base=当前页面名；override=详情优先值；lang=最近一次生效语言 */
let baseTitle: LocalizedTitle | null = null;
let overrideTitle: LocalizedTitle | null = null;
let overrideOwner = 0;
let currentLang: FeedLang = "zh";

function applyTitle(): void {
  const detail = overrideTitle ? resolveTitle(overrideTitle, currentLang).trim() : "";
  const page = baseTitle ? resolveTitle(baseTitle, currentLang).trim() : "";
  const name = detail || page;
  if (!name) {
    // 空值保护：base/override 全空时不写空标题（保持上一稳定值）
    return;
  }
  document.title = `${name} · ${SITE_TITLE_SUFFIX}`;
}

/** 双语双键依赖（对象字面量每渲染换引用，按 zh/en 文本判变化——两语言任一变即重跑） */
function titleDeps(title: LocalizedTitle | null): [string, string] {
  if (title === null) {
    return ["\0null", "\0null"];
  }
  return typeof title === "string" ? [title, title] : [title.zh, title.en];
}

/**
 * 页面名单源 hook：设置「页面名 · PolyUGuide」的 base。
 * 不在卸载时清 base——清理期/未接入页面（/admin 不在本票）回落最近页面名，
 * 下一挂载页的 effect 即刻覆写。
 */
export function usePageTitle(title: LocalizedTitle): void {
  const { lang } = useOptionalFeedLang();
  const [zhText, enText] = titleDeps(title);
  useEffect(() => {
    baseTitle = title;
    currentLang = lang;
    applyTitle();
    // title 经 zh/en 双键捕获变化（见 titleDeps）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [zhText, enText, lang]);
}

/**
 * 详情实际标题优先值 hook：valued 即覆盖页面名，null/空回落父级页面名；
 * 卸载清理回落（token 守卫——只清自己登记的值，不误清后挂页面的新 override）。
 */
export function useDetailPageTitle(title: LocalizedTitle | null): void {
  const { lang } = useOptionalFeedLang();
  const [zhText, enText] = titleDeps(title);
  useEffect(() => {
    const owner = ++overrideOwner;
    overrideTitle = zhText.trim() || enText.trim() ? title : null;
    currentLang = lang;
    applyTitle();
    return () => {
      if (owner === overrideOwner) {
        overrideTitle = null;
        applyTitle();
      }
    };
    // title 经 zh/en 双键捕获变化（见 titleDeps）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [zhText, enText, lang]);
}

/** 测试隔离：清模块级单源（仅测试装配使用） */
export function resetPageTitleForTests(): void {
  baseTitle = null;
  overrideTitle = null;
  overrideOwner += 1;
  currentLang = "zh";
}
