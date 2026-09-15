import { useMemo } from "react";

import type { NewsItem } from "@/types/news";
import { NewsCard } from "./NewsCard";
import { useFeedLang } from "./feedLang";

interface DayGroup {
  key: string;
  labelZh: string;
  labelEn: string;
  items: NewsItem[];
}

/** 相邻同日聚合（数据按日降序到达；FeedPage 同款逻辑随组件化迁入；TopicDetailPage 复用） */
export function groupByDay(items: NewsItem[]): DayGroup[] {
  const groups: DayGroup[] = [];
  for (const item of items) {
    const last = groups[groups.length - 1];
    if (last && last.key === item.dayLabelZh) {
      last.items.push(item);
    } else {
      groups.push({ key: item.dayLabelZh, labelZh: item.dayLabelZh, labelEn: item.dayLabelEn, items: [item] });
    }
  }
  return groups;
}

/**
 * 资讯卡列表（原型 .day-group）：按日分组渲染 NewsCard，
 * 组头带当日条数计数。空列表渲染 null——加载/失败/空分类态由页面层负责。
 */
export function NewsList({ items }: { items: NewsItem[] }) {
  const { lang } = useFeedLang();
  const dayGroups = useMemo(() => groupByDay(items), [items]);

  if (dayGroups.length === 0) {
    return null;
  }

  return (
    <>
      {dayGroups.map((group) => (
        <section key={group.key} className="mt-[14px] first:mt-0">
          <h3 className="flex items-center gap-2 px-1 py-1.5 text-[13px] font-semibold text-[var(--feed-text-tertiary)]">
            {lang === "zh" ? group.labelZh : group.labelEn}
            <span className="rounded-full bg-[var(--feed-bg)] px-[9px] py-px text-[11.5px] font-normal">
              {lang === "zh" ? `${group.items.length} 条` : `${group.items.length} items`}
            </span>
          </h3>
          {group.items.map((item) => (
            <NewsCard key={item.id} item={item} />
          ))}
        </section>
      ))}
    </>
  );
}
