# B07 调拨草稿回执

> 2026-09-11 补修：独立评审发现首次仓库请求失败后无法恢复的 P2。下方原批次结果保留为历史，最新恢复验证见文末；未据旧 49 项通过掩盖该问题。

- 任务：DRIVE-20260910-B07-TRANSFER-DRAFT
- Owner：01a08b80-59fc-7a01-a338-b40f862aad6e
- Base：e03fc7cf0c7c100d35cb8e907067e4b7e7e118af（本轮成功 fetch 并核对）
- Worktree：C:/Users/Steve/cretas-drive-20260910-b07
- Branch：codex/drive-20260910-b07
- Scope：仅 transfer/list.vue、transfer/__tests__/，以及本回执；未修改 ACTIVE、共享缓存、其他页面或后端；没有 subagent。

## 结果

main77 / main72 调拨子场景 / UX&UI19 属已复现 Bug。切换页签后，缓存组件不再因全局路由变化重新初始化草稿。不同 _task 保持独立；明确新建创建新的 _task；失败保留，成功清空本草稿；切厂立即清空旧输入与选项，异步旧响应不能回写新租户草稿。后台提交成功不抢走正在使用的其他页签。调拨类型和库存/单位/审批业务规则保持原样。

修改文件：

1. web-admin/src/views/transfer/list.vue
2. web-admin/src/views/transfer/__tests__/transferCreateWorkspace.source.spec.ts
3. web-admin/src/views/transfer/__tests__/transferDraftLifecycle.spec.ts
4. web-admin/src/views/transfer/__tests__/transferDraft.e2e.mjs

## 验证

从 web-admin 运行：

- `npx vitest run src/views/transfer/__tests__`：7 files / 49 tests 通过，含 5 项真实 Vue Router + KeepAlive 组件生命周期测试。日志 B07-tests.log。
- `npx vue-tsc -b --pretty false`：最终退出 0，日志为空。只使用应用现有 build:check 中的类型检查段，未执行正式 Web 构建。
- `node src/views/transfer/__tests__/transferDraft.e2e.mjs`：最终退出 0；完整应用、本地 Chrome、实际页签点击，六组场景通过。
- `git diff --check`：通过。最终目标验证前已在当前 task 只读审查 diff；未开 subagent。

实际页面验收：

| 场景 | 实际结果 |
|---|---|
| 输入后切列表页签再返回，连续 3 次 | 2026-09-11 调拨日期、2026-09-12 预计到货、B07保留全部字段备注、原料仓、生产仓、B07原料、3.000 kg 全部一致 |
| 负优化：返回草稿请求增量 | 工厂网络/仓库/库存/包装请求总数 5 → 5，未因返回草稿重复加载；列表自己的激活读取独立记录 |
| 保存失败 | 表单全部保留；显示 fixture 失败消息 |
| 明确新建与 _task 隔离 | 新页为空，第一份完整草稿与第二份备注分别保留 |
| 切类型 | 原有目标仓库依赖清理继续有效 |
| 成功保存后返回 | 原草稿为空、明细为空；一次失败加一次成功总共两个创建请求，无额外创建 |
| 切厂 | 当前草稿清空；全局现有行为清空页签栏后，重新通过路由打开旧第二草稿 URL，无旧租户输入 |

组件测试额外覆盖：三次返回零选项请求增量、不同 _task 实例、显式新建、缓存中切厂和延迟旧网络响应、重复提交抑制、成功清空、提交中离开页签后延迟成功不抢导航。

所有 API 均在浏览器 route 层本地 fulfill，且阻断非本机 origin；请求 JSON 中 POST 是 fixture 请求，不是线上业务写入。真实生产业务写入为 0。切厂通过测试中修改本地 auth store 触发真实响应式生命周期；没有生产登录或真实租户修改。

## 证据路径

根目录：C:/Users/Steve/cretas-drive-20260910-b07/web-admin/

- test-results/b07-transfer-draft/result.json：完整字段前后值、请求 URL/方法/payload、六组断言。
- test-results/b07-transfer-draft/01-before.png：输入后。
- test-results/b07-transfer-draft/02-three-tab-returns.png：三次真实页签往返后。
- test-results/b07-transfer-draft/03-failed-save.png：失败保留。
- test-results/b07-transfer-draft/04-tenant-reset.png：切厂与旧草稿链接返回后。
- B07-tests.log、B07-types.log、B07-ui.log。
- 复现脚本：src/views/transfer/__tests__/transferDraft.e2e.mjs，需先在该 web-admin 启动本地 Vite 5187；BASE_URL 只能为 127.0.0.1。

截图已人工查看返回后的整页，显示日期、仓库、备注、明细和按钮正常。首次 UI 脚本因关闭动画中的旧下拉被误匹配超时，改为当前控件 aria-controls 精确定位；另一次切厂后旧页签索引失效，改为打开已记录的旧草稿路由。均属脚本适配，没有据此修改产品权限或共享缓存。failure.json/png 保留为失败过程证据，当前结论以 result.json 和最终退出码为准。

## Drive 回填计划与边界

原表 Cretas FDE：1TyIqNP_z8bBiPyLeXbr3YYvwE1WCuHFhgYAHiJC4w4M。
仅 main!F72:G72、main!F77:G77、'UX&UI'!F19:G19，写入前 fresh-read，保留原备注并追加 commit/验收/未部署。
main72 仍“正在解决”，仅调拨子场景完成；main77“已修复待验证”；UX19“待部署”。这三个状态均在目标单元格现有 validation 白名单内；不修改格式、规则、问题描述或其他行。实际写回/回读状态在收尾记录。

## 未做 / 未验

没有正式 Web 构建、PR、合并或生产部署；集成发布由协调者执行。没有线上数据库/真实业务验收、移动端验收或跨刷新持久草稿。未扩展所有模块草稿、十个以上缓存容量问题或 main71 状态刷新。浏览器本次是 1440×1000，既有小屏布局未改动，未重跑整套小屏测试。长期库存变化仍由既有提交/后端校验约束，本次不新增跨页自动库存刷新策略。

下一步：协调者在 PR762 发布完成后串行集成 B07，统一审查/发布构建，部署后进行用户验收。不能将本地 fixture 通过写成生产业务已解决。

## 收尾记录

- 代码 commit：db9c8738e57825caf0225dd0de822411dec2c0ed；本回执另以文档提交跟随。
- 最后一次源码修正为切换到空仓库时清除旧 loading 状态，之后 49 tests、vue-tsc、六组完整 UI 均重新通过；没有使用旧构建产物证明最终源码。
- Drive 三行 F/G 已更新并 readback：main72=正在解决、main77=已修复待验证、UX19=待部署。原备注完整保留，格式与 dataValidation 和读取前完全相同；仅追加本批证据/代码 commit/未部署说明。回读证据：web-admin/test-results/b07-transfer-draft/drive-readback.json。
- Google 渲染界面未截图，按目标 CellData 做格式/值/验证规则的最佳可用检查；没有改变行高或重排表格。
- 本任务启动的 Vite 5187 已停止；本地证据、独立依赖和 worktree 保留。
- 没有阻塞；等待协调者集成与发布。branch push 状态以交付消息中的远端读取结果为准。

## 2026-09-11 P2 失败选项恢复补修

评审来源 C:/Users/Steve/cretas-review-evidence/B07-readonly-review-20260910.md；独立旧版页面证据 C:/Users/Steve/cretas-drive-20260910-b06/web-admin/.playwright-mcp/b07-review/result.json。旧版首次仓库 500 后回页签请求 1→1、备注保留但仓库永远为空，这是已确认退化。

本次只改原 scope：list.vue、transferDraftLifecycle.spec.ts，并新增 transferDraftRecovery.e2e.mjs。表单初始化与 network/source/target 选项加载状态分离；成功空数组记为 loaded；返回页签只恢复失败项，成功项不重取。增加可见“重试加载选项”按钮，不清空表单。loading 状态去重并保留 revision/目标请求标识防止旧租户或旧目标回写。后台成功不抢导航逻辑未删除。

新增 5 项组件测试：仓库失败→返回恢复且重复触发不重复请求、工厂网络失败局部恢复、合法空列表不重试、目标仓库失败只重试该请求、切厂时旧仓库延迟响应不回写。原 49 项不删不跳过，最终 54/54（7 files）通过，B07-recovery-tests.log。

本次新增完整页面验收（真实点击页签/重试按钮；HTTP 500 由本地 fixture 返回）：

| 场景 | 选项请求前后 | 结果 |
|---|---|---|
| 首次 500→网络恢复→返回草稿，连续返回三次 | 源仓 1→2；工厂网络 1→1 | 可选择“恢复后的仓库”，备注保持“恢复时保留输入” |
| 首次 500→本页重试按钮 | 源仓 1→2；工厂网络 1→1 | 输入保留且仓库可选 |
| 合法空仓库数组，连续返回三次 | 源仓 1→1；工厂网络 1→1 | 不报加载失败、不无限重试，输入保留 |
| A租户首次失败→挂起重试→切B租户→A延迟返回 | A源仓1→2，B源仓0→1 | 仅显示B仓库，旧输入清空，A响应不覆盖B |

新四组与原六组完整页面脚本最终均退出 0，pageerror 均为空，真实生产业务写 0。原正常路径仍草稿选项请求5→5、创建只发两次有意请求；没有扩大共享缓存或业务规则。新四组证据在 C:/Users/Steve/cretas-drive-20260910-b07/web-admin/test-results/b07-transfer-recovery/result.json，以及各 scenario-before.png/scenario-after.png；脚本在 src/views/transfer/__tests__/transferDraftRecovery.e2e.mjs。已查看恢复后的完整页面截图。

最终 `npx vue-tsc -b --pretty false` 退出 0，B07-recovery-types.log 为空。补修代码 commit da9504bf8fa03fcf77cc77939848618344dc9426；本节文档以独立收尾提交跟随，推送结果回传协调者。

仅追加 main!G72、main!G77、'UX&UI'!G19，Drive 已 readback：三行状态仍为正在解决/已修复待验证/待部署；原备注保留，状态单元格、格式和验证规则与写前一致。证据 test-results/b07-transfer-recovery/drive-readback.json。Google 渲染未另截图，使用 CellData 最佳可用格式验证。Vite 5187 已停止。

未创建PR、未合并、未部署、未改ACTIVE或其他scope。原未验项保持有效。请协调者集成时同时包含 B07 原始修复与本补修，不能只取 db9c8738e5。
