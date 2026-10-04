import { Link } from "react-router-dom";

import type { NewsTopic, NewsTopicGroup } from "@/types/news";
import { TopicFollowButton } from "./TopicFollowButton";
import { useFeedLang } from "./feedLang";

/**
 * 主题目录分组卡阵（原型 .topic-group/.topic-grid/.topic-card）：
 * 一个三维分组（0=学院与部门/1=研究领域与话题/2=学生事务）× 2 列目录卡——
 * 名称（分组 1/2 主题带图标前缀）+一句话简介+条目计数，点击进主题详情页 /topics/:slug。
 * 移动端 860px 断点隐藏简介、缩间距（原型 media 口径：.tc-desc{display:none}）。
 * #215 目录触点：每卡右上角本地关注钮（TopicFollowButton，与主题详情头共用状态）；
 * 卡主体保持单一 Link（每卡一链），关注钮以兄弟节点绝对定位角标化，不嵌套进链接。
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
            <div
              key={topic.slug}
              className="relative rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] shadow-sm transition duration-150 hover:-translate-y-px hover:border-[#D8B7BC]"
            >
              <Link to={`/topics/${topic.slug}`} className="flex flex-col gap-[5px] p-4 max-[860px]:p-[13px]">
                {/* 右上角关注钮占位让位：名称行预留右内边距防两行重叠 */}
                <span className="pr-[72px] text-[14.5px] font-bold">{name}</span>
                <span className="text-[12.5px] leading-[1.55] text-[var(--feed-text-secondary)] max-[860px]:hidden">
                  {zh ? topic.descZh : topic.descEn}
                </span>
                {/* #233 零值文案：0 条改「暂无动态」（卡片仍可进主题详情、关注钮照常） */}
                <span className="mt-[3px] text-xs font-semibold text-[var(--polyu-red)]">
                  {topic.itemCount > 0
                    ? zh
                      ? `查看 ${topic.itemCount} 条 →`
                      : `${topic.itemCount} items →`
                    : zh
                      ? "暂无动态"
                      : "No updates yet"}
                </span>
              </Link>
              <div className="absolute right-3 top-3 max-[860px]:right-2.5 max-[860px]:top-2.5">
                <TopicFollowButton slug={topic.slug} nameZh={topic.nameZh} nameEn={topic.nameEn} />
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}
