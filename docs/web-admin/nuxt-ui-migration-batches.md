# web-admin 业务页迁移 Nuxt UI：分批清单

来源：DECISIONS 第二十节第 4 点（Element Plus 逐步退出核心界面）。2026-11-15 编。
定案参数（Steve 11-15）：根字号 14px、徽标绿 `--cs-success`、只做浅色。

## 做法（每批都一样）

1. 一页一页迁：弹窗/抽屉整体迁到 `UModal` / `USlideover`（Element 下拉放进 Nuxt UI 弹层点不了，反过来也一样），不在一个弹层里混用两家控件。
2. 表格用 `UTable`（表头 32px、行 44px、13px、`--cs-*`），徽标 `UBadge variant="soft"`，筛选栏 `UInput`/`USelectMenu`/`UButton`，分页 `UPagination`。样例：`views/production/outsource/index.vue`、`views/sales/customers/detail/tabs/PriceMemoryTab.vue`（PR #1746）。
3. 高密度可编辑表格（行内编辑、合并单元格、虚拟滚动）暂留 Element，但必须用 `styles/cs-theme/*` 的同一套变量，长相对齐原型。
4. 每页：保留全部 `data-testid` 与既有 spec 断言（vitest 里没装 Nuxt UI 插件，spec 用最小桩）；改完做原型/组件库对照并排截图，逐组件列一致/差异；提示仍用 `ElMessage`（命令式服务），全局包 `UApp` 另议。
5. Nuxt UI 缺 Tailwind preflight：`styles/nuxt-ui.css` 已补 `[data-slot]` 限定的子集与弹层 z-index（1100–1200），新页不要再各自打补丁。

## 规模（含 `<el-table` 的 .vue 数 / 该目录 .vue 总数，不含 components 与测试）

| 模块 | 含表格页 / 总页 |
|---|---|
| platform（平台管理员、编辑器） | 47 / 150 |
| smart-bi | 27 / 83 |
| sales | 45 / 62 |
| system | 30 / 50 |
| production | 28 / 42 |
| scheduling | 9 / 28 |
| warehouse | 21 / 25 |
| finance | 20 / 23 |
| procurement | 18 / 20 |
| hr | 18 / 19 |
| quality | 14 / 18 |

## 批次顺序

- **第 1 批：生产过程只读/轻写列表（本轮要做的 7 页，每页 250–430 行，同一业务模块、同一套组件）**
  1. `production/delivery-risks/index.vue`（交付风险，抽屉内有指派/关闭，需整体迁 USlideover）
  2. `production/delivery-warnings/list.vue`
  3. `production/YieldStandardView.vue`
  4. `production/reversals/list.vue`
  5. `production/conversions/index.vue`
  6. `production/labor-efficiency/index.vue`
  7. `quality/release/ProcessTimeoutEventsTab.vue`
- **第 2 批：采购单据列表（已完成 2026-11-15，8 页）**：`procurement/payment-requests/list`、`returns/list`、`requisitions/list`、`requisitions/my-list`、`suppliers/list`（新增弹窗已迁 UModal；详情抽屉 / 导入弹窗为子组件，留下一轮）、`price-lists/list`、`inquiry-quotes/list`、`approval-rules/list`。未动：`orders/list`（2086 行高密度）、`invoices/list`、`exceptions/list`、各 detail 页与 `CreateRequisitionDialog`（Element 子弹窗）。
- **第 3 批：仓库台账（已完成，8 个文件）**：`warehouse/inventory-total/index.vue`（含子组件 `FinishedGoodsSummary.vue`）、`semi-finished/list.vue`、`transit-ledger/list.vue`、`manufacturers/index.vue`、`reusable-containers/list.vue`、`materials/wip-list.vue`、`material-segments/index.vue`。弹窗全部 `UModal`（含停用/删除确认，取代 `ElMessageBox`）；其余较重的仓库页（inventory、stocktakes、materials/list、shipments、wastage-reports 等）留到后续批。
- **第 3 批续：仓库列表页（5 页）**：`warehouse/inventory/index.vue`、`shipments/list.vue`（确认出库弹窗 UModal、详情 USlideover；规格换算的 `ElMessageBox` 在模态 UModal 下点不了，所以那一步先收起弹窗、问完或取消后还原）、`wastage-reports/index.vue`（照片上传改原生 file 输入）、`unordered-inbound-applications/index.vue`（撤回确认改 UModal）、`salted-deductions/index.vue`。留下：`stocktakes`（2476 行，含盘点录入高密度编辑表）、`semi-finished-stocktakes`、`materials/list`（1442 行，含入库批次编辑/称重）、`material-types/list`（2442 行）、`MaterialPriceTrendView`、`PendingPurchaseReceivingPanel`。
- 第 4 批：质量（`quality/ccp|cold-chain|returns|defects|param-deviation`，含质检记录列表本体）
- 第 5 批：销售订单/客户（销售 45 页，按 订单 / 客户 / 报价 / 发运 分 3 小批）
- 之后：财务、人事、系统、smart-bi、平台编辑器（编辑器类整页多为画布，最后评估是否迁）。

不在批次内：`platform` 里的流程/画布编辑器、ECharts 图表本身（保留）。

## 验收口径

每个 PR：改过的每一页本地 dev server（F006 测试账号只读，凭证只读环境变量、不进提交）截图，对组件库 `Cretas-组件库-NuxtUI-20261114/index.html` 的对应组件或统一原型同页，逐组件列「一致/差异」；computed 样式（行高 44、字号 13、表头 32、色值）要量，不能只看颜色。
