import { useFeedLang } from "./feedLang";
import { useNewsLocalStore } from "@/stores/newsLocalStore";

/**
 * #215 关注主题按钮（纯前端本地关注，零后端零账号）：
 * - 状态=useNewsLocalStore.followedTopics（写穿 localStorage polyu.news.followedTopics，
 *   存主题 slug 稳定标识）；清站点数据即清空、无痕窗口互不可见；
 * - 两触点共用本组件：主题目录卡（TopicsGrid）+ 主题详情头（TopicDetailPage）——
 *   读 store 实时值（selector），任一触点关注/取关即时同步到所有触点；
 * - 独立 button（不嵌进目录卡 Link 内），避免交互元素嵌套与点击冒泡误导航。
 */
export function TopicFollowButton({ slug, nameZh, nameEn }: { slug: string; nameZh: string; nameEn: string }) {
  const { lang } = useFeedLang();
  const zh = lang === "zh";
  // 实时 selector：数组 includes 布尔化，followedTopics 变更即重渲染（非 getState 快照）
  const followed = useNewsLocalStore((state) => state.followedTopics.includes(slug));
  const toggleTopicFollow = useNewsLocalStore((state) => state.toggleTopicFollow);

  const name = zh ? nameZh : nameEn;
  const title = followed
    ? zh
      ? `取消关注「${name}」（仅保存在本浏览器）`
      : `Unfollow “${name}” (stored in this browser only)`
    : zh
      ? `关注「${name}」（仅保存在本浏览器）`
      : `Follow “${name}” (stored in this browser only)`;

  return (
    <button
      type="button"
      aria-pressed={followed}
      title={title}
      className={
        followed
          ? "inline-flex flex-none items-center gap-1 rounded-full border border-[var(--polyu-red)] bg-[var(--polyu-red-50)] px-3 py-1 text-[12px] font-semibold text-[var(--polyu-red-dark)] transition-colors hover:border-[var(--polyu-red-dark)]"
          : "inline-flex flex-none items-center gap-1 rounded-full border border-[var(--feed-line)] bg-[var(--feed-card)] px-3 py-1 text-[12px] font-semibold text-[var(--feed-text-secondary)] transition-colors hover:border-[var(--polyu-red)] hover:text-[var(--polyu-red)]"
      }
      onClick={() => toggleTopicFollow(slug)}
    >
      {followed ? "★" : "☆"} {followed ? (zh ? "已关注" : "Following") : zh ? "关注" : "Follow"}
    </button>
  );
}
