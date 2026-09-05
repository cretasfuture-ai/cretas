---
name: e2e-web-admin
description: Web E2E 验收入口（web-admin / Expo Web 的 CRUD、持久化、跨模块、业务流、部署前后验证、客户 bug 复现；生产只读验收与 F006 写入特例）。本文件是指向 canonical skill 的指针。
---

# Compatibility pointer

CANONICAL: `.agents/skills/e2e-web-admin/SKILL.md`

Read and follow the canonical skill above. Production read-only acceptance must
use the shared implementation under `scripts/e2e/production-readonly/`; this
file intentionally contains no independent workflow, defaults, credentials, or
mutation policy.

Claude 侧注意：canonical 文件里的 `browser_run_code_unsafe` / "Codex Playwright MCP filename entry"
是 Codex 的工具名。在 Claude Code 里用 `playwright-rn` 或 plugin playwright 的对应工具加载
`scripts/e2e/production-readonly/mcp-entry.js`；规则本身（零业务写入、白名单 POST、F006 写入前证明
`factoryUser.factoryId === 'F006'`）不变。
