import { Link } from "react-router-dom";

import type { NewsTopic, NewsTopicGroup } from "@/types/news";
import { useFeedLang } from "./feedLang";

/**
 * 主题目录分组卡阵（原型 .topic-group/.topic-grid/.topic-card）：
 * 一个三维分组（0=学院与部门/1=研究领域与话题/2=学生事务）× 2 列目录卡——
 * 名称（分组 1/2 主题带图标前缀）+一句话简介+条目计数，点击进主题详情页 /topics/:slug。
 * 移动端 860px 断点隐藏简介、缩间距（原型 media 口径：.tc-desc{display:none}）。
 */
export function TopicsGrid({ group, topics }: { group: NewsTopicGroup; topics: NewsTopic[] }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";

  return (
    <section className="mb-6">
      <h3 className="text-[14.5px] font-bold">{zh ? group.nameZh : group.nameEn}</h3>
      <p className="mb-2.5 text-xs text-[var(--feed-text-tertiary)]">{zh ? group.subZh : group.subEn}</p>
      <div className="grid grid-cols-2 gap-3 max-[860px]:gap-2.5">
        {topics.map((topic) => {
          const name = zh ? (topic.icon ? `${topic.icon} ${topic.nameZh}` : topic.nameZh) : topic.nameEn;
          return (
            <Link
              key={topic.slug}
              to={`/topics/${topic.slug}`}
              className="flex flex-col gap-[5px] rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-4 shadow-sm transition duration-150 hover:-translate-y-px hover:border-[#D8B7BC] max-[860px]:p-[13px]"
            >
              <span className="text-[14.5px] font-bold">{name}</span>
              <span className="text-[12.5px] leading-[1.55] text-[var(--feed-text-secondary)] max-[860px]:hidden">
                {zh ? topic.descZh : topic.descEn}
              </span>
              <span className="mt-[3px] text-xs font-semibold text-[var(--polyu-red)]">
                {zh ? `查看 ${topic.itemCount} 条 →` : `${topic.itemCount} items →`}
              </span>
            </Link>
          );
        })}
      </div>
    </section>
  );
}
