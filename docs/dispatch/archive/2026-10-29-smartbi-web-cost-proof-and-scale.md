# SmartBI 网页剩余验收与明细修复（2026-10-29）

任务：`SC30-DETAIL-COST-PROOF-20261029`、`SC30-COMMERCE-LABEL-20261029`、`SC30-NETWORK-EVIDENCE-20261029`、`SC30-WEB-SCALE-20261029`。Base `b8b9d81d7b9a622abc56e53c39d4d1a9a23a4fe1`；Root 为唯一协调者。产品修改冻结并提交评审；合并、部署与最终网页回执分别以 Git、发布日志和私有台账核对。父任务 `SMARTBI-F006-QA-20261028` 继续负责验收。

## 问题与最终行为

- F006 六个生产分析族的新详情返回 403。真实 context→snapshot 回读证明不是旧前端引用：数据集中的其他单位有成本，当前候选按单位过滤后成本全空，却残留成本列且无 costGuard。现在在候选单位过滤后清理空制造成本列，并同步行、列、价格字段、维度契约及指纹；保留库存账龄/效期的合成维度。真实 0 成本继续受授权保护，旧无证明快照继续拒绝。
- 客户渠道类目的长文本把绘图区挤成窄条。限制轴标签绘制宽度、截断长标签，客户渠道轴不旋转；原始类别、tooltip、明细和金额保持完整。
- 验收工具补齐响应正文读取期限、登录后 API 活动安静窗口及读取失败回执。认证正文由登录身份校验单独验证，网络记录器只保存认证状态，避免复制会话秘密及跳转时重复读体。业务数据读体失败仍明确 TOOL_ERROR，写入阻断规则未放宽。
- 100 产品验收使用既有上传 5278/5277/5279：实际搜索首、中、末稳定产品，核对选中范围、目标 500kg/96%/95%/6250元、5 行明细及有界图表数；原上传 500 行通过实际 UI 翻页核对两页各 50 行，不以 5 行产品明细冒充分页。

## 已完成的验证

- 完整工厂分析套件：458 passed / 1 既有独立 schema skip，exit 0，冻结前后哈希一致。日志 `test-output/sc28-python-final-sc30-cost-label-final-v2.{log,json}`。首轮漏带已有 `/tmp/cretas-sc28-testdeps-20261028`，收集失败记录 v1 保留；更正为现成 `sc28-final-python-gate.sh` 后通过，未修改共享环境。
- 成本权限真实 PG 15 passed / 0 skipped；库存计数 9 passed；真实 PG 库存点定位 1 passed / 4 deselected。日志 `test-output/sc30-cost-pytest-final-20261029.txt`。
- Node 核心/规模断言 84 passed；本地异步网页 fixture 19 passed。日志 `test-output/sc30-canonical-unit-v5.log`、`test-output/sc30-local-fixtures-v2.log`。原 v1 的 6 项认证重复读体失败保留。
- 独立风险复核通过：空成本清理、真 0、当前成本授权、旧快照拒绝、stock bucket 定位、原始类别数据及现有容量保护。未修改迁移、Java Entity/Repository 或权限矩阵。

## 当前线上证据和界限

私有证据根目录：`C:/Users/Steve/my-prototype-logistics/.playwright-mcp/smartbi-complete-20261028`。不能把下列旧部署回执当作本次新源码部署后的验收。

- `web-resumed-focused-v1-20261029/report.json`：成本 2500/1600/500/300 和双商业关系问答、图表、原始明细、Excel 均 PASS，0 业务写入。
- `web-resumed-catalog-bounded-v2-20261029/report.json`：首批 8 类 PASS。其余三批原回执保留：六生产族明细 403；其他逐族断言虽通过，部分批次整体因响应采集失败为 TOOL_ERROR。最终 30 类须在修复部署后取得完整回执。
- `sc30-service-concurrency-v2.json`：两个 F006 用户各自分析/详情成功、互换上下文均 403；6 次技术查询 POST，0 业务写入。v1 非法额外参数失败保留。不是生产压测或原生 App 验收。
- 原生 App、另一 Android、iOS 按用户本轮要求保留未验收。既有 256 contexts / 32 MiB / 8 MiB / 300s 保护未提高；既有真实 PG 容量证据复用。

## 独立 F006 在制品测试数据

本轮另经正常网页创建并提交独立 QA 计划 `e13710fc-4fd9-42c7-867d-6470b97ba6e0`，标记 `QA-SC30-WIP-20261029`，Workflow365 v4，计划 4 袋。Root 共发送 5 次业务写请求：建计划、创建根批次11007、两道正式报工、一次生产小结。实际数据库行数由独立回读计数，不能把 5 次请求写成仅 5 行修改。

真实链为首道批次11008 / `CLK-W-20261029-1151` → 第二道11009 / `CLK-W-20261029-92940`，领用 1 kg；原料 1 kg、正常 BOM 辅料 0.061 kg，第二道成本73.36元。小结后原料B已用1kg、剩149kg；原料A已用120kg、剩80kg不变；半成品 anchor `CLK-SEMI-e13710fc-7f20b6d1` 可用1kg、成本73.36元；8条消耗均已结算，成品入库0。

证据：`sc30-wip-web-two-steps.{json,png}`、`sc30-wip-after-settle-1793243018.json`。这是普通已过账 MaterialConsumption 链；WIP_CONSUME 事件仍为0，不能伪装成 SFI OUT。现有工厂图表 WIP 适配器尚不消费这套普通链，另由 `SC31-WIP-MC-20261029` 处理，未混入本次冻结源码。

采购草稿、签名附件上传及非零历史封账金额仍按原证据保留，未关闭当前十月，未重复上传三表、重复入库或修改未知历史成本。所有新增测试业务请求限定 F006。
