# Dispatch 完成记录 — 2026-08-23

## 已完成

- `UX-F006-WAREHOUSE-RN-HOME-RECEIVING-20260823` — `merged` — Owner: `/root` — Base SHA: `d6411f475b378b9c4e412c607092f8e7f49f14ec` — 公司仓 PR #13。仓管 RN 底部导航收敛为首页/入库/发货/我的，首页仅保留两个大业务入口；入库首页从物料批次改为采购收货任务真值，筛选与排序固定为待收货→部分入库→已完成，四类状态任一路请求失败均显式报错；移除餐饮待验收、财审通过与明细行，扫码入口放大至 56dp；收货与发货卡片统一显示共 N 项及逐项真实单位，多单位订单禁止错误合计。公司仓 exact branch 目标 Jest 55/55、TypeScript、`git diff --check` 通过；同一业务树 RN 全量 Jest 1639/1639 与 Expo Android export 通过。实现、PR/CI、合并与生产 RN OTA/设备验收分开记录；本项代码 scope 已释放。

- `SEC-DEPENDENCY-BASELINE-20260823` — `merged` — Owner: `/root` — Base SHA: `7129c9b527e5c4fc823e5827b0a50d3b5bcfc8cd` — 公司仓 PR #10。首批仅处理活跃 Web Admin：在修复前 GitHub 默认分支共 673 个 Dependabot open alerts（14 critical、344 high、279 moderate、36 low），其中 `web-admin/package-lock.json` 97 个；本地 Web manifest 的 19 个唯一漏洞（1 critical、15 high、3 moderate）经安全范围升级和 SheetJS 官方 0.20.3 tarball 收敛为 `npm audit` 0。新增 `/web-admin` security-only、按安全更新分组的 Dependabot pilot，常规版本更新 PR 保持关闭，仓库级自动安全修复未开启；`checkout`、`setup-node`、`upload-artifact` 升级至 GitHub 官方当前主版本。首次远程 Web Gate `32629444294` 暴露 `vue-tsc` 约 2 GiB V8 heap OOM，保留完整检查并将该步骤显式提高到 4 GiB；精确 HEAD 的 Web Gate `32630860962`（完整 Vitest + `vue-tsc`，12m18s）与 Secret gate `32630861001` 均通过。Web 本地 clean install、3423 tests passed/5 skipped、typecheck、build、SheetJS roundtrip、`npm audit` 0 与 release-preflight 均通过；可信 Web manifest tree 为 `5b64f0058c3fd910ab2a3a5f10d3013bcf9583be`，archive SHA-256 为 `d275eeb42eb8d7c3ef010eea857f4fb3506fec1156a6dc11f657e24206930a63`。未修改业务源码、Java、Python/RN/Mall 依赖、生产数据或全局自动合并设置；六扇门锁定的 `backend/python/requirements.txt` 未触碰，其他生态告警留给后续隔离批次。本记录只证明实现、审计与候选制品门禁，生产发布仍须在合并后从 clean exact `origin/main` 执行并单独验收；scope 锁已释放。

- `SOP-RAG-SYNC-20260823` — `merged` — Owner: Codex automation `/root` — Base SHA: `a70e96a927af72f70f48c707554f8fdfe08629a1`。GitHub 仍不可用，本轮按离线例外核对前次本地 main `469aaac8d26c8a4354e433c7a9bb46fe2accf500` 至 clean 本地 `main`，工厂判定 changed、餐饮判定 no-op；候选离线汇合到 `9f4847494848bc8356f271b4b166abaa2fa95e68`，未声称进入 `origin/main`。工厂同步了 F006 精确角色权限、RN“生产成品入库”、标签客户风险队列/系统未见风险/平台训练选样隔离、`AP_INVOICE` 未清应付核销与旧付款链 410、报工撤回原子事务和历史 APPROVED 恢复；餐饮三个注册源与注册表未改。共享 AI Assist、工厂 SOP、餐饮 SOP 生产 SHA-256 为 `997563a8816cfea61816f17d313e14c51e18004f255b1f8488ebcb47a7d24615`、`250454f51a7f9bf079a4b61de55f6c702de65c1478decbd7ea306cf94f133052`、`e9e970dda61a87a85701ae9b14ef216b4e2c3e3a44e5b9f92d549e8e37488acd`，release worktree、服务器与公网响应一致；回滚点为 `aiassist.html.bak.sop-20260823_092621` 和 `index.html.bak.sop-20260823_092621`。目标测试 128/128、`py_compile`、五页 HTML parse、两页内联 JS parse、release-preflight 与 `git diff --check` 通过；生产 migration dry-run 与正式发布均为 142/142 已应用、0 pending，Python import/route/Torch/systemd/8083 健康门禁通过。仅原子重建 `f006-production-full-chain-sop.md`，正式块 75、`.NEW=0`；餐饮正式块保持 87/250/176 且创建时间未变。工厂三组等价角色问法、固定 BOM/Workflow、RN、标签、付款、冲销均只引用 F006 source；餐饮固定单菜毛利与导览 canary 只引用三个餐饮 registered source。生产 ERP 业务写入 0；scope 锁已释放。仅余 GitHub 恢复后逐提交补 PR/CI 与远端核销。

- `GITHUB-JPA-ACTIONS-RUNTIME-20260823` — `merged` — Owner: `/root` — Base SHA: `6797ddbbbd5895d41ca7013f735a6fa1c1b01d1c`。新公司仓库 `cretasfuture-ai/cretas` 已设为本地 `origin`；柳杉门及当日离线累计产品代码逐文件与迁移基线一致，未把无共同历史的旧分支硬推成 PR。受控启用 `JPA repository query gate (post-merge)` 后，基线 run `32625169651` 在新 `main` 执行 55/55、0 failure、0 error；PR #8 将 GitHub 官方 `actions/checkout` 从 v4 升到 v7、`actions/setup-java` 从 v4 升到 v5，分支 run `32625649226` 再次执行 55/55、0 failure、0 error，PR `tracked-secret-scan` 通过。改动不触及 Java/Python/Web/RN 产品树或生产状态；scope 锁已释放。仓库为私有且当前组织套餐不支持 branch protection/ruleset，迁移仍不得标记为完全完成；在升级套餐或公开仓库前，合并继续使用 fresh fetch、OPEN/non-draft、exact head、MERGEABLE、通过检查、`--match-head-commit` 与合并后读回的人工等价门禁。

- `FIX-LIUSHANMEN-LABEL-PARITY-SQUARE-20260815` — `merged`（迁移核销）— Owner: `/root` — 原 Base SHA: `ff75ad274c22ff51eb35c90b01c37b79b6135629`。`device=cpu, rect=False, imgsz=640` 的 PT/ONNX parity 修复及对应测试已逐文件确认包含在公司仓迁移基线，旧任务不再占用 `vision_lab.py` scope；未重复提交代码、未修改模型/数据/运行配置或生产。尚缺的 sidecar/evaluator 完整性改由 `MIGRATE-LIUSHANMEN-LABEL-QC-SIDECAR-20260823` 独立处理。

- `MIGRATE-LIUSHANMEN-LABEL-QC-SIDECAR-20260823` — `merged` — Owner: `/root` — Base SHA: `292a2f704f69e84027f321445f1bce016263f2f7` — 公司仓 PR #9。实际 label evaluator CLI 已接收经版本、manifest 路径/SHA、源图 SHA 与 human-reviewed evidence 绑定的 truth-adjudication sidecar，并把 records 前后数量、排除清单与 `promotion_ready` 写入 metrics；VisionLab promotion gate 对 sidecar 缺失、路径/SHA 漂移或非 ready 一律 fail closed。真实 CLI 子进程冒烟、目标测试与完整 VisionLab 235/235、`git diff --check` 通过；未重复 parity，未修改模型、数据集、人工真值、保护集、运行配置或生产，严格 `NOT_DEPLOYED`；scope 锁已释放。

- `POLICY-CODEX-MODEL-ADVISORY-20260810` — `merged`（迁移核销）— Owner: `/root` — 原 Base SHA: `9aaecef811e5159cde424f8e670a2c93400adeee`。公司迁移基线已逐文件确认包含“模型/effort 仅非阻塞建议、不得等待切换确认”的目标语义；旧任务不再单独占用 `AGENTS.md`、rules-maintenance 或 dispatch scope。更完整的静默自动路由与窄职责 agent 配置由 `MIGRATE-CODEX-ORCHESTRATION-ROUTING-20260823` 在公司最新 main 上独立处理。

## 2026-08-23 ACTIVE 实时证据审计归档

以下记录均逐条核对公司 main、迁移源 tree、旧 commit/PR、当前 worktree/ref、远端 open PR、进程与计划任务；“merged”只表示实现已被当前代码树覆盖，不自动表示生产部署或设备验收。

- `E2E-F006-RBAC-ROLE-CHAIN-20260823` — `merged` — Web 96/96、App 7 角色/24 Tab 与跨端链已有最终回执，迁移源实现和 QA 文档进入公司树，生产清理零残留；仅未完成的 ADB/Maestro 应另建窄设备验收项，原代码 scope 已释放。
- `RELEASE-LIUSHANMEN-QC-RISK-ROUTING-20260822` — `merged` — risk routing、dual queue 与 transport retry 均进入迁移源，v8 生产回放有 222 任务/871 图/0 分析失败证据；sidecar 又由公司 PR #9 补齐，scope 已释放。
- `REST-AI-READ-PATH-20260812` — `merged` — B0–B5、归因、时间词语料、DeepSeek 接线与 cron 均已入迁移树且有线上回执；后续观察/续 key 属运营项，restaurant 广域代码锁已释放。
- `FIX-F006-PRESTOCKED-SHIPMENT-E2E-20260809` — `merged` — 实现进入迁移源，后续 F006 跨端链已覆盖创建发货单、仓库确认、库存回读和清理；scope 已释放。
- `AUDIT-RESTAURANT-SYNTHESIS-RETENTION-20260727` — `merged` — 兼容保留审计 commit 已汇入迁移源，公司文件 blob 一致；任务本身严格 NOT_DEPLOYED，scope 已释放。
- `BUG-F006-WORKFLOW-OUTPUT-KIND-SNAPSHOT-20260810` — `merged` — 旧 PR #2448 的 Vue/测试文件与公司树一致，原目标验证回执 35/35；scope 已释放。
- `ARCH-F006-MATERIAL-TAXONOMY-DUPLICATION-001` — `merged` — 可选共享 classificationId、禁止名称驱动自动建 L3、父内规范化去重及 DB/JPA 门禁已进入公司树；scope 已释放。
- `BUG-F006-PURCHASE-ORDER-CREATE-ATTACHMENTS-001` — `merged` — 订单只创建一次、获 ID 后绑定附件、部分失败仅重试附件的 UI/测试已进入公司树；scope 已释放。
- `BUG-F006-PURCHASE-ORDER-TAX-RATE-INHERIT-001` — `merged` — 税率继承/免税/缺配置 422、后端重算及金额快照和测试已进入公司树；scope 已释放。
- `BUG-F006-PURCHASE-ORDER-ACTION-IA-001` — `merged` — 合法状态动作与去重更多菜单已进入公司树，审计 UI 不再暴露；scope 已释放。
- `BUG-F006-PURCHASE-WAREHOUSE-RECEIVING-BOUNDARY-001` — `merged` — WarehouseReceiving 入口、模块/权限复用和目标测试已进入公司树，采购详情不再直接确认收货；scope 已释放。
- `BUG-F006-PURCHASE-RECEIPT-DUPLICATE-001` — `merged` — 唯一收货任务、active receipt 冲突、部分收货、幂等/QC/关闭测试已进入公司树；旧“打印收货单”要求已被产品决定取消，scope 已释放。
- `BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001-CORE` — `merged` — V94、AP settlement Controller/Service/DTO/Entity/Repository、幂等余额约束及 JPA/边界测试已进入公司树；独立 CORE scope 已释放。
- `BUG-F006-R2-CUSTOMER-NAV-001` — `merged` — 客户列表/详情导航、状态保留及 source spec 已进入公司树；scope 已释放。
- `UX-F006-R2-SKU-PROCESS-001` — `merged`（后续架构吸收）— 工序 drawer/search/filter 已在公司树，旧 output-unit 语义被后续工序治理明确移除；独立 scope 已释放。
- `BUG-F006-R2-WORKFLOW-VIEWPORT-001` — `merged` — 1366/1440/1920、缩放与布局预算 helper/spec 已接入编辑器；scope 已释放。
- `FEATURE-F006-R2-BOM-SUBSTITUTE-001-CORE` — `merged` — V99、结构化替代料 Entity/Repository/Service 与真实 JPA/Service 测试已进入公司树；scope 已释放。
- `BUG-F006-R2-WORKFLOW-INLINE-PROCESS-CATEGORY` — `merged` — 前端现场新建分类必选与后端 taxonomy/跨工厂校验及测试已进入公司树；scope 已释放。
- `BUG-F006-R2-WORKFLOW-CELL-ACTIONS-UX` — `merged` — 节点 edit/delete 在 hover/focus/selected/touch 下的行为与编辑器测试已进入公司树；scope 已释放。
- `BUG-F006-R2-WORKFLOW-INPUT-MODE-DERIVATION` — `merged`（当前模型取代）— 后端当前明确丢弃 process portGroups，替代关系由原料节点 substituteOfNodeId 表达；旧独立 input-group scope 已释放。

- `MIGRATE-CODEX-ORCHESTRATION-ROUTING-20260823` — `merged` — Owner: `/root` — Base SHA: `7129c9b527e5c4fc823e5827b0a50d3b5bcfc8cd` — 公司仓 PR #11。在最新 main 上无冲突重放已验证的 `ba22cbc92b03ff9ac2468eebe1761dcb7541d7ef`，来源与重放 patch-id 均为 `8db12e9e504146736fe5d6fc6ee636bca75b0f52`；新增单代理优先静默路由与 5 个窄职责 Cretas agent，删除旧项目 `agent-team`，保持 model-effort 非阻塞，并纠正 F006 凭证模板边界。TOML 6/6、agent schema、Codex config load、hook 5/5、tracked-secret scan、阻塞模型门禁零命中、独立只读终审和 `git diff --check` 通过。ACTIVE 全量审计以实时 Git/worktree/PR/commit/process/task 证据将 36 条收敛为 15 条、34 个 scope 收敛为 9 个，20 条完成/重复记录逐项归档，证据不足的 6 条已恢复保留。未修改 Claude/Cursor、产品代码、生产或凭证真值，严格 `NOT_DEPLOYED`；scope 已释放。
