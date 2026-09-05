---
name: smartbi-test-e2e
description: SmartBI E2E 上传测试。把 /smartbi-test-data 生成的测试 Excel 通过浏览器上传到 SmartBI，验证 SSE 解析 → 图表 → KPI → AI 分析全流程。用户说 /smartbi-test-e2e、上传测试 Excel、SmartBI 上传验证时触发。
allowed-tools:
  - Bash
  - Read
  - Glob
  - Grep
---

# SmartBI E2E 上传测试

用当前可用的 Playwright 浏览器工具（`playwright-rn` / plugin playwright MCP，或本地 `chromium.launch()` 脚本）走真实上传流程。

## 参数（`$ARGUMENTS`）

| 输入 | 动作 |
|------|------|
| _(空)_ | 列出 `tests/test-data/` 可用文件让用户选 |
| `factory {industry} {scenario}` / `factory` / `restaurant` | 单个或全部工厂 / 餐饮文件 |
| `edge` / `stress l1` / `stress l2` / `all` | 边界 6 个 / 压力 L1、L2 各 2 个 / 全部 <10MB 文件 |
| `--url http://...` | 覆盖默认 `http://localhost:5173` |

文件位置：`tests/test-data/Test-mock-{industry}-{scenario}-s42.xlsx`、`restaurant/Restaurant-*.xlsx`、`edge-cases/Edge-*.xlsx`、`stress/Stress-*-L{1,2}-s42.xlsx`。文件不存在 → 提示先 `/smartbi-test-data gen {type}`。

## 账号

`f006_admin`，口令 `$TEST_FACTORY_ADMIN_PASS`（本地 `.env.test`，不进仓库）。`factory_admin1` 是死值。

## 流程与判据

登录 → `{BASE_URL}/smart-bi/analysis` → `input[type="file"]` 用**绝对路径**上传 → 点「开始分析 / 上传」→ 等 SSE 完成（"成功处理 N 个 Sheet" / "分析完成"，上限 120s，超时截图记 TIMEOUT 跳下一文件）→ 逐项验证 → 截图到 `tests/test-data/screenshots/{filename}-result.png`。

| 检查项 | 通过标准 |
|--------|----------|
| SSE 完成 | "成功处理 N 个 Sheet" 出现 |
| Sheet 标签 | tab 数 = 预期 sheet 数 |
| 数据表格 | 第一个 sheet 表格行数 > 0 |
| 图表渲染 | 至少 1 个 `.chart-container canvas` / ECharts 实例 |
| KPI 卡片 | 至少 1 个 |
| AI 分析 | 文本 > 50 字 |
| 控制台 | 无 500 / CORS / 致命错误 |

批量模式逐文件执行，之间点「上传新文件 / 新建分析」或直接回到上传页。

## 报告

按文件列 `Sheets | 解析 | 图表 | KPI | AI | 结果`，加总计（文件 / 通过 / 失败 / 图表数 / KPI 数）和截图目录。登录失败先 `curl http://localhost:5173` 与 `curl http://localhost:10010/api/mobile/health`；SSE ERROR 记失败的 sheet 名；图表失败查控制台里的具体 API。发现 Python / Java 解析问题用截图 + 控制台日志辅助定位。
