import { useEffect, useMemo, useRef, useState } from "react";
import { Link, Navigate, useParams } from "react-router-dom";

import { FeedFooter } from "@/components/feed/FeedFooter";
import { FeedShell } from "@/components/feed/FeedShell";
import { NewsCard } from "@/components/feed/NewsCard";
import { groupByDay } from "@/components/feed/NewsList";
import { useFeedLang } from "@/components/feed/feedLang";
import type { NewsItem } from "@/types/news";
import { formatUpdatedLabel } from "@/services/newsMapping";
import { TOPIC_MISSING_MESSAGE, fetchTopicDetail } from "@/services/newsService";
import type { TopicDetailData } from "@/services/newsService";

/**
 * 公开主题详情页（原型 #viewTopic）：
 * - 面包屑返回 /topics + 名称 + 界定描述 +（条数 · 数据更新时间）统计；
 * - 近期焦点=主题内热榜卡（排名徽章+标题+🔥热度+右上灰字「本主题 · 按热度」）——
 *   与流式列表明确区分；点击焦点行滚动定位到下方对应卡片并描边闪烁（.feed-flash）；
 * - 最新动态=日期分组全量列表；
 * - 数据走 /public/news/topic/{slug} 专用端点（计数/焦点/列表同源，
 *   替换旧「全局流第一页客户端过滤」——44 vs 9 与 csm 空态两意见同根因）；
 *   未知 slug（后端同文案「主题不存在」）回主题地图；公开页不 import engineStore（红线）。
 */

/** 近期焦点取主题内热度 Top N（原型 openTopic slice(0,3)） */
const FOCUS_LIMIT = 3;
/** 焦点行点击后描边闪烁驻留时长（原型 scrollToCard setTimeout 1600ms） */
const FLASH_DURATION_MS = 1600;

export function TopicDetailPage() {
  const { slug = "" } = useParams<{ slug: string }>();
  const [detail, setDetail] = useState<TopicDetailData | null>(null);
  const [missing, setMissing] = useState(false);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let alive = true;
    setDetail(null);
    setMissing(false);
    setFailed(false);
    fetchTopicDetail(slug)
      .then((data) => alive && setDetail(data))
      .catch((error) => {
        if (!alive) {
          return;
        }
        // 未知 slug（后端 A000001「主题不存在」）回主题地图；其余拒绝（网络/服务异常）落失败态
        if (error instanceof Error && error.message === TOPIC_MISSING_MESSAGE) {
          setMissing(true);
        } else {
          setFailed(true);
        }
      });
    return () => {
      alive = false;
    };
  }, [slug]);

  // 未知 slug → 回主题地图（原型 openTopic 早退语义）
  if (missing) {
    return <Navigate to="/topics" replace />;
  }

  return (
    <FeedShell title={detail ? { zh: detail.topic.nameZh, en: detail.topic.nameEn } : { zh: "主题地图", en: "Topics" }}>
      {detail ? (
        <TopicDetailBody key={detail.topic.slug} detail={detail} />
      ) : failed ? (
        <LoadFailureCard />
      ) : null}
    </FeedShell>
  );
}

function LoadFailureCard() {
  return (
    <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
      资讯加载失败，请稍后刷新重试
    </div>
  );
}

function TopicDetailBody({ detail }: { detail: TopicDetailData }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const { topic, lastPublishTime } = detail;
  const cardRefs = useRef<Map<string, HTMLDivElement>>(new Map());

  // 首页数据（本组件随 detail 就绪挂载）+「加载更多」追加页（size=50 一页装下，超限走护栏）
  const [items, setItems] = useState<NewsItem[]>(detail.page.records);
  const [hasMore, setHasMore] = useState(detail.page.hasMore);
  const [loadingMore, setLoadingMore] = useState(false);
  const nextPageRef = useRef(2);

  const loadMore = () => {
    if (loadingMore) {
      return;
    }
    setLoadingMore(true);
    fetchTopicDetail(topic.slug, nextPageRef.current)
      .then((data) => {
        setItems((prev) => [...prev, ...data.page.records]);
        setHasMore(data.page.hasMore);
        nextPageRef.current += 1;
      })
      .catch(() => null)
      .finally(() => setLoadingMore(false));
  };

  // 近期焦点=主题内按热度降序 Top N（heat desc 排序）
  const focus = useMemo(() => [...items].sort((a, b) => b.heat - a.heat).slice(0, FOCUS_LIMIT), [items]);
  const dayGroups = useMemo(() => groupByDay(items), [items]);

  /**
   * 焦点行点击：滚动定位到下方对应卡片并描边闪烁（原型 scrollToCard，第三轮评审定）。
   * jsdom 断言口径：scrollIntoView mock + .feed-flash 类挂载/1600ms 后移除。
   */
  const scrollToCard = (id: string) => {
    const el = cardRefs.current.get(id);
    if (!el) {
      return;
    }
    el.scrollIntoView({ behavior: "smooth", block: "center" });
    el.classList.add("feed-flash");
    window.setTimeout(() => el.classList.remove("feed-flash"), FLASH_DURATION_MS);
  };

  return (
    <>
      <Link
        to="/topics"
        className="mb-2.5 inline-block text-[13px] text-[var(--feed-text-tertiary)] hover:text-[var(--polyu-red)]"
      >
        {zh ? "‹ 全部主题" : "‹ All topics"}
      </Link>
      <div className="mb-1 border-b border-[var(--feed-line-soft)] pb-3.5">
        <h2 className="text-xl font-extrabold">{zh ? topic.nameZh : topic.nameEn}</h2>
        <p className="mt-1 max-w-[560px] text-[13px] text-[var(--feed-text-secondary)]">
          {zh ? topic.descZh : topic.descEn}
        </p>
        <div className="mt-2 flex gap-3.5 text-xs text-[var(--feed-text-tertiary)]">
          <span className="font-bold text-[var(--polyu-red-dark)]">
            {zh ? `共 ${topic.itemCount} 条` : `${topic.itemCount} items`}
          </span>
          {lastPublishTime && <span>{formatUpdatedLabel(lastPublishTime, lang)}</span>}
        </div>
      </div>

      {items.length > 0 && (
        <section className="mb-1 rounded-2xl border border-[var(--feed-line)] bg-[var(--feed-card)] p-4 pb-2.5 shadow-sm md:px-[18px]">
          <div className="mb-1.5 flex items-baseline gap-2.5">
            <h2 className="text-[15px] font-bold">{zh ? "近期焦点" : "Recent focus"}</h2>
            <span className="ml-auto text-xs font-normal text-[var(--feed-text-tertiary)]">
              {zh ? "本主题 · 按热度" : "In this topic · by heat"}
            </span>
          </div>
          <ol>
            {focus.map((item, index) => (
              <li
                key={item.id}
                className="border-b border-dashed border-[var(--feed-line-soft)] last:border-b-0"
              >
                <button
                  type="button"
                  onClick={() => scrollToCard(item.id)}
                  className="flex w-full items-center gap-3 py-2 text-left"
                >
                  <span
                    className={
                      index < 3
                        ? "flex h-5 w-5 flex-none items-center justify-center rounded-md bg-[var(--polyu-red)] text-xs font-bold text-white"
                        : "flex h-5 w-5 flex-none items-center justify-center rounded-md bg-[var(--feed-bg)] text-xs font-bold text-[var(--feed-text-tertiary)]"
                    }
                  >
                    {index + 1}
                  </span>
                  <span className="min-w-0 flex-1 truncate text-[13.5px]">{zh ? item.titleZh : item.titleEn}</span>
                  <span className="flex-none rounded-full bg-[var(--feed-heat-bg)] px-2.5 py-0.5 text-[11.5px] font-bold text-[var(--feed-heat)]">
                    🔥 {item.heat}
                  </span>
                </button>
              </li>
            ))}
          </ol>
        </section>
      )}

      <h3 className="mt-[18px] mb-2.5 text-[13px] font-bold text-[var(--feed-text-tertiary)]">
        {zh ? "最新动态" : "Latest updates"}
      </h3>

      {items.length === 0 ? (
        // 空态语义：主题确无条目=「暂无」；计数>0 却拉取为空=异常态，不再误导
        topic.itemCount > 0 ? (
          <LoadFailureCard />
        ) : (
          <div className="rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
            {zh
              ? "该主题下暂无入选资讯——主题由 AI 标签自动聚合，随来源扩展持续完善。"
              : "No items under this topic yet — topics grow as sources expand."}
          </div>
        )
      ) : (
        dayGroups.map((group) => (
          <section key={group.key} className="mt-[14px] first:mt-0">
            <h4 className="flex items-center gap-2 px-1 py-1.5 text-[13px] font-semibold text-[var(--feed-text-tertiary)]">
              {zh ? group.labelZh : group.labelEn}
              <span className="rounded-full bg-[var(--feed-bg)] px-[9px] py-px text-[11.5px] font-normal">
                {zh ? `${group.items.length} 条` : `${group.items.length} items`}
              </span>
            </h4>
            {group.items.map((item) => (
              <div
                key={item.id}
                ref={(el) => {
                  if (el) {
                    cardRefs.current.set(item.id, el);
                  } else {
                    cardRefs.current.delete(item.id);
                  }
                }}
              >
                <NewsCard item={item} />
              </div>
            ))}
          </section>
        ))
      )}

      {hasMore && (
        <div className="mt-4 text-center">
          <button
            type="button"
            disabled={loadingMore}
            className="rounded-full border border-[var(--feed-line)] bg-[var(--feed-card)] px-5 py-2 text-[13px] font-semibold text-[var(--feed-text-secondary)] transition-colors hover:border-[var(--polyu-red)] hover:text-[var(--polyu-red)] disabled:opacity-60"
            onClick={loadMore}
          >
            {loadingMore ? (zh ? "加载中…" : "Loading…") : zh ? "加载更多" : "Load more"}
          </button>
        </div>
      )}

      <FeedFooter compact />
    </>
  );
}
