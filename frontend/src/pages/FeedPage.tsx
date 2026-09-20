import { useEffect, useState } from "react";
import { useSearchParams } from "react-router-dom";

import { CategoryChips } from "@/components/feed/CategoryChips";
import { FeedFooter } from "@/components/feed/FeedFooter";
import { FeedShell } from "@/components/feed/FeedShell";
import { HotPanel } from "@/components/feed/HotPanel";
import { NewsList } from "@/components/feed/NewsList";
import { NewsSearchBar } from "@/components/feed/NewsSearchBar";
import { useFeedLang } from "@/components/feed/feedLang";
import { useStaleRequest } from "@/hooks/useStaleRequest";
import type { HotRankEntry, NewsCategory, NewsItem } from "@/types/news";
import { NEWS_CATEGORY_CHIPS } from "@/services/newsMockData";
import { fetchHotRank, fetchNewsFeed, searchNewsFeed, type NewsSearchOrder, type NewsSearchSort } from "@/services/newsService";

/**
 * 公开资讯首页（组件组装版）：
 * - 匿名直见当日理大资讯流——裸路由无守卫（法务页范式），不 import engineStore/不触发引擎探测；
 * - 公开数据一律走 newsService 独立 axios 实例（mock 先行），不走 api.ts；
 * - 壳与侧栏/热点卡/chips/卡列表 = components/feed/ 组件族；
 * - `/?view=all` 全部资讯态不渲染 AI 精选条与热点卡；
 *   `/?category=` 筛选态保留；
 * - `?q=` 全局检索态：优先于 view/category 呈现——范围提示条+排序切换
 *   （按时间/按相关度+方向翻转）+日期分组结果流；结果复用 NewsList 形态；清空 q 回落原视图。
 *   T21：sort/order 进 URL（刷新/后退保持）；关键词×分类互通（点分类保留 q 限范围）；
 *   新搜索不强制复位排序（修复位债）。
 */

function isCategoryKey(key: string | null): key is NewsCategory | "all" {
  return key === "all" || NEWS_CATEGORY_CHIPS.some((chip) => chip.key === key);
}

function isSortKey(key: string | null): key is NewsSearchSort {
  return key === "time" || key === "relevance";
}

function isOrderDir(dir: string | null): dir is NewsSearchOrder {
  return dir === "asc" || dir === "desc";
}

/** AI 每日精选条（原型 .ai-strip；全部资讯态/检索态隐藏） */
function AiDigestStrip() {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  return (
    <div className="mb-[18px] flex items-center gap-2 rounded-[10px] border border-[var(--polyu-red-100)] bg-[var(--polyu-red-50)] px-3.5 py-2 text-[12.5px] text-[var(--polyu-red-dark)]">
      ✨{" "}
      <span>
        {zh ? (
          <>
            <b>AI 每日精选</b>
            —— 自动聚合理大官网与校级公开渠道（含官方 YouTube 频道）的当日动态，摘要由 AI 生成，以原文为准
          </>
        ) : (
          <>
            <b>AI daily digest</b> — auto-aggregated from PolyU official site & public channels; AI-generated
            summaries, always check the source
          </>
        )}
      </span>
    </div>
  );
}

/**
 * 检索态状态条：范围提示条+排序切换（按时间/按相关度）。
 * T21：同键再点翻转方向（时间键双向，默认 desc=最新在前；相关度单向不翻转）；
 * 提示条文案随范围动态（全部/分类 X）；sort+order 进 URL 由调用方承接。
 * 必须作为 FeedShell 子组件渲染（useFeedLang 依赖壳顶 Provider——FeedPage 自身是壳的父层）。
 */
function SearchStateBar({
  q,
  category,
  sort,
  order,
  onSortChange
}: {
  q: string;
  category: NewsCategory | "all";
  sort: NewsSearchSort;
  order: NewsSearchOrder;
  onSortChange: (next: { sort?: NewsSearchSort; order?: NewsSearchOrder }) => void;
}) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  const categoryLabel = zh
    ? NEWS_CATEGORY_CHIPS.find((chip) => chip.key === category)?.labelZh
    : NEWS_CATEGORY_CHIPS.find((chip) => chip.key === category)?.labelEn;
  // 相关度键单向不翻转：方向图标只在时间键上表态
  const dirGlyph = sort === "time" ? (order === "asc" ? "↑" : "↓") : "";
  const dirTitle =
    sort === "time"
      ? zh
        ? order === "asc"
          ? "时间正序（最旧在前），点击翻转"
          : "时间倒序（最新在前），点击翻转"
        : order === "asc"
          ? "Oldest first — click to flip"
          : "Newest first — click to flip"
      : "";
  return (
    <div className="mt-2.5 flex flex-wrap items-center gap-2 rounded-[10px] bg-[var(--feed-bg)] px-3.5 py-2">
      <p className="min-w-0 flex-1 text-[12px] text-[var(--feed-text-secondary)]">
        {category !== "all"
          ? zh
            ? `在分类「${categoryLabel ?? category}」中搜索「${q}」`
            : `Searching “${q}” in ${categoryLabel ?? category}`
          : zh
            ? `在全部理大资讯中搜索「${q}」`
            : `Searching “${q}” across all PolyU news`}
      </p>
      <div className="flex flex-none overflow-hidden rounded-full border border-[var(--feed-line)] bg-white text-[12px] font-semibold">
        <button
          type="button"
          aria-pressed={sort === "time"}
          title={dirTitle}
          className={
            sort === "time"
              ? "px-3 py-[5px] bg-[var(--polyu-red)] text-white"
              : "px-3 py-[5px] text-[var(--feed-text-tertiary)]"
          }
          onClick={() => {
            // 同键再点翻转方向（默认 time+desc）；异键切换保持 desc（最新在前）
            if (sort === "time") {
              onSortChange({ order: order === "asc" ? "desc" : "asc" });
            } else {
              onSortChange({ sort: "time", order: "desc" });
            }
          }}
        >
          {zh ? "按时间" : "By time"}
          {dirGlyph ? <span aria-hidden="true"> {dirGlyph}</span> : null}
        </button>
        <button
          type="button"
          aria-pressed={sort === "relevance"}
          className={
            sort === "relevance"
              ? "px-3 py-[5px] bg-[var(--polyu-red)] text-white"
              : "px-3 py-[5px] text-[var(--feed-text-tertiary)]"
          }
          onClick={() => onSortChange({ sort: "relevance" })}
        >
          {zh ? "按相关度" : "By relevance"}
        </button>
      </div>
    </div>
  );
}

/** 检索态失败/空态文案（双语；非检索态两处既有空态维持原型中文原样） */
function SearchStatusCard({ kind, q }: { kind: "failed" | "empty"; q: string }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  return (
    <div className="mt-4 rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
      {kind === "failed"
        ? zh
          ? "搜索失败，请稍后重试"
          : "Search failed — please try again later"
        : zh
          ? `未找到「${q}」相关资讯`
          : `No results for “${q}”`}
    </div>
  );
}

export function FeedPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const categoryParam = searchParams.get("category");
  const category: NewsCategory | "all" = isCategoryKey(categoryParam) ? categoryParam : "all";
  // 全部资讯态（/?view=all）：隐藏 AI 精选条与热点卡
  const isAllView = searchParams.get("view") === "all";
  // 检索态（?q=）：非空 q 优先于 view/category 呈现
  const qParam = searchParams.get("q")?.trim() ?? "";
  const isSearch = qParam.length > 0;
  // T21：sort/order 进 URL（刷新/后退保持）；缺省 time+desc=最新在前
  const sortParam = searchParams.get("sort");
  const sort: NewsSearchSort = isSortKey(sortParam) ? sortParam : "time";
  const orderParam = searchParams.get("order");
  const order: NewsSearchOrder = isOrderDir(orderParam) ? orderParam : "desc";

  const [items, setItems] = useState<NewsItem[]>([]);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  // L31：loadMore 失败态——按钮转为「重试」入口，不再静默吞错
  const [loadMoreFailed, setLoadMoreFailed] = useState(false);
  const [page, setPage] = useState(1);
  const [hot, setHot] = useState<HotRankEntry[]>([]);
  const [failed, setFailed] = useState(false);

  // L31：M19 请求序守卫——首屏 effect 与 loadMore 共用同一序号，
  // 切分类/检索条件后迟到的旧 loadMore 响应不再追加进新列表
  const { begin, isCurrent } = useStaleRequest();

  // 首屏/切分类/提交检索/切排序：重置到第 1 页（首屏 20 条+加载更多）
  useEffect(() => {
    let alive = true;
    const requestId = begin();
    setFailed(false);
    setLoadMoreFailed(false);
    setPage(1);
    const request = isSearch
      ? searchNewsFeed({ q: qParam, sort, order, category, page: 1 })
      : fetchNewsFeed({ category, page: 1 });
    request
      .then((data) => {
        if (!alive || !isCurrent(requestId)) return;
        setItems(data.records);
        setHasMore(data.hasMore);
      })
      .catch(() => {
        if (alive && isCurrent(requestId)) {
          setFailed(true);
        }
      });
    if (!isSearch) {
      fetchHotRank(5)
        .then((data) => alive && isCurrent(requestId) && setHot(data))
        .catch(() => null);
    }
    return () => {
      alive = false;
    };
    // begin/isCurrent 为稳定 ref 读写，不进依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [category, isSearch, qParam, sort, order]);

  // 加载更多：追加下一页（检索态走同一分页语义）
  const loadMore = () => {
    if (loadingMore) return;
    setLoadingMore(true);
    setLoadMoreFailed(false);
    const requestId = begin();
    const request = isSearch
      ? searchNewsFeed({ q: qParam, sort, order, category, page: page + 1 })
      : fetchNewsFeed({ category, page: page + 1 });
    request
      .then((data) => {
        if (!isCurrent(requestId)) return;
        setItems((prev) => [...prev, ...data.records]);
        setHasMore(data.hasMore);
        setPage(page + 1);
      })
      .catch(() => {
        if (!isCurrent(requestId)) return;
        setLoadMoreFailed(true);
      })
      .finally(() => setLoadingMore(false));
  };

  const setCategory = (next: NewsCategory | "all") => {
    // T21：关键词×分类互通——检索态点分类保留 q，结果限该分类范围；非检索态行为不变
    const params: { view?: string; category?: string; q?: string } = {};
    if (isAllView) {
      params.view = "all";
    }
    if (next !== "all") {
      params.category = next;
    }
    if (qParam) {
      params.q = qParam;
    }
    setSearchParams(params);
  };

  const submitSearch = (q: string) => {
    // 保留既有视图参数（view/category 作回落位）；空 q=退出检索
    // T21：新搜索不再强制复位排序（修票面点名的复位债），sort/order 随 URL 自然保留
    const params: { view?: string; category?: string; q?: string } = {};
    if (isAllView) {
      params.view = "all";
    }
    if (category !== "all") {
      params.category = category;
    }
    if (q) {
      params.q = q;
    }
    setSearchParams(params);
  };

  // T21：排序/方向变化写回 URL（sort/order 进 URL，刷新/后退保持）
  const changeSort = (next: { sort?: NewsSearchSort; order?: NewsSearchOrder }) => {
    const params: { view?: string; category?: string; q?: string; sort?: string; order?: string } = {};
    if (isAllView) {
      params.view = "all";
    }
    if (category !== "all") {
      params.category = category;
    }
    if (qParam) {
      params.q = qParam;
    }
    const nextSort = next.sort ?? sort;
    const nextOrder = next.order ?? order;
    if (nextSort !== "time" || nextOrder !== "desc") {
      params.sort = nextSort;
    }
    if (nextOrder !== "desc") {
      params.order = nextOrder;
    }
    setSearchParams(params);
  };

  // 空态：当日零条目时顶部灰条说明+照常展示此前日期内容（列表按发布时间倒序，
  // 首屏自然先见最近内容）——「今天」按 HKT 判定（时区口径）；检索态不渲染
  const todayKey = new Date().toLocaleDateString("en-CA", { timeZone: "Asia/Hong_Kong" });
  const newestPublishDate = items[0]?.publishDate;
  const staleToday =
    !isSearch && items.length > 0 && !!newestPublishDate && newestPublishDate < todayKey;

  const title = isSearch
    ? { zh: "搜索结果", en: "Search results" }
    : isAllView
      ? { zh: "全部资讯", en: "All news" }
      : { zh: "精选", en: "Featured" };

  return (
    <FeedShell title={title}>
      {/* 搜索行（2026-09-12 修复）：页面顶端独立一行、桌面右对齐（参照
          aihot 形态——图 4 搜索与标题同行）；chips 独占一行横滑不再被挤压。≤860px 全宽。 */}
      <div className="mb-2 flex justify-end max-[860px]:justify-start">
        <div className="w-[340px] flex-none max-[860px]:w-full">
          <NewsSearchBar value={isSearch ? qParam : ""} onSubmit={submitSearch} />
        </div>
      </div>
      {!isAllView && !isSearch && <AiDigestStrip />}
      {!isAllView && !isSearch && <HotPanel entries={hot} />}

      <CategoryChips value={category} onChange={setCategory} />

      {isSearch && (
        <SearchStateBar
          q={qParam}
          category={category}
          sort={sort}
          order={order}
          onSortChange={changeSort}
        />
      )}

      {failed ? (
        isSearch ? (
          <SearchStatusCard kind="failed" q={qParam} />
        ) : (
          <div className="mt-4 rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
            资讯加载失败，请稍后刷新重试
          </div>
        )
      ) : items.length === 0 ? (
        isSearch ? (
          <SearchStatusCard kind="empty" q={qParam} />
        ) : category === "all" ? (
          <div className="mt-4 rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
            今日数据更新中，请稍后再来看看
          </div>
        ) : (
          <div className="mt-4 rounded-2xl border border-dashed border-[var(--feed-line)] bg-[var(--feed-card)] p-7 text-center text-[13px] text-[var(--feed-text-tertiary)]">
            该分类下暂无资讯
          </div>
        )
      ) : (
        <>
          {staleToday && (
            <div className="mt-3 rounded-lg bg-[var(--feed-bg)] px-3.5 py-2 text-center text-[12px] text-[var(--feed-text-tertiary)]">
              今日数据更新中——先看看此前的资讯
            </div>
          )}
          <NewsList items={items} />
          {hasMore && (
            <div className="mb-2 text-center">
              {/* L31：loadMore 失败转重试入口（不再吞错） */}
              <button
                type="button"
                disabled={loadingMore}
                className={
                  loadMoreFailed
                    ? "rounded-full border border-[var(--polyu-red)] bg-[var(--feed-card)] px-5 py-2 text-[13px] font-semibold text-[var(--polyu-red)] transition-colors hover:bg-[var(--polyu-red-50)] disabled:opacity-60"
                    : "rounded-full border border-[var(--feed-line)] bg-[var(--feed-card)] px-5 py-2 text-[13px] font-semibold text-[var(--feed-text-secondary)] transition-colors hover:border-[var(--polyu-red)] hover:text-[var(--polyu-red)] disabled:opacity-60"
                }
                onClick={loadMore}
              >
                {loadingMore ? "加载中…" : loadMoreFailed ? "加载失败，点击重试" : "加载更多"}
              </button>
            </div>
          )}
        </>
      )}

      <FeedFooter />
    </FeedShell>
  );
}
