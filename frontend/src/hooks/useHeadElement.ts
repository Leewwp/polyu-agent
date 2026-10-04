import { useEffect } from "react";

/**
 * #231 head 注入小 helper（可复用导出形态）：
 * - `mountHeadElement`：向 document.head 挂一个带属性元素的命令式原语，返回
 *   卸载函数（移除该元素）——404 的 noindex meta（#231/N6）与 #243 订阅出口
 *   autodiscovery（link rel=alternate RSS）复用同款；
 * - `useHeadElement`：React 绑定——attrs 传 null 时不挂载（条件注入），
 *   卸载/属性变化时旧元素随之移除，无残留。
 * 属性一律经 setAttribute 写入（不经 innerHTML），XSS 面收敛。
 */

export type HeadElementAttrs = Record<string, string>;

export function mountHeadElement(tagName: string, attrs: HeadElementAttrs): () => void {
  const element = document.createElement(tagName);
  for (const [key, value] of Object.entries(attrs)) {
    element.setAttribute(key, value);
  }
  document.head.appendChild(element);
  return () => {
    element.remove();
  };
}

export function useHeadElement(tagName: string, attrs: HeadElementAttrs | null): void {
  const deps = attrs ? Object.entries(attrs).flat() : [null];
  useEffect(() => {
    if (!attrs) {
      return;
    }
    return mountHeadElement(tagName, attrs);
    // attrs 逐项展开进依赖（null 态以 [null] 占位）；tagName 同层常量
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tagName, ...deps]);
}

/** noindex meta（name=robots，与两类分享页同 content 口径）：软 404 不被当 200 收录 */
export const NOINDEX_ATTRS: HeadElementAttrs = { name: "robots", content: "noindex, nofollow" };
