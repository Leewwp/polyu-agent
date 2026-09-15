import type { NewsCategory } from "@/types/news";
import { NEWS_CATEGORY_CHIPS } from "@/services/newsMockData";
import { useFeedLang } from "./feedLang";

/**
 * 分类筛选 chips（原型 .chips）：固定 8 类 + 全部（禁止自由标签），
 * 横向滚动、隐藏滚动条；选中态红底白字。受控组件——筛选态由页面 URL 持有。
 */
export function CategoryChips({
  value,
  onChange
}: {
  value: NewsCategory | "all";
  onChange: (next: NewsCategory | "all") => void;
}) {
  const { lang } = useFeedLang();

  return (
    <div className="mb-1.5 flex gap-2 overflow-x-auto pb-1.5 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
      {NEWS_CATEGORY_CHIPS.map((chip) => (
        <button
          key={chip.key}
          type="button"
          aria-pressed={chip.key === value}
          className={
            chip.key === value
              ? "flex-none rounded-full border border-[var(--polyu-red)] bg-[var(--polyu-red)] px-[15px] py-[5.5px] text-[13px] font-semibold text-white"
              : "flex-none rounded-full border border-[var(--feed-line)] bg-white px-[15px] py-[5.5px] text-[13px] text-[var(--feed-text-secondary)] transition-colors hover:border-[var(--polyu-red)] hover:text-[var(--polyu-red-dark)]"
          }
          onClick={() => onChange(chip.key)}
        >
          {lang === "zh" ? chip.labelZh : chip.labelEn}
        </button>
      ))}
    </div>
  );
}
