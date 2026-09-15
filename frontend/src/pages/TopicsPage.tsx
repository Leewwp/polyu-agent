import { useEffect, useState } from "react";

import { FeedFooter } from "@/components/feed/FeedFooter";
import { FeedShell } from "@/components/feed/FeedShell";
import { TopicsGrid } from "@/components/feed/TopicsGrid";
import { useFeedLang } from "@/components/feed/feedLang";
import type { NewsTopic, NewsTopicGroup } from "@/types/news";
import { fetchTopics } from "@/services/newsService";

/**
 * 公开主题地图页（原型 #viewTopics）：
 * - 20 主题三维分组目录卡（学院与部门 6 / 研究领域与话题 6 / 学生事务 8）——
 *   名称+一句话简介+条目计数，点击进主题详情页 /topics/:slug；
 * - 公开数据走 newsService（mock 先行），不 import engineStore、匿名零 /auth 请求
 *   （公开页红线，engineStore.ts:13 同款经验）；
 * - 裸路由无守卫（FeedPage 范式，router.tsx 挂 /topics）。
 */

interface TopicsRegistry {
  groups: NewsTopicGroup[];
  topics: NewsTopic[];
}

/** 页头（原型 .topics-head）：useFeedLang 须在 FeedShell（FeedLangProvider）内调用 */
function TopicsHead() {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  return (
    <div className="mb-[18px]">
      <h2 className="mb-1 text-[19px] font-extrabold">{zh ? "主题地图" : "Topics"}</h2>
      <p className="text-[12.5px] text-[var(--feed-text-tertiary)]">
        {zh
          ? "20 个主题由 AI 标签自动聚合、持续更新 · 点击任一主题查看近期焦点与全部动态"
          : "20 topics auto-aggregated by AI tags — click any topic for recent focus and full coverage"}
      </p>
    </div>
  );
}

export function TopicsPage() {
  const [registry, setRegistry] = useState<TopicsRegistry | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    fetchTopics()
      .then((data) => alive && setRegistry(data))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, []);

  return (
    <FeedShell title={{ zh: "主题地图", en: "Topics" }}>
      <TopicsHead />

      {failed ? (
        <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
          主题目录加载失败，请稍后刷新重试
        </div>
      ) : (
        registry?.groups.map((group, index) => (
          <TopicsGrid
            key={group.nameZh}
            group={group}
            topics={registry.topics.filter((topic) => topic.group === index)}
          />
        ))
      )}

      <FeedFooter compact />
    </FeedShell>
  );
}
