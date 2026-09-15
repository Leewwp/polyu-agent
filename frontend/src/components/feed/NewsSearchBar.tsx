import { useEffect, useState } from "react";

import { useFeedLang } from "./feedLang";

/**
 * 资讯检索框（aihot 形态）：分类 chips 行右侧「输入框+搜索钮」；
 * 移动端（≤860px）由 FeedPage 包裹层换行到 chips 下一行全宽。
 * 提交后由页面写入 `?q=`（不新建路由）；清空提交=退出检索回落原视图。
 * 入口仅「精选」与「全部资讯」两视图（部署方指定，热点榜/主题页不放）。
 */
export function NewsSearchBar({ value, onSubmit }: { value: string; onSubmit: (q: string) => void }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const [text, setText] = useState(value);

  // 外部回落（URL 清 q、切页重进）时同步输入框
  useEffect(() => {
    setText(value);
  }, [value]);

  const submit = (q: string) => {
    onSubmit(q.trim());
  };

  return (
    <form
      className="flex w-full items-center gap-1.5"
      onSubmit={(event) => {
        event.preventDefault();
        submit(text);
      }}
    >
      <input
        type="search"
        value={text}
        aria-label={zh ? "搜索理大资讯" : "Search PolyU news"}
        placeholder={zh ? "搜索标题与摘要…" : "Search titles & summaries…"}
        className="h-[34px] min-w-0 flex-1 rounded-full border border-[var(--feed-line)] bg-white px-3.5 text-[12.5px] text-[var(--feed-text-primary)] outline-none placeholder:text-[var(--feed-text-tertiary)] focus:border-[var(--polyu-red)]"
        onChange={(event) => setText(event.target.value)}
      />
      <button
        type="submit"
        className="h-[34px] flex-none rounded-full bg-[var(--polyu-red)] px-[15px] text-[12.5px] font-semibold text-white transition-opacity hover:opacity-90"
      >
        {zh ? "搜索" : "Search"}
      </button>
    </form>
  );
}
