// npm 依赖审计门：high/critical 漏洞即红。
// 已知暂不可非破坏修复的例外走本白名单——纪律：
//   1. 每条须附理由与解除条件；
//   2. 升级落地（或官方补丁进入当前版本区间）后删除对应条目，让门自然收紧；
//   3. 白名单只增不减须在 PR 正文显式声明理由。
// 运行环境：CI 默认 npmjs registry；本地验证需
//   npm_config_registry=https://registry.npmjs.org node scripts/npm-audit-gate.mjs
//（npmmirror 等镜像不实现 audit 端点，本地直跑会 404）。
import { spawnSync } from 'node:child_process';

const ALLOWED_ADVISORY_IDS = new Set([
  // 2026-09-20 随 #70 工具链对齐（vite 8 / vitest 5）清空：
  // 原 vite GHSA-fx2h-pf6j-xcff（1123525）与 vitest GHSA-5xrq-8626-4gwp（1139528）
  // 两条 known-unfixed 已随大版本升级修复，门自然收紧。
  // 2026-10-03 随 #215 批次加入（PR #216）：braces GHSA-vfj7-8cjw-p6xm（1240992）——
  // 官方把受影响区间扩到 <=3.0.3（lock 在用的 3.0.3 即"修复版"现也落在区间内），
  // braces 无 3.x 修复版发布，唯一 fixAvailable=tailwindcss 4 大版本升级（isSemVerMajor）。
  // 暴露面评估：纯 dev 依赖链（tailwindcss→chokidar/micromatch 的构建期 glob 匹配），
  // 不进构建产物、不进运行时镜像；CWE-674 需向解析器喂深嵌套模式，CI 构建输入为受信源码 glob。
  // 解除条件：braces 发布 >3.0.3 修复版、或 tailwind 升 4 时删除本条让门自然收紧。
  '1240992',
]);

// npm audit 在存在漏洞时退出码为 1（stdout 仍带完整 JSON）——spawnSync 不抛错才能拿到报告。
const res = spawnSync('npm', ['audit', '--json'], {
  encoding: 'utf8',
  maxBuffer: 64 * 1024 * 1024,
});
if (res.error || !res.stdout) {
  console.error('npm audit 门：npm audit 执行失败（网络/registry 异常？）：', res.error ?? res.stderr);
  process.exit(2);
}

const audit = JSON.parse(res.stdout);

const offenders = [];
for (const [name, vuln] of Object.entries(audit.vulnerabilities ?? {})) {
  for (const via of vuln.via ?? []) {
    if (
      typeof via === 'object' &&
      (via.severity === 'high' || via.severity === 'critical') &&
      !ALLOWED_ADVISORY_IDS.has(String(via.source))
    ) {
      offenders.push(`${name}: [${via.severity}] ${via.url ?? via.source} — ${via.title ?? ''}`);
    }
  }
}

if (offenders.length > 0) {
  console.error('npm audit 门：发现白名单外的 high/critical 漏洞，请处置或补充白名单：');
  console.error(offenders.map((s) => `  - ${s}`).join('\n'));
  process.exit(1);
}
console.log('npm audit 门：high/critical 均已修复或在白名单内，通过。');
