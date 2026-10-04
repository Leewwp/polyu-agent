import { Link } from "react-router-dom";

/**
 * 轻量页品牌位（#229 品牌与出路统一）：法务三页与答案分享页的自绘 header
 * 从旧「PolyU Wayfinder」（学士帽+浅蓝块）切换为 PolyUGuide——图形标与名称
 * 随主站报头（FeedSidebar/MobileTopbar）同源：/brand/icon.png + Guide 品牌红。
 * 整位链到 /（轻量页无导航，外部访客由品牌位获得回主站资讯流的出路）。
 */
export function BrandMark() {
  return (
    <Link
      to="/"
      aria-label="PolyUGuide"
      className="flex items-center gap-2 rounded-lg outline-none focus-visible:ring-2 focus-visible:ring-[var(--polyu-red)]"
    >
      <img
        src="/brand/icon.png"
        alt=""
        className="h-8 w-8 flex-none rounded-[9px] object-contain"
      />
      <span className="text-[15px] font-extrabold leading-none text-[#1A1A1A]">
        PolyU<i className="not-italic text-[var(--polyu-red)]">Guide</i>
      </span>
    </Link>
  );
}
