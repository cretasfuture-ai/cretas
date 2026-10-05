---
name: smartbi-test-e2e
description: SmartBI 测试：生成测试 Excel、在浏览器里上传验证 SSE 解析 → 图表 → KPI → AI 分析，以及跑 tests/e2e-smartbi 回归套件。用户要生成或上传 SmartBI 测试数据、验证 SmartBI 上传流程时用。
---

# SmartBI 测试

三件事：生成测试 Excel → 上传走真实流程 → 需要时跑回归套件。账号用 `f006_admin`，口令 `$TEST_FACTORY_ADMIN_PASS`（本地 `.env.test`，不进仓库；`factory_admin1` 是死值）。

## 1. 生成测试数据（参数以 `gen` 开头时）

| 类型 | 脚本 | 输出 | 数量 |
|---|---|---|---|
| 工厂 | `tests/generate_test_excel.py` | `tests/test-data/Test-mock-{industry}-{scenario}-s42.xlsx` | 12 文件 / ~264 sheets（food/mfg/retail × normal/growth/loss/sparse） |
| 餐饮 | `tests/generate_restaurant_excel.py` | `tests/test-data/restaurant/` | 12 文件 / ~204 sheets（fish/hotpot/bakery × 同上） |
| 边界 | `tests/generate_edge_cases.py` | `tests/test-data/edge-cases/` | 6 文件（宽表 120 列、混合类型、空区域、公式、数字列名、跨年同比） |
| 压力 | `tests/generate_stress_test.py`（`--level l2` 含 L2） | `tests/test-data/stress/` | L1 2 文件；L2 再加 2 个、约 50MB、内存占用大 |

工厂、餐饮、边界三个脚本互不依赖，可以并行跑。生成后核对文件数和 sheet 数与上表一致；边界和 L1 压力文件里应有合并单元格，`Edge-formula-cells.xlsx` 里应有公式。清理时保留原始 `tests/test-data/Test.xlsx`，删之前先列出要删的文件。

## 2. 上传验证

参数：空 = 列出可用文件让用户选；`factory {industry} {scenario}` / `factory` / `restaurant` / `edge` / `stress l1|l2` / `all`；`--url` 覆盖默认 `http://localhost:5173`（Vite 常在 5173–5175 之间漂，先确认实际端口）。文件不存在就先按第 1 节生成。

用 Playwright MCP（`browser_navigate` / `browser_file_upload` / `browser_wait_for` / `browser_console_messages`）：登录 → `{BASE_URL}/smart-bi/analysis` → `input[type="file"]` 用绝对路径上传 → 点「开始分析」→ 等 SSE 完成（"成功处理 N 个 Sheet"，上限 120s，超时截图记 TIMEOUT 换下一个）→ 检查 → 截图到 `tests/test-data/screenshots/{filename}-result.png`。

| 检查 | 通过标准 |
|---|---|
| SSE | 出现 "成功处理 N 个 Sheet" |
| Sheet 标签 | tab 数 = 预期 sheet 数 |
| 表格 | 第一个 sheet 行数 > 0 |
| 图表 | 至少 1 个 ECharts 实例（canvas 不是 DOM 元素，点数据点要按坐标定位） |
| KPI | 至少 1 张卡 |
| AI 分析 | 文本 > 50 字 |
| 控制台 | 无 500 / CORS / 致命错误 |

报告按文件列 `Sheets | 解析 | 图表 | KPI | AI | 结果`，加总计和截图目录。登录失败先 `curl` 前端和 `http://localhost:10010/api/mobile/health`；SSE 报错记下失败的 sheet 名。

## 3. 回归套件 `tests/e2e-smartbi`

`npm install && npx playwright install chromium` 后 `npm run test:headed`；持续回归用 `npm run ralph-loop:headed`（`LOOPS=N` 限轮数）。按 `.claude/rules/playwright-headed-mode.md`，SmartBI dashboard 用有头模式跑。生产只读检查用 `BASE_URL=http://139.196.165.140:8086`（web-admin 在 139 网关，不在 47）。报告在 `tests/e2e-smartbi/reports/`。
