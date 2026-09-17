# 贡献指南

感谢关注本项目！欢迎通过 issue 与 pull request 参与贡献。

## 开始之前

- 本项目基于 [nageoffer/ragent](https://github.com/nageoffer/ragent)（Apache-2.0）二次开发。涉及底座模块（`framework/`、`infra-ai/`、`system/` 等）且与 PolyU 业务无关的通用改动，建议直接向上游仓库提交，本项目会按需跟进。
- 重大改动（新模块、依赖引入、破坏性变更）请先开 issue 讨论，避免返工。
- 提交前请确认没有重复的 issue / PR。

## 本地开发

环境要求：JDK 17、Node.js 20+。环境与启动配置参见 [README](./README.md)「快速开始」与 `bootstrap/src/main/resources/application.yaml`；本地中间件编排见 `resources/docker/`。

```bash
# 后端构建与单元测试（纯单元测试，无需任何 API key / 中间件）
./mvnw test

# 跑单个测试类（多模块场景须带 -am 与 failIfNoSpecifiedTests，与 CI 口径一致）
./mvnw -pl agent -am test -Dtest=AgentTraceScenarioTest -Dsurefire.failIfNoSpecifiedTests=false

# 前端检查（与 CI 前端门同口径）
cd frontend && npm ci
npm run lint && npm run test && npm run build
```

## Pull Request 要求

- **CI 全绿是硬门**：后端质量门 / 秘密扫描 / 依赖巡检 + 前端 lint / test / build，未通过无法合并。
- **不引入任何密钥或凭据**（真实密钥、内部地址、个人隐私信息）；仓库已开启 secret scanning 与 push protection。
- 新增依赖需在 PR 说明用途与选型理由；安全相关依赖升级优先处理。
- 单一关注点、小步提交；提交信息遵循 `type(scope): 摘要` 风格（中英文均可，与仓库历史一致）。
- 涉及 UI 的改动请附前后截图或可复现步骤。

## 安全问题

安全漏洞不要开公开 issue / PR，请按 [SECURITY.md](./SECURITY.md) 走私密漏洞报告。

## 许可证

提交即表示你同意贡献以 Apache-2.0 许可证随本项目发布。
