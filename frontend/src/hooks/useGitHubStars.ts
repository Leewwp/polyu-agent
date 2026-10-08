import * as React from "react";

// #337 切仓：星数指向本项目仓库；缓存键随仓更名，上游旧键保留 legacy 回退读取
const REPOSITORY_API_URL = "https://api.github.com/repos/Leewwp/polyu-agent";
const STAR_CACHE_KEY = "polyu.github-stars";
const LEGACY_STAR_CACHE_KEY = "ragent.github-stars";
const STAR_CACHE_TTL = 3 * 60 * 60 * 1000;
const STAR_RETRY_DELAY = 5 * 60 * 1000;

type StarCache = {
  count: number;
  at: number;
};

let requestInFlight: Promise<StarCache | null> | null = null;

function parseStarCache(raw: string | null): StarCache | null {
  if (!raw) return null;
  try {
    const parsed = JSON.parse(raw);
    return typeof parsed?.count === "number" && typeof parsed?.at === "number" ? parsed : null;
  } catch {
    return null;
  }
}

function readStarCache(): StarCache | null {
  try {
    return (
      parseStarCache(window.localStorage.getItem(STAR_CACHE_KEY)) ??
      parseStarCache(window.localStorage.getItem(LEGACY_STAR_CACHE_KEY))
    );
  } catch {
    return null;
  }
}

function writeStarCache(cache: StarCache) {
  try {
    window.localStorage.setItem(STAR_CACHE_KEY, JSON.stringify(cache));
  } catch {
    // 隐私模式等场景无法写入时，仍可在当前页面使用本次请求结果
  }
}

function requestStarCount(): Promise<StarCache | null> {
  if (requestInFlight) return requestInFlight;

  requestInFlight = fetch(REPOSITORY_API_URL, { cache: "no-store" })
    .then((response) => (response.ok ? response.json() : null))
    .then((data) => {
      if (typeof data?.stargazers_count !== "number") return null;
      const cache = { count: data.stargazers_count, at: Date.now() };
      writeStarCache(cache);
      return cache;
    })
    .catch(() => null)
    .finally(() => {
      requestInFlight = null;
    });

  return requestInFlight;
}

/**
 * 星数展示格式（#337 星钮 chip 共用纯函数）：null→"--"（API 不可达降级态）；
 * <1000 原样；≥1000→x.k 一位小数去尾零（12345→12.3k、2000→2k）。
 */
export function formatStarCount(starCount: number | null): string {
  if (starCount === null) return "--";
  if (starCount < 1000) return String(starCount);
  const rounded = Math.round((starCount / 1000) * 10) / 10;
  return `${String(rounded).replace(/\.0$/, "")}k`;
}

/**
 * 读取 GitHub Star 数：共享三小时本地缓存，并在过期、窗口聚焦或页面恢复可见时检查刷新
 */
export function useGitHubStars() {
  const [starCount, setStarCount] = React.useState<number | null>(
    () => readStarCache()?.count ?? null
  );

  React.useEffect(() => {
    let active = true;
    let refreshTimer: number | null = null;

    const scheduleRefresh = (delay: number) => {
      if (refreshTimer !== null) {
        window.clearTimeout(refreshTimer);
      }
      refreshTimer = window.setTimeout(
        () => {
          void refreshIfNeeded();
        },
        Math.max(delay, 1000)
      );
    };

    const refreshIfNeeded = async () => {
      const cached = readStarCache();
      if (cached) {
        setStarCount(cached.count);
        const remaining = STAR_CACHE_TTL - (Date.now() - cached.at);
        if (remaining > 0) {
          scheduleRefresh(remaining);
          return;
        }
      }

      const refreshed = await requestStarCount();
      if (!active) return;
      if (refreshed) {
        setStarCount(refreshed.count);
        scheduleRefresh(STAR_CACHE_TTL);
      } else {
        scheduleRefresh(STAR_RETRY_DELAY);
      }
    };

    const handleFocus = () => {
      void refreshIfNeeded();
    };
    const handleVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        void refreshIfNeeded();
      }
    };
    const handleStorage = (event: StorageEvent) => {
      if (event.key === STAR_CACHE_KEY || event.key === LEGACY_STAR_CACHE_KEY) {
        void refreshIfNeeded();
      }
    };

    void refreshIfNeeded();
    window.addEventListener("focus", handleFocus);
    window.addEventListener("storage", handleStorage);
    document.addEventListener("visibilitychange", handleVisibilityChange);

    return () => {
      active = false;
      if (refreshTimer !== null) {
        window.clearTimeout(refreshTimer);
      }
      window.removeEventListener("focus", handleFocus);
      window.removeEventListener("storage", handleStorage);
      document.removeEventListener("visibilitychange", handleVisibilityChange);
    };
  }, []);

  return starCount;
}
