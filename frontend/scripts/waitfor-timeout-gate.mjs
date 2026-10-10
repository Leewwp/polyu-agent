// #375 重页面 waitFor 显式 timeout 门禁（调用级，非字符串匹配）：
// 用 TypeScript 编译器 API 解析测试源码，逐 CallExpression 检查 waitFor 调用的
// 第二参数是否带 timeout 属性——裸调用（含多行形态）报 文件:行号 并以非零码退出。
// 首批范围=重页面两文件（票面授权）；其余 194 处存量不整改，扩面另票。
// 自检（--self-test）：裸调用失败/显式通过/多行显式通过/多行裸失败 四形态断言。
import { readFileSync } from 'node:fs';
import path from 'node:path';
import ts from 'typescript';

const DEFAULT_TARGETS = [
  'src/pages/DailyDigestPage.test.tsx',
  'src/pages/TopicsPage.test.tsx'
];

/**
 * 返回文件内所有缺少显式 timeout 的 waitFor 调用（行号从 1 起）
 */
function bareWaitForCalls(sourceText, fileName) {
  const sf = ts.createSourceFile(fileName, sourceText, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const violations = [];
  const visit = (node) => {
    if (ts.isCallExpression(node)) {
      const callee = node.expression.getText(sf);
      if (callee === 'waitFor' || callee.endsWith('.waitFor')) {
        const options = node.arguments[1];
        const hasTimeout = !!options
          && ts.isObjectLiteralExpression(options)
          && options.properties.some(
            (prop) => ts.isPropertyAssignment(prop) && prop.name.getText(sf) === 'timeout'
          );
        if (!hasTimeout) {
          const { line } = sf.getLineAndCharacterOfPosition(node.getStart(sf));
          violations.push({ line: line + 1, callee });
        }
      }
    }
    node.forEachChild(visit);
  };
  visit(sf);
  return violations;
}

function checkFile(file) {
  const source = readFileSync(file, 'utf8');
  return bareWaitForCalls(source, path.basename(file)).map(({ line, callee }) => ({
    file,
    line,
    callee
  }));
}

function selfTest() {
  const cases = [
    {
      name: '裸调用（单行）必须判缺 timeout',
      source: "test('x', async () => { await waitFor(() => screen.getByText('x')); });",
      expectViolations: 1
    },
    {
      name: '显式 timeout 通过',
      source: "test('x', async () => { await waitFor(() => screen.getByText('x'), { timeout: 5000 }); });",
      expectViolations: 0
    },
    {
      name: '多行显式 timeout 通过',
      source: [
        "test('x', async () => {",
        "  await waitFor(() => expect(screen.getByText('x')).toBeTruthy(), {",
        "    timeout: 5000,",
        "  });",
        "});"
      ].join('\n'),
      expectViolations: 0
    },
    {
      name: '多行裸调用必须判缺 timeout',
      source: [
        "test('x', async () => {",
        "  await waitFor(() =>",
        "    expect(screen.getByText('x')).toBeTruthy());",
        "});"
      ].join('\n'),
      expectViolations: 1
    },
    {
      name: 'screen.waitFor 属性访问形态同判',
      source: "await screen.waitFor(() => screen.getByText('x'));",
      expectViolations: 1
    }
  ];
  let failed = 0;
  for (const c of cases) {
    const got = bareWaitForCalls(c.source, 'fixture.tsx').length;
    const ok = got === c.expectViolations;
    console.log(`${ok ? 'ok  ' : 'FAIL'} 自检：${c.name}（期望 ${c.expectViolations} 处，实际 ${got} 处）`);
    if (!ok) {
      failed += 1;
    }
  }
  if (failed > 0) {
    console.error(`waitFor timeout 门禁自检失败：${failed} 个形态断言不通过`);
    process.exit(1);
  }
}

const args = process.argv.slice(2);
if (args.includes('--self-test')) {
  selfTest();
  process.exit(0);
}

const targets = args.length > 0 ? args : DEFAULT_TARGETS;
let total = 0;
for (const target of targets) {
  const file = path.resolve(target);
  const violations = checkFile(file);
  for (const v of violations) {
    console.error(`${v.file}:${v.line}: ${v.callee} 调用缺少显式 timeout（第二参数需 { timeout: … }）`);
  }
  total += violations.length;
}
if (total > 0) {
  console.error(`waitFor timeout 门禁不通过：${total} 处裸调用（范围 ${targets.join(', ')}）`);
  process.exit(1);
}
console.log(`waitFor timeout 门禁通过：${targets.join(', ')}`);
