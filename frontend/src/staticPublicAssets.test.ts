// @vitest-environment node
import { describe, expect, it } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * #213 站点发现面静态资产合同：robots.txt / llms.txt / IndexNow 键文件随前端
 * 构建进 nginx 镜像（public/ 真实文件先于 SPA try_files 兜底命中）——三件缺一
 * 即出口断链。键文件内容必须与后端 rag.news.indexnow-key 配置值逐字一致
 * （两处同源约定；此处锚文件侧，后端侧由 IndexNowServiceTests 覆盖）。
 */
describe("public discovery static assets (#213)", () => {
  // vitest 自 frontend/ 启动（npm test），cwd 即 frontend/——public/ 为构建静态源目录
  const publicDir = join(process.cwd(), "public");

  it("robots.txt exists, allows public pages, disallows api/admin, declares sitemap", () => {
    const robots = readFileSync(join(publicDir, "robots.txt"), "utf8");
    expect(robots).toContain("User-agent: *");
    expect(robots).toContain("Disallow: /api/");
    expect(robots).toContain("Disallow: /admin");
    expect(robots).toContain("Sitemap: https://polyuguide.com/sitemap.xml");
  });

  it("llms.txt exists and lists the core public surfaces", () => {
    const llms = readFileSync(join(publicDir, "llms.txt"), "utf8");
    expect(llms).toContain("PolyU Guide");
    expect(llms).toContain("https://polyuguide.com/feed.xml");
    expect(llms).toContain("https://polyuguide.com/sitemap.xml");
    expect(llms).toContain("/daily");
    expect(llms).toContain("/key-dates");
  });

  it("IndexNow key file exists at site root shape and matches the configured key", () => {
    const key = "ee751b74b79272cc9e40f864450cbca4";
    const keyFile = join(publicDir, `${key}.txt`);
    expect(existsSync(keyFile)).toBe(true);
    expect(readFileSync(keyFile, "utf8")).toBe(key);
  });
});
