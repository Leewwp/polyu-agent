import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render } from "@testing-library/react";

import { mountHeadElement, useHeadElement } from "./useHeadElement";

/**
 * #231 head 注入小 helper：可复用导出形态（404 noindex 本票消费；#243
 * 订阅出口 autodiscovery 将复用同款）——挂载即入 head、卸载即移除、
 * null 态不挂载、属性变化换新元素。
 */

function Probe({ attrs }: { attrs: Record<string, string> | null }) {
  useHeadElement("meta", attrs);
  return <p>probe</p>;
}

function robotsMetas(): HTMLMetaElement[] {
  return Array.from(document.head.querySelectorAll<HTMLMetaElement>('meta[name="robots"]'));
}

describe("useHeadElement / mountHeadElement", () => {
  afterEach(() => {
    cleanup();
  });

  it("imperative primitive mounts and the returned cleanup removes exactly that element", () => {
    const preexisting = document.createElement("meta");
    preexisting.name = "robots";
    preexisting.content = "keep-me";
    document.head.appendChild(preexisting);

    const dispose = mountHeadElement("meta", { name: "robots", content: "noindex, nofollow" });
    expect(robotsMetas().map((meta) => meta.content)).toEqual(["keep-me", "noindex, nofollow"]);

    dispose();
    expect(robotsMetas().map((meta) => meta.content)).toEqual(["keep-me"]);
    preexisting.remove();
  });

  it("hook mounts the meta while rendered and removes it on unmount (离开 404 后移除)", () => {
    const view = render(<Probe attrs={{ name: "robots", content: "noindex, nofollow" }} />);
    expect(robotsMetas().map((meta) => meta.content)).toEqual(["noindex, nofollow"]);

    view.unmount();
    expect(robotsMetas()).toEqual([]);
  });

  it("null attrs mounts nothing; changing attrs swaps the element", () => {
    const view = render(<Probe attrs={null} />);
    expect(robotsMetas()).toEqual([]);

    view.rerender(<Probe attrs={{ name: "robots", content: "noindex, nofollow" }} />);
    expect(robotsMetas().map((meta) => meta.content)).toEqual(["noindex, nofollow"]);

    view.rerender(<Probe attrs={{ name: "robots", content: "index, follow" }} />);
    expect(robotsMetas().map((meta) => meta.content)).toEqual(["index, follow"]);

    view.rerender(<Probe attrs={null} />);
    expect(robotsMetas()).toEqual([]);
  });
});
