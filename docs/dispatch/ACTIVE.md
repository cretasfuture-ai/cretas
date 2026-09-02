# Dispatch 台账 — ACTIVE
**最后更新**：2026-09-01（organizer 交接后重写在飞段）

**当前真值**：`origin/main` 为代码与合并真值；本文件为当前任务状态真值。完成记录见 [2026-09-01-active-history.md](archive/2026-09-01-active-history.md)、[2026-08-31-active-history.md](archive/2026-08-31-active-history.md)、[2026-08-30-active-history.md](archive/2026-08-30-active-history.md)、[2026-08-29-active-history.md](archive/2026-08-29-active-history.md)、[2026-08-27-active-history.md](archive/2026-08-27-active-history.md)、[2026-08-26-active-history.md](archive/2026-08-26-active-history.md)、[2026-08-25-active-history.md](archive/2026-08-25-active-history.md)、[2026-08-24-active-history.md](archive/2026-08-24-active-history.md)、[2026-08-21-active-history.md](archive/2026-08-21-active-history.md)、[2026-08-17-active-history.md](archive/2026-08-17-active-history.md)、[2026-08-16-active-history.md](archive/2026-08-16-active-history.md)、[2026-08-15-active-history.md](archive/2026-08-15-active-history.md)、[2026-08-14-active-history.md](archive/2026-08-14-active-history.md)、[2026-08-13-active-history.md](archive/2026-08-13-active-history.md)、[2026-08-12-active-history.md](archive/2026-08-12-active-history.md)、[2026-08-11-active-history.md](archive/2026-08-11-active-history.md)、[2026-08-10-active-history.md](archive/2026-08-10-active-history.md)、[2026-08-09-active-history.md](archive/2026-08-09-active-history.md)、[2026-08-07-active-history.md](archive/2026-08-07-active-history.md)、[2026-08-04-active-history.md](archive/2026-08-04-active-history.md)、[2026-08-03-active-history.md](archive/2026-08-03-active-history.md)、[2026-08-02-active-history.md](archive/2026-08-02-active-history.md)、[2026-08-01-active-history.md](archive/2026-08-01-active-history.md)、[2026-07-31-active-history.md](archive/2026-07-31-active-history.md)、[2026-07-30-active-history.md](archive/2026-07-30-active-history.md)、[2026-07-29-active-history.md](archive/2026-07-29-active-history.md)、[2026-07-28-active-history.md](archive/2026-07-28-active-history.md)、[2026-07-27-active-history.md](archive/2026-07-27-active-history.md) 与 [2026-07-26-active-history.md](archive/2026-07-26-active-history.md)，此前历史见 [2026-07-25-active-history.md](archive/2026-07-25-active-history.md)。

## 在飞任务

### 📌 2026-09-02 凌晨 · LIUSHANMEN 走查线 · organizer 状态快照（`liushanmen-organizer-handoff-fa5fb5-bb`）

**背景**：owner 明早给客户演示，通宵走操作手册；organizer 22:00 接手（交接件 `docs/audits/liushanmen/2026-09-01-ORGANIZER-HANDOFF.md`）。
**判据口径**：每次部署都在**运行中的制品**里核排他标记 + 阴性对照，⛔ 不看 exit code；web 侧从 live `index.html` 追引用链，⛔ 不裸 grep 部署目录（孤儿 chunk 累积）。

**已合 main 且已部署 prod（8 次部署，最新 `832847cec3` @ 01:19）**：#442 #444 #445 #446 #447 #448 #449 #450 #451 #452 #453 #454 #455 #456 #457 #458 #459 #460 #461 #462(nginx) #463 #464(迁移 `V20261101_23`, success=t) #465 #466 #467 #468 #469 #470。
**已合未部署**（下次一起发）：#471 销售主录入面含税 / #472 列表列口径 / #473 表头税率读权威值 / #474 「个」不再碾成 pcs / #475 假注释订正。

**在飞（🔴 演示唯一拦路）**：`FIX-LSM-TOLL-PROCESSING-AUX-20260902` — 缺陷 38：来料加工计划领不出工厂自有辅料/包材。owner 裁定**按类别分档**（RAW 保持严格客户血缘；AUXILIARY/PACKAGING/SEASONING 允许公司库存）。两个调用点（领料 `findEligibleSourceBatches` + 报工 `findEligibleBatchesForUpdate`）必须一起改。⚠️ 阳性对照必须是 `CUSTOMER_SUPPLIED` 计划 + BOM 含 `COMPANY_OWNED` AUX（全客供的单是恒真式）；🔴 阴性对照「别的客户的 RAW 仍被拒」是这次改动能上线的全部理由。Owner: subagent `ae6b1c85d180026c0`，WIP `83f7ca9ea2`。
**在飞**：`FIX-LSM-SPEC-DIALOG-MERGE-20260902` — 缺陷 16 UI 合并（lucid-nobel），⛔ 只做 UI，`active` 列**不删**（读取方非空集：索引/2 查询/停用功能/3 前端消费方；删掉=失去「单独退役一个包装规格」），等 owner 复核。

**🔒 等 owner**：规格表 `active` 列去留 / 缺陷 16 那三个重复字段（`min_order_quantity`/`lead_time_days` 读取方为空可删，两阶段：先去字段再 DROP，蓝绿窗口旧实例会读到不存在的列）。

**登记未派（无执行者）**：31 开工前不做料齐检查（BOM 建计划即钉死，可纯计算短缺；现在每道工序各撞一次墙）/ 32 混批面板渲染在提交区下方且「正式报工」不可点无说明 / A 同一批次 5900 vs 5800 口径未标（⛔ 别改成一个数，并列标口径；批次行 used/reserved 仍为 0，预扣是算出来的）/ B 空态「仓库是空的」而同屏写着 56 / C 拼音搜索跨页面缺失（统一组件不逐页补）/ D 分摊成本单价加口径说明（客供料材料成本 0 是对的不是算漏）/ E 半成品批次工序投入区「无生产日期/成本 0」与总览行不一致 / F 表头点文字排序 vs 点图标 / 缺陷 33 根治：`UNIT_ALIASES` 目标码对齐后端 `systemAliases()`（**不动点判据**：每个目标码 `normalize()` 后等于自己；⚠️ 该表参与构造请求体，上次加 `'斤'→'jin'` 弄坏 SeasoningBindingDialog）/ 全站不带 actionHint 的 409 静默 / `production_manager` fallback 漂移 6 条 / `priceSource` 词表分散 / 领料单「累计已领 vs 计划」无人管（分批领料缺幂等提示）/ 规格名称自由文本与换算系数矛盾 / 采购规格单位应与原料字典联动（设计）/ `MergePurchaseDialog` 同形单位碾平 / 后端 `update` 仍接受无价格（organizer 判：不收，否则存量关系连停用都不能；要收须配「不带价格也能停用」豁免）。

**今晚被执行 session 纠正的 organizer 错误（保留机制：让他们敢反驳）**：PR comment 当载体 / `required` 是装饰 / 硬约束 7 过度套用 / 「两个空态同因」判反 / `router.push` 会毁掉半填表单 / 「共享断言守历史」只对一半成立 / brief 说 5 处实际 8 处 / `priceUnit` 提示会只修一半 / 「来料加工从未走通」太宽 / 拿测试文件里的串当部署标记 ×3 / 拿过期生产计数当论据 ×3。

- `QA-LSM-APPROVAL-403-AS-ZERO-20260901` — `merged` — Owner: `claude-code/liushanmen-organizer` — **PR #449 已合入 `e88d769870`**。生产实测(LIUSHANMEN 真实客户): `lsm008` 质量经理打开 `/production/approval` 看到「待审批 0」+ 暂无数据 + 零提示, 而 `lsm015` 生产经理同一 URL 是「待审批 26」⇒ **那个 0 不是空列表, 是挡在 26 条真实待办前面的伪造读数**。机制: `loadData()` 的 catch 吞掉 403, `allData` 停在初始 `[]`, 模板据此渲染一个 confident 的 0 —— **「加载失败」与「真的没有待审批」在数据模型里是同一个状态**; `response.success === false` 那条分支同样静默。硬约束 8: 同页同形态 **BEFORE=2**(`loadData` / `loadCorrectableReports`), 两处都修, `2 + 0 == 2`。硬约束 9: 每条失败用例**同时**断言「假的 0 不出现」**和**「`.load-error` 面板确实出现」(只断言前者的话, 组件整个渲染失败也满足)。**阳性对照**: 真的 200 且空队列时**仍然显示「待审批 0」**, 证明没有过度修正成永远显示「—」。变异两条各自只打红为它设计的那条。全量 web-admin 3980 passed / `vue-tsc -b` 0 error。排他性核验: `loadError` 合并前 **0** 次、合并后 **10** 次。
  ⚠️ **刻意未做**: ⛔ 没碰 `request.ts` 吐司层。「是这一页吞了 403, 还是两条相同的 403 被折叠成一条」**至今未定** —— 403 分支 `severity` 缺省为 `'BLOCKING'` ⇒ 走 `ElMessageBox.alert` 模态, 且另有 `showSingletonNotification` 单例 + `ElMessage grouping` + 2000ms `_toastSeen` 去重, **三种机制都会把并发的相同错误折叠**。本修复与哪一种为真**无关**, 故两件事分开。
  📌 报告者自曝两处**自己的仪器错误且都没报成缺陷**(`innerText.length` 把菜单文字算进空判定 / `lsm015` 登录 POST 根本没发出去是 refs 过期), 这是它其余读数可信的理由。

- `QA-LSM-APPROVAL-MENU-ROLES-20260901` — `review` — Owner: `claude-code/liushanmen-organizer` — **PR #450**, Base `3c5e36b4fd`。上一条的另一半: **菜单放行而后端拒绝**。两侧判的不是同一件事 —— 菜单按 `module:'production'` → `canAccess()`(quality_manager 的 production=`'r'`)放行, 后端 `GET /pending-approval` 的闸是 **`@RequireRole` 角色白名单**(`ProcessWorkReportingController:41`), 差集就是「看得见点不开」。
  🔴 **不是权限漂移, ⛔ 别去改权限矩阵**: `quality_manager` 的 `production:'r'` 在 **DB L1 / 两份前端 fallback / Java fallback 四处一致**, 且 `V20261030_11` 显式保留过同类跨模块只读(「质检结果影响生产」)。撤它会打断一批角色的合法访问。
  ⚠️ **菜单 `roles[]` = `@RequireRole`(5) ∪ `RequireRoleInterceptor.PLATFORM_ADMIN_ROLES`(4)**。第二个集合不能漏 —— 那 4 个平台角色**无条件绕过** `@RequireRole`, 只照注解抄会把**本来打得开的页从平台管理员菜单里藏掉**(反向缺陷)。
  ⚠️ menuConfig 里那段 ⛔「不加 roles 白名单」**不适用本条**: 那段前提是「菜单闸判的模块 == 后端闸判的模块」, 而本页后端判的是角色名单。它自己写的另一半正是本条 ——「菜单宽、页面窄」。`workshop_supervisor` 在白名单内, 那段担心的误伤有阴性对照钉着。
  🔴 **元闸拦了我一次且是对的**(本仓第三次同形): 本闸读两个 Java 文件而 `web-admin-gate` 的 `paths:` 不含它们 ⇒ **只改 `@RequireRole` 名单的 PR 恰好是它看不见的那类改动**。已**逐文件**登记进 `push` 与 `pull_request` 两处; ⛔ 没用宽 glob(8-28 有人那样补触发面, 把另一道元闸的阴性对照变成恒真式)。
  判据: 新闸 5/5 + 变异两条各打中自己那条 + 回归 12 文件 116 条 + `vue-tsc -b` EXIT=0 零输出 + 元闸修前 1 红修后 3/3。

- `FIX-LSM-SUPPLIER-PRICE-REQUIRED-20260901` — `in-progress` — Owner: `lucid-nobel-4a3795-7c` — 缺陷 **16 + 17** 一批(同文件, 必须一起数才数得全)。Scope: `web-admin/src/views/procurement/suppliers/SupplierDetailDrawer.vue` + 目标测试。
  🔴 **审计的「给价格字段加 `required`」是错的做法, 照做会产出假修复**: 该文件 3 个 `<el-form>` **全都没有 `:rules`/`ref`**(其一连 `:model` 都没有), 8 个 `required` 只有 5 个带 `prop`, **全文件没有一处调 `validate()`** ⇒ `required` 是**装饰**, 加了只是一颗红星 + 零强制, 而且**截图好看、视觉验收会过**(形态 B)。真正的校验手写在 `saveRelation`(~`:586`) 与 `savePurchaseSpec`(~`:681`) **两个不同函数**。
  价格字段 **3 处不是 2 处**: `:218` 关系表单 / `:242` **内联规格(新增路径)** / `:299` 独立规格(编辑路径)。⛔ 漏 `:242` = 拦住编辑、放过新增, 而空价格主要从新增进来。
  🔒 **Owner 2026-09-01 裁定「只在价格被动过时拦」**: 新增必填, 编辑仅当用户改动了价格字段才校验。理由 —— LIUSHANMEN 有 **4 条存量关系价格未设置**(DEMO235005 的 WL026/WL065/WL130 + 上海鹃城 YL063), 无条件拦会逼着只想改启用状态的用户先编一个价, **而编出来的价比空值更糟(空值还拦得住, 编的价一路通过)**。
  ⚠️ 未核: 后端/Excel 导入路径对空价格的行为是否与「必填」一致(前端拦住而接口还能进空值)。⛔ 若需改后端 → 🔒 回协调者。

- `QA-LSM-443-ACCEPTANCE-20260901` — `in-progress` — Owner: `strange-yalow-28966b-4e` — PR **#443**(线性步骤列表 UI)的浏览器/对抗性验收。**#447 已先合**(见下), #443 rebase 后验**最终形状**, ⛔ 不验一个此后还要变的树。
  🔒 **边界(协调者背书)**: 只在 Mac mini 测试环境(`100.85.234.108:10010`)驱动 UI; **生产只读, ⛔ 不点保存**。理由: 验「有在途实例时只读闸拦得住」要求在真客户审批链上点一次保存, 闸若失效就是**亲手再造一次缺陷 27**, 而 `PO-20260901-0001` 已经因此永久卡死、恢复入口(#446)尚未部署。在途实例改为在测试环境构造。测试环境验不了的部分**一律标「未验证」**, ⛔ 不拿测试环境的绿冒充生产结论。

- `QA-LSM-READONLY-ROLE-SWEEP-20260901` — `review` — Owner: `adoring-hellman-b219c0-07` — PR **#445**(docs-only)。`lsm006` 仓储主管 15/15 页可达无 403; `lsm004` 销售主管 6/6 干净; `lsm011` 操作员 / `lsm013` 质检员 → `/mobile-only`, **MOBILE_ONLY 设计如此不是缺陷**。
  🔴 **覆盖缺口(不是「走过了没问题」)**: 质检段与报工段的执行角色都是**移动端专属**, Web 只有管理视角 ⇒ **手册那两段在 Web 上走不完**, 需另派 RN 线, 不在本线范围。
  📌 刻意未追(登记防丢): ① `lsm015` 菜单 60 项且含质量管理组(疑似 1 的**反向镜像**); ② `/sales/orders` 同屏「共 12 条」vs 标签「全部 10」+ 表格 10 行 —— **可能是两个合法维度**(工作流状态 vs 履约状态), 未查证故只记疑似 ⛔ 未当缺陷记账。

- `OBS-SILENT-409-WITHOUT-ACTIONHINT-20260901` — `queued` — Owner: 无 — 🔴 **全产品级静默**: `request.ts:539` 的 `isVanillaOptimisticLock = (status===409 && !actionHint && !hintTarget)` 把这类 409 的 toast **整条吞掉**(本意消 optimistic-lock 噪音), 而业务侧 `handleCatchError` 见 `err.status` 有值又认为「拦截器已经显示过了」而闭嘴 ⇒ **两层各自以为对方会说话, 用户看到「点了没反应」**。
  ▎与 `QA-LSM-APPROVAL-403-AS-ZERO` **是同一形态**(两层互相推让), 只是一个吞提示、一个把失败渲染成一个肯定的数字。
  #446 只用 `actionHint` 治好了缺陷 27 那一条; **「任何不带 actionHint 的 409 在全站静默」仍然成立**。收窄它会改全站行为 ⇒ 先量爆炸半径再动。由 `lucid-nobel` 查出并明确未擅自收窄。

- `OBS-PRODUCTION-MANAGER-FALLBACK-DRIFT-20260901` — `queued` — Owner: 无 — `V20261030_11` 把 `production_manager` 的 `analytics`/`report`/`procurement` 在 DB 收窄为 `'-'`, 但**前端 store fallback 与 Java 硬编码 fallback 都仍是 `'r'`** ⇒ 3 模块 × 2 份 = **6 条陈旧条目**。与 PR #441 给 `finance_manager` 修的**同一形态**, 但**不在**那份 `KNOWN_DRIFT` 名单里(它只圈了 finance_manager)。DB 可达时 L1 胜出故**休眠**, 只在 DB 不可达/竞态的 fallback 窗口显形 —— 而那正是 #441 关心的窗口。



- `FIX-QC-VERSION-OTA-APK-20260829` — `in-progress` — Owner: `/root` — Base SHA: `0f11d9a91bdfb77115c9cc3cebab05984f02a458` — 质检员“关于/设置”版本号改读 APK 原生真值并补一致性闸；合入后从 clean exact `origin/main` 向错误原生标识仍报 1.0.3 的现场设备发布兼容 OTA，同时重建并发布原生 runtime 已修正、保留最低 APK 版本门禁的 1.0.4 签名 APK。Scope lock：仅 QIProfile/QISettings、quality 中英文版本文案、nativeVersionConsistencyContract 测试、ACTIVE/当日归档；验收为质检上传目标测试、版本一致性闸、TypeScript、签名/启动冒烟、OTA manifest/CDN 与 APK CDN/版本/哈希验证。
- `HOTFIX-LIUSHANMEN-QC-CAPTURE-UPLOAD-20260829` — `in-progress` — Owner: `/root` — Base SHA: `0ed3f10da8` — Scope lock: RN `QILabelQcCameraModal.tsx`、`QILabelQcCreateScreen.tsx`、`labelQcApi.ts`、对应 Label QC camera/create/API 目标测试、`docs/specs/label-qc-cold-start.md` 与本任务 dispatch；照片本地压缩后立即添加，远程清晰度检查只作后台建议，5 秒超时、断网、服务失败和明确拒绝均不得删除照片或阻塞正式提交，状态不得伪称“已自动检查”。验收：RN 目标 Jest、TypeScript、`git diff --check`、Android production OTA、线上 manifest/CDN、真实设备 LIUSHANMEN 拍照立即返回表单并成功上传回读。
- `QA-WEB-VISUAL-WALKTHROUGH-20260828` — `merged` — Owner: `claude-code/visual-acceptance` — **用 web-admin 逐屏走查(视觉验收)一轮, 抓到 3 个接口层/类型检查/单测全都抓不到的缺陷**, 并把其中重复三次的那一类升级成闸。凭 `f006_admin` 登录 prod(`139.196.165.140:8086`), ⛔ 全程从菜单点进去, 不用 `goto(url)` —— 本仓 8-26 实测过「E2E 直接敲 URL 对『入口缺失』这一类系统性免疫」。
  **① `String.equals(枚举)` 恒 false(PR #249, 已上生产并目视复验)**: 批次详情页顶部状态「已完成」而生产时间线最后一条写「**生产取消**」。根因 `"COMPLETED".equals(batch.getStatus())` 而 `status` 是 `@Enumerated` 枚举 ⇒ **每一个有 endTime 的批次时间线都写「生产取消」**。同一形态第二处在 `MobileBusinessServiceImpl` 人效评分, 是个**两道都坏的双保险**(`.name().equals(枚举)` 一样恒 false) ⇒ `completedCount` 恒 0 ⇒ **所有有批次的人效率分恒为 0**。🔴 讽刺: 同一文件第 108 行写的是 `==` 枚举比较, **一处对一处错**。⚠️ **javac 不报警**(equals(Object) 收任何类型) ⇒ 判据用**行为断言**驱动 `getBatchTimeline` 断言它产出的标签, 不用源码扫描(换个写法就绕过去)。**范围数过了**: 全后端 `"字面量".equals(x.getStatus())` **72 处**, 但绝大多数比的是真 `String status` 字段, grep 判不出类型 ⇒ **只改已确认的 2 处**, 正解是上 ErrorProne `EqualsIncompatibleType`(编译期就能报), ⛔ 不靠猜。**目视复验**: 同一页面同一批次, 修前「生产取消」→ 修后「**生产完成**」。
  **② 质检标准四张 KPI 卡字段名与后端【一个都对不上】(PR #251, 已合)**: 页面「已启用 **0**」而**同屏表格**那条写着「启用」(prod 实测 `enabled = true`)。前端读 `totalItems/enabledItems/requiredItems/criticalItems`, 后端产出 `total/requiredCount/criticalCount` ⇒ 四张卡全 `undefined` → `|| 0` → 一律 0。**之所以没人发现: 三个真值恰好是 0, 第四个有 `|| tableData.length` 兜底(而那是【当前页】行数, 超过一页就少报)**。顺带订正名实不符: 后端那个 `total` 装的是 `countBy...EnabledTrue...`。`|| 0` 一律换成 `?? '—'` —— **字段名再对不上时显示「—」而不是一个很有说服力的 0**。
  **③ 跨语言字段名棘轮(PR #253, 已合)**: 同一形态一晚三次(②/财务概览 `monthlyTransactionCount`/采购单 `unpaidAmount`) ⇒ 不再逐个修, 建闸。扫 554 个 `.vue` 的 `<template>` × (4156 `.java` + 1754 `.py`), 断言前端读的字段名在后端源码里出现过。**存量 17 处, 冻具体名单不冻计数**(修好一个同时新增一个时计数不变, 名单会变)。⚠️ **第一版只扫 Java, 把 SmartBI/餐饮分析(Python 服务的)那 4 条报成违例, 21 → 17 —— 少一个语料 = 造一批假阳性**。
  🔴 **建闸时元闸拦了我两次, 两次都对**: ① 我的闸读后端源码而 `web-admin-gate` 的 paths 不含它 ⇒ 元闸报「真正该拦的 PR 一次都不过它」; ② 我改用宽 glob `src/main/java/**` 补触发面, **把元闸自己的阴性对照变成了恒真式**(「匹配器过宽 —— 连没被引用的文件都判成已覆盖了」)。⛔ 正解不是改那条阴性对照(削弱别人的闸迁就自己), 而是承认跨语言契约不属于 web-admin 那棵树 ⇒ 新建 `crosslang-contract-gate.yml`。⚠️ **给闸补触发面时, 宽 glob 会让别的闸「证明自己不过宽」的对照失效。**
  **⛔ 我差点报的两个假阳性(都被读文档/读 tooltip 拦住)**: ① 批次详情页「良品率 —」在我改之前就是「—」(该页 8-26 已按 `goodQuantity == null` 守对), **不是有效验收目标**; ② 销售订单页四个数(菜单 21/工作流 32/共 41/Tab 全部 10)看着矛盾, 而 Tab 那个有 tooltip 明说「分页数字来自**当前已加载的同一批订单**」⇒ 已登记, 不是缺陷。
  **📋 已量清未修(登记)**: (a) 「合格率」两个口径 —— 首页 **68.77%**(13 张单 `pass_rate` 算术平均) vs 质检记录页 **46.2%**(合格单数/总单数 6/13), 差 **22.6 个百分点**, 两处都不标口径; 且底部「合格 6 + 不合格 4」而总数 13 —— **3 条(1 有条件放行 + 2 PENDING)不算分子也不算不合格, 但进了分母**。(b) 财务概览「本月交易笔数 **0**」而列表里本月交易一大把(已进 #253 的 BASELINE)。(c) `ProcessingServiceImpl.getWeeklyBatchesCost` 里 `efficiency`/`actualQuantity`/各项成本仍有同形的 `ZERO` 兜底 —— **efficiency 当前 38 个批次为 NULL ⇒ 那个接口现在就在把它们报成「0% 效率」**, 与本轮改动无因果, 独立登记。(d) 财务菜单「会计凭证」分组标题**连续重复三次**。(e) 销售订单页同屏四个计数只有一个标了口径。
  **⚙️ 四项待裁定的结论(已用生产数据判完, 不再挂账)**: ①盘点阈值 **现在不定** —— 186 条明细只有 7 条有差异, 其中 2 条是测试数据(系统 2000 → 实盘 1), 真实差异只有 5 条 ±1 盒/±0.1kg, **5 个样本定不出阈值**, 解冻条件: 真实盘点单累计 ≥30 张且差异样本无测试数据; ②LIUSHANMEN 配 `INVENTORY_ADJUSTMENT_APPROVAL` **已经不用做** —— 实测已 published+enabled, 且它半成品盘点实例 **0 张**(F006 有 8 张); ③F006 调拨审批 **不用改代码** —— `INVENTORY_TRANSFER_APPROVAL` 已配置, 页面上草稿有「提交审批」入口, 那 6 张自己批自己的是配置生效前的存量; ④可配置质检项 **不接入口也不删** —— 全库唯一那条是 `E2E-质检项-mr42epir`(E2E 账号建的), 绑定 0 条, **零真实使用**。
  ⚠️ **交接件里被证伪的一条**: 「质检不合格数回流到批次良品数」**不该按原样修**。13 张质检单里 **10 张是 `f006_quality_insp`(E2E 测试账号)录的**、`inspection_mode` **13/13 全 NULL**(分不清抽检全检)、`sample_size` 无单位而批次单位是「盒」(有 5 条样本量 > 批次产量, 一条是 10 倍)、`quality_defects` **0 行**。照原样实现会把 E2E 造的数字变成生产上的良品数 —— **那是在建幻觉**。


- `QA-JAVA-NIGHTLY-RATCHET-20260827` — `merged` — Owner: `claude-code/nightly-burndown` — **夜间棘轮的第一次真实读数：基线 49 → 5，并抓到它建成以来的第一批新增红（8 条）**。判据来自 run `33081570292`（`java-full-nightly` on `9d26e655`，自建 runner）：`共跑 12007 个测试 · 红 13 · 基线 49`，`已修好 44` / `没跑到 0` / `新增 8`；`mvn` 侧 `Tests run: 12007, Failures: 2, Errors: 11` 对得上，且 `12007 > 11891 × 0.9` 未触发 rc=2 ⇒ 这一轮完整。**#197（`ddl-auto` `create-drop` → `create`）的 H2 schema 毒源诊断成立**（44/49）。落地 **#233**（修 8 条新增红）+ **#234**（削基线 49 → 5）。交接件 `HANDOFF-2026-08-27-nightly-ratchet-first-real-catch.md`。**收尾判据**：合并后又跑一次（run `33085335136` on `04f0ecbb`）得 `共跑 12009 个测试 · 红 5 · 基线 5 · 没有新增红`，rc=0，两个失败分支均 `skipped`；且总数 12007 → **12009 正好 +2**（= #233 新增的两条阴性对照）⇒ 那次跑批跑的确实是合并后的代码。

  🔴 **上一棒有一句是错的**：「剩下的 49 条全部是同一个根因」——对 5 条不成立。`FactoryConfigServiceImplReorderIT` 4 条的真实报错是 `Connection to localhost:5432 refused`（要真 PostgreSQL，由 `ci.yml` 的 pgvector 服务容器提供，而 `java-full-nightly` 没有 `services:`，自建 macOS runner 也起不了）；`SalesOrderFinanceApprovedFanOutIntegrationTest` 1 条是异步 fan-out 轮询到超时（第一条断言**是通过的**）。**H2 毒源和「缺 PG」两条不同的路通向同一个长相（本机绿 / CI 红）** ⇒ 症状分布集中不等于根因只有一个。

  **第二轮：5 → 4。** (b) 那条（`SalesOrderFinanceApprovedFanOutIntegrationTest`）查清了，**「真回归」和「轮询窗口不够」两个猜测都不对** —— 夹具建了 operator 却把拿到的 id 扔掉、发事件时写死 `1L`，而 `production_plans.created_by` 有外键指向 `users(id)` ⇒ 是否通过取决于「此刻库里有没有 id=1 的用户」（CI 上自增列早被推进：`@Transactional` 测试插入后回滚，行没了而自增列不退）。**#241** 已修，run `33090976753` 报 `已修好 1 条（跑了而且通过）`、`没有新增红`。⚠️ 值得记的是**两次复现尝试都失败且各自都能骗人**：本机单跑照样绿（会读成「CI 环境问题」），`jdbcTemplate` 硬删占位用户没生效（会读成「假设被证伪」）—— 靠把中间量打出来（`usersWithId1=1`）才知道是仪器没生效，最后用变异（换成 `999_999L`）拿到与 CI **逐字同源**的失败签名。

  ✅ **第三轮：4 → 0（Owner 拍板选 (A)）。** 给自建 runner 装原生 **PostgreSQL 17.11**（Homebrew LaunchAgent，与两个 runner agent 同一套 `~/Library/LaunchAgents` + `RunAtLoad` 生命周期）＋ 建 `cretas_user` / `cretas_r10_it_test`。run `33093132951` 报 **`共跑 12012 个测试 · 红 0 · 基线 4`** ＋ `已修好 4 条（跑了而且通过）` ⇒ **基线削到 0**（空 ≠ 停用：现在守的是「一条新增红都不许有」）。⛔ 服务容器那条路在这台机器上走不通（实测 `docker: command not found`）；不需要 pgvector（`r10-it` 的 schema 由 `spring.sql.init` 建）。

  🔴 **同一轮里差点在新载体上重演 #197**：这台机器上跑着**两个** runner agent，而 `java-full-nightly` 的 concurrency 组**按 ref 分**（`java-full-suite-<ref>` / `nightly`）⇒ 两条 PR 的全量跑批可以同时落在同一台机器；而 `r10-it` 的 schema 每次 `DROP TABLE IF EXISTS` + `CREATE TABLE` 重建 ⇒ 共用一个库就是**互相 drop 对方的表**——**与 #197 那个 H2 毒源同一个形状，只是换成 PostgreSQL**。⇒ 改成一个 runner 一个库（`${R10_IT_DB:cretas_r10_it_test}` + 按 `RUNNER_NAME` 派生并按需 `createdb`），并用 `R10_IT_DB=zz_no_such_db` 验过占位符**真的被读到**（拿到 `PSQLException ... "zz_no_such_db"`），再用默认值跑 4/4 绿。

  ⚠️ **这份基线是跟着 runner 走的，不是跟着代码走的** —— 同一个 commit，装 PG 之前 4 条红、之后 4 条绿。切换 runner（`switch_ci_runner.py`）之后**必须当场重量**。影响面自 #242 起变大：PR 闸也跑全量 + 同一份基线 ⇒ 基线不准 = 所有 Java PR 一起红或一起假绿。

  📌 历史（已解决，留档）：剩下 4 条曾是一个需要 owner 拍板的二选一，不是一个测试的修法。`FactoryConfigServiceImplReorderIT` 要真 PostgreSQL，2026-08-27 逐条实测它**当前在任何自动路径上都跑不了也不可能绿**：PR 闸窄选择器按类名后缀选（`...IT` 不在四类里）／`ci.yml` 的 `java-build-test` 是唯一提供 pgvector 服务容器的地方但 `if: inputs.full_audit` 且 `runs-on: ubuntu-latest`，而**托管侧当前被账单挡着**（job 2 秒失败，注解 `recent account payments have failed or your spending limit needs to be increased`）／`java-full-nightly` 会跑它但自建 macOS runner 上 `docker: command not found`（`switch_ci_runner.py` 里记着 ci.yml「试过并撤回」就是这个原因），且那台机器**没有原生 PostgreSQL**（实测 `psql not found`）。⇒ **(A)** 给自建 runner 装原生 PG + nightly 加 bootstrap，或 **(B)** 维持现状等托管额度恢复。⛔ 拍板前不要加 `@Disabled`/`assumeTrue` —— 那会让它在真有 PG 的那条路上也一起沉默。

  ⚠️ **下一棒第一件事**：看 2026-08-27 18:00Z（北京 8-28 02:00）那次 **`schedule` 事件有没有真的发生** —— 这个工作流至今**全部运行都是 `workflow_dispatch`**，cron 直到 #178（今天 05:00Z）才被从 `disabled_manually` 打开。靠人记得手工触发的棘轮，与没有棘轮的区别只是多了一个可以指责的对象。
- `OBS-JAVA-TEST-SELECTOR-COVERAGE-20260827` — `merged` — Owner: `claude-code/java-gate-widen` — **1435 个 Java 测试类里只有 229 个在 CI 窄选择器内，1206 个（85%）跑不到**。
  **已合入 `05f1a5aec5`（PR #242）**：PR 闸从窄选择器换成**全量 + 复用夜间那把棘轮**（`workflow_call`，判断只有一份，⛔ 不复制那 60 行）。PR 上执行的测试 **1128 → 12012**，覆盖类 **229 → 1435**，耗时 ~2min → **6m17s**（实测）。
  **收尾判据（在 origin/main 的实际文件里找标记，不看 exit code）**：`java-gate-pr.yml` 含 `uses: ./.github/workflows/java-full-nightly.yml`、**不再含任何 `runs-on`**；阴性对照 —— 旧的 `-Dtest=` 窄选择器 **0 次命中**（是换掉不是并存）；被调用方 `workflow_call` + 新闸 3 处命中。**「找到标记 ≠ 它能跑」**：#242 自己那次 `java-full-gate / full-suite` 绿，读数 `共跑 12012 · 红 5 · 基线 5 · 没有新增红`，且 `12009 → 12012` 的 +3 精确对上 #235 新增的 3 个 `@Test`。
  **新闸做过变异**：塞 `*ContractTest` 红 / 塞 `*RepositoryQueryValidationTest` 红 / 塞普通测试仍绿 ⇒ 不是恒真也不是恒假。
  ⚠️ **订正一句口径**：原文写「1206 个跑不到」——准确说是「**PR 上**拦不住」。夜间全量确实执行它们（12009 个测试），所以缺口是**反馈时机**，不是从不执行。当天的 8 条新增红正是这个形状：PR 全绿合入，红在 main 上挂了半天。
  **上闸前先量了三件事**（⛔ 拿一次绿上闸就是形态 E）：① 耗时 窄闸 ~2min/1128 个测试 → 全量 **6:21~6:31**/12009；② 稳定性 —— 同一 commit 连跑两遍 + 另一 commit 一遍，三次读数**逐字相同**（`红 5 · 基线 5 · 没有新增红`，即红集恰好等于基线）；③ 换棘轮语义**不放松**原来那四类 —— 基线 5 条无一落在窄选择器里，且**另加一道闸钉住这个论据**（它是快照，会过期）。
  ✅ 顺带修一个既存缺陷：`java-gate-pr.yml` **不在** `switch_ci_runner.py` 的 `WORKFLOWS` 里而自己硬写 `runs-on: [self-hosted, cretas]` ⇒ 9/1 切回托管时切不过去。改后它没有 `runs-on`，归被调用方（在清单里）统一管。
  ⚠️ **9/1 切回托管时基线要重量**：那 4 条 `FactoryConfigServiceImplReorderIT` 要真 PostgreSQL，托管侧 `ci.yml` 有 pgvector 服务容器而自建 macOS runner 没有 ⇒ 失败集必然不同，⛔ 不能假设这份基线还成立。选择器是**按类名后缀**匹配的（`*ContractTest` / `*StartupGuardTest` / `*RepositoryQueryValidationTest` + 逐字列出的 `FlywayVersionUniquenessTest`），与 `.github/workflows/ci.yml` 的 `TARGET_TESTS` 逐字对应。

  **⚠️ 这不是理论问题 —— 2026-08-27 一天之内因它栽了两次**：① `MaterialConsumptionNotesAndPlanRefTest`（一条「现状钉住」型断言）**在 main 上红了很久没人看见**，它设计出来就是为了拦住那种改动，结果拦了个寂寞（本次 CI 日志 grep 该类名 0 命中）；② 我新写的两道闸类名不合规，**只会被编译不会被执行**，靠 `GateSelectorCoverageContractTest` 这道元闸才抓住。

  **⚠️ 元闸的覆盖面要说清楚**：`GateSelectorCoverageContractTest` 只管「**读 src/main 源码的闸**」进不进选择器，**不管普通行为测试**。所以它绿 ≠ 那 1206 个跑过。

  **⛔ 不要靠一个个改类名去补** —— 用类名兼任 CI 选择器本身就脆（名字是给人读的）。方向应当是扩大选择器，或按改动路径选测试。⚠️ 一次性放开会暴露未知数量的存量红，需要先量再定节奏。**本条只登记读数，不带方案。**

  实测口径：`find src/test/java -name '*Test.java'` = 1435；四类后缀合计 229。同类历史读数见 memory `feedback_i_swapped_one_never_executed_gate_for_another`（当时是「1277 个类 push 上只跑 37 个」）。

  **⚠️ 2026-08-27 夜新增第三个实例（`QA-JAVA-NIGHTLY-RATCHET-20260827`）**：#184 给 `MaterialBatchMapper` / `SalesServiceImpl` 加了一个 `@Autowired` 协作者，**7 个手工装配的测试当场 NPE 并在 main 上红了半天没人看见** —— 那四个类（`MaterialBatchMapperBoxCountTest` / `MaterialBatchMapperSp4Test` / `FinishedGoodsBatchManagementTest` / `InvoiceRequestRegistryReachabilityTest`）**一个都不在窄选择器里**，而 #184 自己新加的 `*ContractTest` 在选择器里、是绿的。⇒ **「PR 全绿」在这个仓里当前只覆盖 15% 的测试类**，这条观测的代价现在有价钱了：一次回归在 main 上活了半天，靠夜间棘轮才捡回来。

- `FIN-VOUCHER-CONSUMPTION-SOURCE-20260827` — `merged` — Owner: `claude-code/voucher-source` — **凭证只收生产领用**（Owner 2026-08-27 裁定：「消耗行不进凭证，只有生产领用进」）。**已部署 prod**（21:49，jar mtime 21:49:08 早于进程启动 21:49:40；运行中的 jar 含 `VoucherConsumptionSourcePolicy`，**部署前基线 0**；导出里 `isProductionConsumption` 命中 1；阴性对照 `ZZ_NOT_A_REAL_CLASS`=0；`:10020 /api/mobile/health`=200；nginx 上游已自动同步到 10020；近 40 行日志 0 ERROR）。

  **此前**：`VoucherExportServiceImpl` 是 `for (c : findByTimeRange(factoryId, start, end))`，**不按来源过滤**，窗口内每一行都记成「原料发出」。生产实测（全库 `material_consumptions`）：`RAW_MATERIAL` 83 行 5538.36 / `PACKAGING` 52 行 26.11 / `SEASONING` 46 行 167.52 / `MATERIAL_RETURN` 23 行 **−631.40** / **`TRANSFER_OUT` 23 行 1969.00** / `SEMI_FINISHED` 17 行 777.87 / `MANUAL_ISSUE` 1 行 0.00。🔴 `TRANSFER_OUT` 是仓库之间移库，**货还在厂里**，却被记成消耗。

  **⚠️ 实现时必须偏离字面一处**：`MATERIAL_RETURN` **留在凭证里** —— 它金额是负的，是生产领用的**冲减**，不是另一件事；领用进而退料不进，成本会虚高（实测 −631.40）。「生产领用」在这里是个**会计科目**，不是一个动作名。

  判据收敛成 `VoucherConsumptionSourcePolicy` 一份（`IN_VOUCHER` / `EXCLUDED` 两张表，每条排除写理由），⛔ 不在导出里写 if 列举来源。未知来源**两手都不静默**：运行时不计入但计数 + 打 warn 日志（⛔ 不抛异常，月结不该被一行怪数据卡住）；CI 侧 `VoucherConsumptionSourceCoverageContractTest` 让**新增一种来源在 CI 上就红**。

  🔴 **闸第一版瞄错了对象，被它自己的阳性对照抓住**：扫「全 `src/main` 的 `setSourceType("字面量")`」，当场扫出 `SALES_ORDER` / `PRODUCTION_BATCH` —— 那是**提醒**和别的实体的字段，与消耗行无关；同时**漏掉**真正的写入点（`MANUAL_ISSUE` / `TRANSFER_OUT` 写在三元表达式和常量里）。⇒ 改成与既有 `MaterialBatchSourceDocContractTest` 同形状：扫 `new MaterialConsumption()` 的**构造点**。剩下的常量/变量实参盲区由 `OBSERVED_IN_PRODUCTION`（生产实测值）兜住。

  🔴 **一处夹具订正，且我没把它当既有失败放过**：`VoucherExportServiceTest` 的消耗夹具**不设 `sourceType`** 而 notes 写着「生产领用」⇒ 新口径下成了「来源不明」被排除，台账少一行，断言红。查清是**我造成的**，给夹具如实标成 `RAW_MATERIAL`。⛔ 没留空「让测试过」—— 留空在新口径下本就不该进凭证。⚠️ 这顺带证实源码里那 **4 个不设 `sourceType` 的写入点是真隐患**（`MaterialBatchServiceImpl:2034` / `ProcessingServiceImpl:812` / `ProductionPlanServiceImpl:7607` / `OrderCostBreakdownService` 透传），生产库当前 0 行 NULL 只是巧合。

  **判据**：闸 6/6 + 变异对照（把 `TRANSFER_OUT` 从裁定表删掉 → 先证明行为真变了 → 精确点名 `["TRANSFER_OUT"]` 红 → 恢复 6/6 绿）；凭证族全量 **211/211**。

  **⚠️ 部署路上两件**：① 第一次发布 `DEPLOY_EXIT=1` 且 **`RELEASE_FINAL_STATUS` 一次都没出现**（按判据这本身就是失败信号）。读发布报告查出真因：`java.build=success` 但 `java.deploy=not-selected` —— `ensure_exact_main_after_artifacts` 在制品建完后复查发现 `HEAD ≠ origin/main`。**本地构建 191 秒，期间别的 session 合了 2 个 PR**。闸拒得对；根因是**东京中继 `10.66.66.1:22` 不通导致预热失败，每次都得本地构建**，那近 4 分钟的窗口很容易被撞上。② 蓝绿这次切到 **10020**（上一次是 10010），我按上次的端口查健康得到 000，差点误判成故障 —— ⛔ **判健康前先从 cmdline 确认这个进程监听哪个端口**，不要沿用上次的。

- `QA-WAREHOUSE-P0-ISOLATION-20260827` — `merged` — Owner: `claude-code/wh-p0-isolation` — 交接件 `HANDOFF-2026-08-25-warehouse-storage-layout-stocktake-book.md` §6 的 **P0 逐条收尾**。**已合入并部署 prod**（2026-08-27 16:44；jar mtime 16:43:56 早于进程启动 16:44:25；运行中的 jar 里三个标记逐个命中，阴性对照 `ZZ_NOT_A_REAL_MARKER`=0；`/api/mobile/health`=200；近 40 行日志 0 条 ERROR；⚠️ 部署前基线那三个标记**一个都不在**）。

  **P0.2 跨工厂隔离 — 不是缺陷**。`GET /warehouse/layout` 确实没有方法级 `@RequireRole`（交接件说对了），但 `JwtAuthInterceptor.validateFactoryAccess` 在拦截器层强制 `urlFactoryId.equals(tokenFactoryId)`。prod 实测四组：F006 token → F006 **200**（2223B 真数据）／→ LIUSHANMEN **403**／→ MOCK_REST **403**／无 token → **401**。403 的措辞正是 `sendForbiddenResponse` 那句 ⇒ **拦住它的确实是那道闸**。⚠️ 探针有意义的前提：`factory_super_admin` **不在** `PLATFORM_ADMIN_ROLES`（只有 super_admin/platform_admin/developer）。

  **P0.3/P0.4 位置校验 — 已修（PR #184）**。动手前先数：全后端 `setStorageLocation` **8 处**，其中只有 1 处过判据。🔴 交接件写的是「原料/包材复用旧接口」，按那个描述只会覆盖 3 处 —— 漏掉的正是 `SalesServiceImpl`（**成品的第二条写路**，销售侧编辑批次），「成品已 fail closed」此前只对两条写路中的一条成立。⇒ 判据收敛成 `WarehouseLocationCatalog` 一份（null/空 = 未分配合法；填了必须是本厂已配置编码；配置为空时 fail closed），加棘轮闸 `StorageLocationControlledVocabularyContractTest`。RN 侧同步清了两处硬编码字面量（`'冷冻库'` / `'B区-冷冻库'`）与两处**编造的显示位置**（`'A区-冷藏库'` —— 其中一处在建**库位分布图**，而 F006 的 139 条原料批次位置全是 NULL ⇒ 整张图会是一根假柱子）。

  **P0.5 重复审批 — 守住**。prod 只读探针（F006，规则 13 前置：实时登录响应 factoryId=F006）：对已过账的单再点「应用差异」→ **409**「盘点任务已于 … 生效」；阴性对照传错版本号 → **409**「盘点数据已更新」。两条 409 **措辞不同** ⇒ 是两道不同的闸，不是同一个兜底碰巧都返 409。库存指纹 `139|119843.52|3459.3632` 写前写后逐字相同且**非空**（阳性对照）。⚠️ 仪器错两次：① 第一版没传 `expectedVersion` → 400「必填字段」，请求**从没走到** ALREADY_APPLIED 守卫，「mutation=0」是真的但**理由是错的**；② 库存指纹用了不存在的 `remaining_quantity` 列 → 两次都是空串，「空==空」恒真。

  **P0.5 并发审批 — 结构保证 + 已上闸**。`apply` 是 `@Transactional`，两个并发请求会**同时读到 APPROVED**，应用层那句 `if (status == APPLIED) throw` 拦不住它们。真正拦住的是 JPA `@Version`：`UPDATE … WHERE version=?` 后提交的命中 0 行 → 回滚（含已做的库存调整）。⇒ 保护**是数据库给的不是应用代码给的**；而 `assertExpectedVersion` 是 `if (expectedVersion != null && …)`，**传 null 就跳过**，它只让用户早点看到「请刷新」。⚠️ 摘掉 `@Version` **所有单测照样绿**（串行的）⇒ 写成闸 `OptimisticLockConcurrencyGuardContractTest`（6 个具名关键实体 + 全局棘轮基线 56）。

  **P0.5 未配置流程 — 分情况 fail-closed**。配了 → PENDING_APPROVAL；没配+有差异 → 409 `STOCKTAKE_APPROVAL_WORKFLOW_REQUIRED`；没配+零差异 → 直接 APPROVED（没有差异就没有东西要批）。⇒「仓管自己数自己录自己点就改库存」**不成立**。

  **往下多走两步**：① **PR #192** 盘点提交进 OA 时带上可判的差异量（surplus/shortage/differenceCount + 按单位分开的数量 Map；条件节点是 SpEL）—— 此前是手写 `Map.of` 三个字段，阈值分级**不是没人配是配了也没得判**。② **PR #196** 半成品盘点没配审批流时**无条件过账**（连差异都不看），而它的 `apply` **不只调库存还过财务凭证**（盘盈进收入侧/盘亏进损耗侧）⇒ 对齐成原料同口径，并加闸 `BothStocktakeTypesFailClosedContractTest` 钉住两份一致（两个实体无共同父类，抽不成一份）。⚠️ 两者用的是**同一个** decision_type（`INVENTORY_ADJUSTMENT`）⇒ 一条审批流会同时接到两种单子，键名不一致会让同一个条件表达式对其中一种**静默失效**。生产实测：F006 的 8 张半成品盘点全部在那条审批流发布（08-25 23:15）之前过账，`workflow_instance_id` 全为 null，其中 7 张 `approved_by == submitted_by`。

  **🔍 调拨：查过了，不是同一类缺陷**。「没配审批流就自动批准」在调拨上**不动库存** —— `approveTransfer` 库存语句 **0 处**，库存在 ship/confirm 才动。且它写 `approvedBy = 发起人` 与采购一致（采购也保留 `approvedBy = initiator`，只把 `financeReviewedBy` 置空，因为那是「财务**这个角色**看过了」的声明）。⇒ 代码不改。
  ⚠️ **但生产数据里有一件事值得知道**：调拨单 10 张（F006 CONFIRMED×6/DRAFT×3，LIUSHANMEN APPROVED×1），**已批准的 7 张全部 `approved_by == requested_by`** —— 其中 6 张已 CONFIRMED（货真的动了）。原因是 **F006 没配 `INVENTORY_TRANSFER_APPROVAL`**（LIUSHANMEN 配了且 published/enabled）。⇒ 这是**配置问题不是代码问题**：F006 的调拨要不要审批需 Owner 裁定。

  **待 Owner 裁定（三项，都不需要改代码）**：① 盘点阈值定数 —— 变量已能判，配个 condition 节点即生效；② LIUSHANMEN 配 `INVENTORY_ADJUSTMENT_APPROVAL` —— 否则它做半成品盘点只要有差异就会被 409 拦（原料本来就这样）；③ F006 调拨要不要审批。

  **⚠️ 未做**：`MaterialConsumptionNotesAndPlanRefTest.useWritesNoConsumptionRowWithoutAPlan` 在 `origin/main` 上**就是红的**（在干净 detached 检出上单独跑，8 run / 1 failure，逐字一样），而它红在**任何 CI 选择器都覆盖不到的地方**（本次 CI 日志 grep 该类名 **0 命中**），靠手动点名才现形。问题是「无计划时该不该写消耗行」—— 需 Owner 判。

- `FIX-FABRICATED-QUALITY-NUMBERS-20260826` — `merged` — Owner: `claude-code/f006-acceptance` — 「没人填不良品」被写成「零不良」的**两列**均已清理并部署验证。**`V20260826_05`（PR #137，部署 `v20260826_205828`）**：`good_quantity` = `actual_quantity` 的 8 行 → NULL。**`V20260826_06`（PR #144，部署 `v20260826_213739`）**：**上一条做漏的孪生列** —— 同样这 8 个已完工批次 `defect_quantity` 全是 `0`，清一个留一个等于没修；且这一列**真的有消费方**（`ProcessingBatchCompleteTool` / `ProcessingBatchDetailTool` / `ProcessingCapacityTodayTool` 都在读或累加）。**证伪依据**：4 个批次的 `quality_inspections` 明确记了不合格（10766 抽 80/不合格 5；10806 三次复检全 FAIL；10814 CONDITIONAL 3 不合格），而这 4 个批次两列都声称零不良 ⇒ 从「可能不准」升级为**可证伪的假**。**整批清的依据**：全库 61 批次中 `good < actual` 与 `defect > 0` 的行数**均为 0** ⇒ 从无人工录入，整列存量同源。**不丢信息**：`good` 原值恒等于 `actual`（该列保留）；`defect` 读侧全部 null-safe。⚠️ `defect_quantity` 是**累加器**（`currentDefect.add(teamDefect)`），故只清 `status='COMPLETED'` 的行 —— 进行中批次的 `0` 是合法起点（实测保留 1 行，未动）。**部署后复核**：COMPLETED 8 条两列皆空、`defect_zero` 归零、`actual_quantity` 完好；迁移 `success=t`。**顺带**：`processingApiClient.completeProduction` 只发 `actualQuantity` 而后端三参数皆为必传 `@RequestParam`，但该方法**仅测试在调**（屏幕走 `productionPlanApiClient`）⇒ 死代码，非活缺陷。⛔ CI 因账单全线不执行，判据为本地 + 生产只读探针。

- `QA-F006-COST-QC-ACCEPTANCE-20260826` — `merged` — Owner: `claude-code/f006-acceptance` — F006 生产验收（部署后，接口级 + 生产库只读，写入仅 F006 且已清理）。**已部署两次**：`v20260826_200710`（含 `V20260826_04` BOM 零成本→NULL）与 `v20260826_205828`（含 `V20260826_05` 良品数→NULL），两次均核对「进程启动晚于 jar mtime + 运行制品含本次标记 + 迁移 success=t + 行级业务判据」。**① 成本口径验收通过**：`GET /F006/bom/recipes` 34/34 返回 `totalCost=null`、返回 0 的 **0 条**，与库内 34 条完全对上；行级算术逐条核对（0.05×8.85=0.4425 / 0.125×0.44=0.055 / 1.0×0.01=0.01 全对），4 项原料用量+单价皆空 ⇒ 整条诚实阻断，**没有拿 0 顶替**。**② 成本算不出的真因**：不是代码 —— F006 39 条 BOM 明细中 **34 条缺参考用量、24 条缺单价、仅 3 条两样齐全**；「标准单价」在物料主数据里两个工厂**一条都没填过**；全生产仅 1 个产品（六膳门酱鸭腿 ¥23.95）能算出完整成本。⚠️ 订正此前「成本被算低 8 处」的说法：那 8 行当时并未使成本偏低（相关配方本就被别的缺口整体阻断），修它是**防将来**。**③ 可配置质检项 —— 裁定不接入口**。实测建项/绑定/读回全部 `success:true`（功能本身是好的），但把「中心温度≥75℃」绑到产品后，从质检员侧拉同批次质检记录**零命中**，`qualityInspectorApi` 对 `quality-check-items` **零引用** ⇒ 配置侧与执行侧**结构上不相交**。存量 3 条质检项逐条打开全是测试数据（2 条 `ZTESTE-*` 已软删 + 1 条 `E2E-*`）⇒ **真实使用 0**。而质检本身在跑：13 条真实记录（8-18→8-25，PASS 6/FAIL 4/PENDING 2/CONDITIONAL 1，含抽样 80/合格 75/不良率 6.25%/等级 B/实名检验员）。⇒ 形态 D：接入口＝在能跑的流程旁再开一条要人维护的平行线。测试数据已清理（存活项/绑定回到验收前）。**④ 🔑 顺带证伪历史良品数**：4 个批次的质检明确记了不合格，而这 4 个批次 `good_quantity = actual_quantity`（声称零不良）⇒ 「可能不准」升级为**可证伪的假**；又因全库 `good < actual` 行数为 **0**（从无人工录入）⇒ 整个字段存量同源，故 `V20260826_05` 整批清 8 行为 NULL（原值恒等于 `actual_quantity` 且该列保留 ⇒ **不丢信息、可还原**）。部署后复核：COMPLETED 8 条全部 `unknown`、`still_fabricated` 归零、`actual_quantity` 完好。**⑤ 未做**：界面截图级走查（登录框输密码不在可执行范围，需 owner 自行登录后另行验收）。**⑥ 遗留缺口（未修，建议下一轮）**：质检的不合格数**不回流**到批次良品数 —— 「警报响了没人接」的根因。⛔ **未被 CI 背书**：CI 因账单全线不执行。 ✅ **2026-08-27 补登**: CI 已恢复(切到自建 runner, 加拿大 Mac mini), main 当前 HEAD 上五个闸全绿且带真实读数: RN 2041 tests/184 suites · web-admin 451 test files + `vue-tsc -b` · python 9571 passed/173 skipped · JPA 65 tests · secret 扫全树。⚠️ 口径: 这证明的是**当前 main 是绿的**, 不是「当时那个合入 commit 逐个被验过」—— 那几天的 job 确实一次都没跑。

- `QA-RN-SCREEN-REACHABILITY-20260826` — `merged` — Owner: `claude-code/rn-reachability` — **PR #135 已合入**。RN 侧可达性棘轮闸 `frontend/CretasFoodTrace/src/__tests__/gates/screenReachability.test.ts`（`ORPHAN_BUDGET = 35`），与 web-admin 侧 `routeReachability`（存量 91）成对。**判据**：屏可达 = `Tab.Screen` / 落地页（显式 `initialRouteName` 或该 Navigator 第一个 Screen）/ 非 `navigation` 目录源码里出现名字字面量（覆盖 `navigate|push|replace` 与十几张 `screen:'X'` 快捷操作表）。**变异对照**：注入一个既非落地页也无人跳转的 Screen → 红 36>35 并按名字点出；反向由**真实案例**背书（`DraftReports` 判为可达，正因 #130 真加了一处 navigate）。**🔴 建闸时这把尺子自己错了三次，每次都不报错、只给一个偏的数**：① 只认*显式* `initialRouteName` → 56（虚高 21），而 RN 没写它时用**第一个 Screen** 作落地页，65 个 navigator 里仅 9 处写了显式的 ⇒ 判据要跟**框架默认行为**走；② 用 ``new RegExp(`<${comp}\.Screen…`)`` 拼正则 → **模板串里 `` 是退格符、`\s` 是字母 s**，正则静默永不匹配 → 53 ⇒ 改成复用已解析的 `screenHits`，不再构造带转义的正则；③ 闸把**自己**算进引用池（阴性对照里写着 `'Loading'`，那个屏自己把自己变可达）→ 34 ⇒ 排除 `__filename`，引用池同时剥注释（否则 `// TODO: navigate('X')` 也算可达）。**已知假阳性（写明不开白名单）**：`AppNavigator` 的 `Loading` 只在 `!featuresLoaded` 分支注册，靠**状态**可达而非 navigate，被棘轮预算吸收 —— 白名单是藏东西的地方。**存量 35 个是真屏**：能定位组件的 6 个合计 4226 行（5 个 SmartBI 看板 683~893 行），另 29 个未定位、真实行数只会更多。**判据**：RN jest **1920 passed / 173 suites**；`tsc --noEmit` exit 0；预算调 0 复核真值恰好 35。触发面已核对：`rn-gate.yml` 的 `paths: frontend/CretasFoodTrace/**` 覆盖本闸读的全部文件。⛔ **未被 CI 背书**：CI 因账单全线不执行。 ✅ **2026-08-27 补登**: CI 已恢复(切到自建 runner, 加拿大 Mac mini), main 当前 HEAD 上五个闸全绿且带真实读数: RN 2041 tests/184 suites · web-admin 451 test files + `vue-tsc -b` · python 9571 passed/173 skipped · JPA 65 tests · secret 扫全树。⚠️ 口径: 这证明的是**当前 main 是绿的**, 不是「当时那个合入 commit 逐个被验过」—— 那几天的 job 确实一次都没跑。

- `QA-BUILT-NOT-WIRED-20260826` — `merged` — Owner: `claude-code/built-not-wired` — 四个「做了没接上」逐个核查并按裁定执行。**已合入**：PR **#130**（草稿入口）、**#131**（删 2502 行死代码）、**#129**（结论文档 `docs/handoff/BUILT-NOT-WIRED-2026-08-26.md`）。**① 离线队列 2428 行 + ② PermissionGuard 74 行 → 删**：都不是坏了，是**被另一种写法取代**（形态 D）——`enqueue()` 零调用方、其唯一消费者 `components/offline/OfflineIndicator.tsx` **从未被渲染**（屏幕上那个是 `components/common/` 下另一个 55 行组件，接 `draftReportStore`）；`PermissionGuard` 零使用，RN 现行是直接调 `hasPermission()` 6 处。删除安全性按**符号名**逐个查过（7 个符号全 0 引用）而非只看编译；判据 `tsc --listFiles` 1204→1195（恰好 9 个 .ts）+ jest **1916/172 与删前完全相同** ⇒ 这 2502 行连测试都没覆盖过，与「从未参与运行」一致。**④ 草稿持久化 → 接上**：报工失败存草稿而 `navigate('DraftReports')` 全仓调用数 **0**，工作静默累积无法恢复；屏本身功能完整、早已注册在两个 navigator，缺的只是入口。🔴 **接入口前先修了一个被「没有入口」掩盖的缺陷**：草稿跨工厂持久化，而 `DraftReportsScreen` 零 `factoryId` 引用（列出所有工厂）、`clearDrafts()` 连别的工厂一起清 —— 直接接入口等于把跨工厂串数据的屏推给用户。已加 `clearDraftsByFactory` 并按工厂收口；变异对照：改回 `set({drafts:[]})` 当场红。顺带 `WSHomeScreen:97` 的 `draftCount` 此前是**死变量**（算了从不渲染）。**③ 可配置质检项 —— 仍待产品裁定**：后端 8 端点 + 两表 + RN 配置屏都齐活且已注册，但 `navigate('QualityCheckItemConfig')` 调用数 **0**，生产 **3 条质检项 / 0 条绑定** ⇒ 建了 3 条就停下，很可能正因下一步没入口。要接入口还是连表带屏删，需 Owner 裁定。**共同形态**：四条里 3 条与 #87/#96 同形（做完了、后端通了、屏也注册了，但没有任何地方走得过去）。web-admin 侧已有闸（`routeReachability`，存量 91），**RN 侧目前没有等价的闸**。⛔ **未被 CI 背书**：CI 因账单全线不执行，判据均为本地 + 生产只读探针。 ✅ **2026-08-27 补登**: CI 已恢复(切到自建 runner, 加拿大 Mac mini), main 当前 HEAD 上五个闸全绿且带真实读数: RN 2041 tests/184 suites · web-admin 451 test files + `vue-tsc -b` · python 9571 passed/173 skipped · JPA 65 tests · secret 扫全树。⚠️ 口径: 这证明的是**当前 main 是绿的**, 不是「当时那个合入 commit 逐个被验过」—— 那几天的 job 确实一次都没跑。

- `QA-WEB-ROUTE-REACHABILITY-20260826` — `merged` — Owner: `claude-code/route-reachability` — Base SHA `a0c69db42d`，**PR #121 已合入 `origin/main` `98ff0089bc`**。**做完 ≠ 用户能到**：#87 仓储空间 / #96 车间大屏 同一周两次「做完、部署、RBAC 正确、菜单零登记」，只能手打 URL；#87 的后果是生产 `factory_feature_config` 里 `WAREHOUSE_LAYOUT` **一行都没有** —— 不是功能坏了，是没人找得到。⇒ 「做完功能」此前**没有任何一步是「确认用户能走到」**，故补闸而非再修一次。**闸**：`web-admin/src/router/__tests__/routeReachability.spec.ts`，判据「菜单登记 or 代码里有字面量跳转」，棘轮 `ORPHAN_BUDGET = 92` 只禁增长（动态拼接跳转看不见会误报，故不硬红 —— 一道天天误报的闸最终会被关掉）。**变异对照**：注入一条既不在菜单也无人跳转的路由 → 红(93>92, 按名字点出)；**同一条**登记进 `menuConfig` → 绿(92) ⇒ 量的是可达性不是路由条数。另含阳性对照(读空即红)与阴性对照(`/warehouse/storage-locations` 不许被判孤儿)。**🔴 实测存量 92 条孤儿**：能定位到组件的 56 条合计 **28,899 行 Vue**（另 36 条未定位，真值更高）——HR 13 条(工资单 806 行/排班日历 826 行/请假/加班/年度汇算)、system 12、analytics 9、warehouse 5、凭证导出 1048 行、审批流编辑器 2020 行、调拨、半成品仓……**做完了，没人找得到**。**🔴 顺带查出**：5 条孤儿在 **E2E 里是绿的**（`/analytics/ai-reports`、`/finance/voucher-export`、`/indicator-center`、`/sales/finance-review`、`/system/data-fabric/provenance-config`）——E2E 是 `goto(url)` 直接敲 URL，与用户地址栏手打是同一件事 ⇒ **E2E 对「入口缺失」这一类系统性免疫**，恰好绕开了它本该发现的缺口。故本闸不看 E2E 目录。**⚠️ 建闸时这把尺子自己错了三次**，读数 62→83→84→92 同一份代码：① Windows 上 `endswith('router/index.ts')` 恒假(路径是反斜杠) ⇒ 路由表自己进了引用池，它当然「引用」每一个路径；② `index.ts` 还 import 了 `router/modules/*.ts`，漏掉 34 条路由/其中 8 条是孤儿；③ 62 那次已废弃、文件里如实标注为**唯一没被解释掉的读数**。⇒ 跨平台路径比较先归一化分隔符；「路由表」不一定只有一个文件。**判据**：web-admin 全量 vitest **3640 passed / 448 files**；`vue-tsc -b --force` exit 0 无输出；变异后 `git checkout` 逐字节还原 `router/index.ts`/`menuConfig.ts`。**⚠️ 本批只建闸不改那 92 条** —— 哪些补菜单、哪些本就该废弃是产品判断，待 Owner 裁定。**⛔ 未被 CI 背书**：CI 因账单全线不执行(job 0 步骤/2 秒结束/无日志，GitHub 注解 "recent account payments have failed or your spending limit needs to be increased")，上述判据均为本地跑。 ✅ **2026-08-27 补登**: CI 已恢复(切到自建 runner, 加拿大 Mac mini), main 当前 HEAD 上五个闸全绿且带真实读数: RN 2041 tests/184 suites · web-admin 451 test files + `vue-tsc -b` · python 9571 passed/173 skipped · JPA 65 tests · secret 扫全树。⚠️ 口径: 这证明的是**当前 main 是绿的**, 不是「当时那个合入 commit 逐个被验过」—— 那几天的 job 确实一次都没跑。

- `BUG-UTC-DAY-TRUNCATION-20260826` — `done` — Owner: `/root` — **全仓 184 处「取本地日历日」写成 UTC 截断, 东八区 00:00~07:59 整整差一天**(早班仓库收货正好在这个窗口)。RN 105 处/60 文件 → `utils/formatters.formatDate()`; web-admin 79 处/50 文件 → `utils/dateFormat.toDateString()`。**先核了前提再动手**: 后端 `spring.jackson.time-zone=GMT+8`、服务器 `timedatectl`=`Asia/Shanghai`、`LocalDate BETWEEN` 比业务日历日 ⇒ 送 UTC 日是错的, 不是口径之争。⛔ 整串 ISO(时间戳语义)不动。**PR#110**(前置: 6 个屏的私有 `formatYmd`/`parseYmdOrToday` 副本收敛成一处真源, 顺带 6 处默认日期缺陷; `parseYmdOrToday` 此前**零行为覆盖**, 补 11 条真测试) + **PR#111**(184 处清扫), 均已合并 `origin/main` `2de0c0d9c5`, web-admin 已部 prod, OTA 已推 `runtimeVersion=1.0.4`。**🔴 一个 tsc 抓不到的缺陷**: 机械改写把 `const formatDate = (d) => d.toISOString()...` 变成 `=> formatDate(d)` —— **自我递归、运行时栈溢出**, 6 个文件中招而 tsc **只报 1 个**(有显式 `: string` 返回标注就不触发 TS7023) ⇒ 两道新闸自己扫源码, **不拿「tsc 绿」当判据**, 并专查自我递归; 变异 G2 下闸红而 tsc 仍绿。**闸**: `frontend/.../__tests__/unit/utils/noUtcDayTruncation.test.ts`、`web-admin/src/utils/__tests__/noUtcDayTruncation.spec.ts`(均逐行剔注释 —— 本仓多次栽在「闸把自己写的注释当被测对象」, 而清扫留下的注释里正好写着缺陷长相)。**判据**: RN jest 1799/1799 + `tsc` 0 错; web vitest 3554/3554 + `vue-tsc -b` 0 错; 两侧 `--stat` 与 `--ignore-all-space --stat` 一致(修掉一处误插 CRLF)。**真机验收(小米 f79c50d6)**: 本地 12:xx 时 UTC 日与本地日相同 ⇒ 截图不可判定, 故把设备时区改到 GMT-08:00(本地 8/25 20:53 vs UTC 8/26)让两者分叉后才验 —— 扫码入库录入「商品日期」= **2026-08-25**(本地日)✅, 点击弹**三列滚轮**✅, 滚到 24 写回 `2026-08-24` ✅; 出入库统计/操作记录/盘点记录三屏渲染正常, logcat 无 error/FATAL。⚠️ **分层说明**: 设备证的是「没崩/默认是本地日/滚轮能用」, 「算得对不对」由 24 点采样测试 + 变异对照证, 两层不混。**✅ 收尾(2026-08-27)**: 这条已闭环。Owner 裁定「你自己决定」后按**改语义**处理: `city-overrides.vue` 与 `insurance-rates.vue` 两处控件改 `type="month"`(保持 `value-format="YYYY-MM-DD"`), 并配 **fail-closed 校验**(`!effectiveFrom.endsWith('-01')` 直接拒绝) —— 控件被换回 `type="date"` 或被绕过时也进不去。**PR#115** 已合。 **prod 实测(在运行中的产物里找标记, 不看 git log)**: `139:/www/wwwroot/web-admin/assets/` 下的 chunk 按 mtime 排开, 切换点干净 —— **08-26 13:06 及以前全是 `type:"date"` 且无新串; 20:15 起全部 `type:"month"` + fail-closed 串**(PR#115 于 17:16 合入, 对得上), 当前活的那一代(08-27 10:33)两个文件都含。 ⚠️ 阳性对照: 同一把 grep 搜旧串「生效起始」命中 36 个 chunk ⇒ 上面那些「无」不是 grep 自己失效造出来的。

- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811` — `blocked` — Owner: `/root` — 已有代码进入公司迁移树，但正式 audit 仍因 `trayal_0e5a259f_030` unjudgeable fail closed。计划任务 `Cretas-Liushanmen-VisionLab` 已迁到 clean exact-main 工作树并取得 LastResult=0；剩余阻塞仅为该 ROI 人工裁决。保留视觉 scope；LocateAnything hardening 与其串行，模型、人工真值、原图和生产不改。
- `LLM-PROVIDER-CLIFF-20260815` — `blocked` — Owner: `/root` — DeepSeek 接线、七槽、悬崖模拟、每日健康与复审 cron 已进入迁移树并在线运行；剩余仅 2026-09-13 aistore 续期换 key、2026-09-04/11-15 运营复审等外部动作。旧 `llm_router.py` 代码锁已释放；不得把供应商控制台动作扩大为代码改动。

- `UX-RN-RESTAURANT-PROCUREMENT-DELIVERY-20260802` — `blocked` — Owner: `/root` — 实现提交已进入迁移树，代码与 Expo Web 验收可核销；当前餐饮 RN OTA runtime 与真实设备验收无法确认。实现文件 scope 已释放；如继续，仅新建 exact-commit OTA/设备验收项。

- `SEC-CREDENTIAL-ROTATION-20260719` — `blocked` — Owner: `/root` — tracked tree、服务器副本、数据库/JWT/internal/BaoTa/OSS RAM 替换已完成；仍需各供应商控制台吊销旧 Aliyun 主账号 AK、旧 Model Studio/Zhipu/DeepSeek key 与微信长期凭证并验证失效。旧 broad 代码锁已释放；不得在本任务做历史重写。

- `ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001` — `blocked` — Owner: `/root` — 候选 DTO、置信度/reason、同厂历史匹配和 Web 展示已进入迁移树；分类建议目标测试与 Excel preview 复用未完整证明，当前无执行者。陈旧 scope 已释放；恢复时按最新 Base 重新登记。**2026-08-25 核销进度**：分类建议目标测试这一半已补齐 —— 新增 `MaterialTaxonomySuggestionContractTest`(10 条, 落在 CI 选择器内), 覆盖 HIGH/MEDIUM 分级、按 L3 去重、分类链残缺(节点缺失/父链断裂/已停用)跳过候选且不阻断录入; 每条断言都配阳性对照(`unit`/`matchedMaterialName` 照常返回), 防「整个方法挂了」被读成「候选为空」。**实测**：既有 `RawMaterialTypeCodeGenTest` 对 classification* 的断言数为 **0**(同文件对其它 suggest 字段有 8 条, 作阳性对照), 且其类名不在 CI 选择器内, push 上只编译不执行。**剩余未证明**：「Excel preview 复用」—— 实测 `suggestFields` 全仓**唯一**调用点是 `RawMaterialTypeController`, 物料 Excel 导入预览**根本没有复用**它, 即台账这半句描述的不是「未证明」而是「未实现」, 需 Owner 裁定是否要接。

- `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` — `blocked` — Owner: `/root` — **需 Owner 裁定后才能继续；上一版交接件给的方向经实测被否**。交接件写「缺 `TRANSFER` 和生产退料 —— 统一来源矩阵要补的是这两类」，照此实施是**给一个死字段加枚举值，零用户可见效果**。**2026-08-25 实测(只读)**：① `InboundType` / `material_batches.inbound_type` 是**只写字段** —— Java / web-admin / RN / Python **四侧生产读取点为 0**(阳性对照: 同法 grep `sourceDocType` 得 62 处且有真实过滤 `"PRODUCTION_BATCH".equals(batch.getSourceDocType())`; Python 侧引用 `material_batches` 105 次而 `inbound_type` **0** 次)；全仓仅 3 处读取, **全部在测试里**, 且断言的正是那 3 个写入点自己写的值(自我确认)。② 生产库 177 行 material_batches 里 **167 行(94%) `inbound_type` 为 NULL**, 含全部 53 行 `PURCHASE_RECEIVE` 与 24 行 `PRODUCTION_BATCH`; 只有 OPENING→LEGACY_IMPORT(7) 与 CUSTOMER_MATERIAL_ARRIVAL→OTHER(3) 被写过。③ 8 个批次写入点中只有 4 个设过 `inboundType`, 主路径 `createMaterialBatch` 只在 LEGACY_IMPORT 一个分支设。④ 全仓**唯一**暗示该枚举有用途的是 `WarehouseInventoryGuardService` 的一句 javadoc, 而它写的 `inboundType=SEMI_FINISHED/RAW` **在枚举里根本不存在**(实为该方法的 `inboundCategory` 字符串形参)——本轮已订正该注释并把上述结论钉在注释里。⑤ 真正被消费的来源字段是 `source_doc_type`, 但它与 `InboundType` 是**两套互不映射的词表**, 且生产在用的 `MATERIAL_REQUISITION`/`PRODUCTION_BATCH`/`CUSTOMER_MATERIAL_ARRIVAL` 三个值**都不在** `validateSourceDoc` 的 switch 里(它们由绕开该方法的写入点直接落库)。**裁定项**：(a) 给 `InboundType` 接一个真实消费方(如注释描述过的仓库准入), 还是 (b) 删掉这个死字段、来源矩阵统一收敛到 `source_doc_type` 一套词表？在裁定前**不要**扩充枚举值。

  **2026-08-25 第二轮：本项的真实内容已定位, 两个真缺口现已全部清零。** 「统一来源矩阵」的实质不是枚举值缺项, 而是**「入库必须有发起单」那道 fail-closed 守卫只在 `createMaterialBatch` 这一条路上跑**, 其余写入点直接 `new MaterialBatch()` + `repo.save()` 绕开它。全仓 11 个构造点实测: 8 个写来源, 3 个没写 —— 其中 1 个是不落库的报表占位对象(非写入点), 另 2 个是真缺口:
  - ✅ **已修 `TransferServiceImpl.createTargetInventory`**: 调入方建的批次此前 `source_doc_type`/`source_doc_id` 一个字都不写, 追不回调拨单。现写 `TRANSFER_IN` + 调拨单号。**行为中性**: 全仓按值消费 `sourceDocType` 的三处全是与 `"PRODUCTION_BATCH"` 比相等, 而它对 `null` 与 `"TRANSFER_IN"` 同为 false, 且无 JPQL 按该列过滤。配 `TransferTargetBatchSourceDocContractTest`(3 条, 含阳性对照「确认时确实建了批次」)。**变异对照**: 撤掉那两行 → 行为测试与下述闸**双层同时红**。
  - ✅ **已下线 `POST /{factoryId}/material-receipt`（Owner 2026-08-25 拍板删除）**: 它是与 `/material-batches` **并行的第二个原料入库端点**, 真落库但 `CreateMaterialReceiptRequest` 里**没有任何来源字段**, 整条路**不过** `validateSourceDoc` / 补录时效锁 / 单位维度守卫 / 仓库类型守卫, 且**批次号由用户手填**(主路径是系统生成并显式忽略手填值)。⚠️ **权限口径也不一致**: 正门要 `warehouse:read_write`(仅仓储经理/仓管员/餐饮厨师 3 个角色), 而它要 `production:read_write`(**8 个角色, 含一线 operator**) —— 即生产岗可绕过仓储职责分离凭空建原料库存; 而系统在 `ToolRbacEnforcer`(`material_batch_create`→`warehouse:read_write`) 与 `BatchDeleteConfirmTool` 注释里都明确规定建原料批次属仓储权限, **AI 工具层守住了这条线, 这个 HTTP 端点没守住**。**下线前的实测证据**: ① 批次号格式反查 —— 生产库 37 条无来源批次全部有别的解释(24 条 `MR2026*` 是 MOCK_REST 餐饮演示租户种子 / 12 条 `TRF-MT-*` 是调拨 / 1 条 `MT-2*` 是 08-16 修复前的采购), **没有一条是从这扇门进来的** ⇒ 上线至今零使用; ② 唯一调用方是 RN `screens/test/BatchOperationsTestScreen.tsx`, 而该测试屏**没挂进任何导航**(对照: 同目录 `ServerConnectivityTestScreen`/`IntentExecutionTestScreen` 确实挂在 ProfileStack) ⇒ App 里点不到。**删除范围**: Controller 端点 + `toMaterialBatch` mapper + `ProcessingService`/`Impl` 的 `createMaterialReceipt` + `CreateMaterialReceiptRequest` DTO + `ProcessingControllerCreateMaterialReceiptTest`(4 条) + RN 的 `recordMaterialReceipt`/测试屏那一步/单测, 净 -594 行。**入库统一走 `/material-batches`(71 处调用), 仓管日常不变。**⚠️ 保留了 `CreateBatchRelationRequest`/`CreateDisposalRecordRequest` 两处 javadoc 里的历史 PR 清单提及(「continues the sweep from ... PR #391」), 那是对当时做过什么的陈述, 仍然为真且非 `{@link}` 不断链。
  - 🔒 **防复发已上闸**: 新增 `MaterialBatchSourceDocContractTest`(4 条, 棘轮) —— 凡 `new MaterialBatch()` 的文件必须写 `setSourceDocType`, 否则须登记进 `KNOWN_WITHOUT_SOURCE_DOC` 并写理由; 名单只许变短, 且检查过期条目防名单烂掉; 另有阳性对照防「标记过期导致扫到 0 个而恒绿」。闸的**代理判据与两个已知盲区**(文件级粒度 / 临时对象会被误判)已写在类 javadoc 里。⚠️ 这道闸解决的正是 2026-08-16 那次的复发面: 当时 `PurchaseServiceImpl` 漏写来源, 修法是**在那一个文件里手工补一行**, 守卫本身没被接上 —— 于是 `TransferServiceImpl` 成了「下一个」。

- `BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001` — `blocked` — Owner: `/root` — V94、allocation 核心、财务 AP 未清项付款与旧采购付款只读边界已进入迁移树；四维状态、付款条款里程碑/计划/三单匹配未完整实现。CORE 子任务已归档；父任务旧广域锁释放，恢复时按最新 Base 重锁。**2026-08-25 只读核查**：AP 核心确在 main 上 —— `entity/finance/` 下有 `ArApTransaction`(带 `settledAmount`/`outstandingAmount`/`paymentStatus`/`purchaseOrderId`)、`ArApPaymentAllocation`、`PaymentRecord`、`InvoiceRecord`, 端点有 `ArApController` 与 `PayableSettlementController`(付款并核销未清项 + 历史未分配付款只读查询)。**「四维状态」疑似已由 `PayablePaymentStatus` 四值承担**(`NEEDS_RECONCILIATION` / `UNPAID` / `PARTIALLY_PAID` / `PAID`) —— 需 Owner 确认台账说的「四维」是不是就是这个; 若是, 本项描述已过期。**确实未实现**：付款条款里程碑/付款计划、三单匹配 —— 全仓 grep `threeWayMatch` / `PaymentMilestone` / `paymentSchedule` **0 命中**(阳性对照: 同法搜 `ArAp`/`应付` 有大量命中, 排除 grep 失效)。

- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001` — `blocked` — Owner: `/root` — 净需求、客户来料、ownership/idempotency 与旧直接收料 fail closed 已有；严格同批 identity/单位/日期/QC/cost 匹配及独立库存 transaction 验收不足。需求仍有效且跨模块，保留 scope，`NOT_DEPLOYED`。**2026-08-25 只读核查**：台账描述与代码一致, 未发现过期 —— `SalesOrderSuppliedMaterialRequirementService` 有 `createForOrder`/`updateForOrder`/`copyForOrder`/`getPendingReceivingTasks`/`receive`, 幂等经 `idempotencyKey` → `sourceEventKey` 落库; 直接收料的 fail closed 在 `MaterialBatchServiceImpl.validateSourceDoc`(缺 `sourceDocType` 一律 400, 各类型分别校验发起单存在性)。相关测试 `SalesOrderSuppliedMaterialRequirementReceiptTest` / `CustomerOwnedInventoryGuardsTest` / `CustomerMaterialArrivalNoticeServiceTest` 均存在但**类名不在 CI 窄选择器内**(只编译不执行), 与本批其它几项同一形态。**剩余未核销**：严格同批 identity/单位/日期/QC/cost 匹配与独立库存 transaction 验收 —— 本轮未动。⚠️ 注意与 `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` 的交叉: 生产在用的 `source_doc_type` 值里 `MATERIAL_REQUISITION`/`PRODUCTION_BATCH`/`CUSTOMER_MATERIAL_ARRIVAL` **都不在** `validateSourceDoc` 的 switch 里 —— 它们由绕开 `createMaterialBatch` 的写入点直接落库, 即「直接收料 fail closed」只覆盖走主路径的那部分。


- `ENH-F006-SUPPLIER-IMPORT-001` — `blocked` — Owner: `/root` — 供应商必填资料、Excel 预览/幂等确认、状态生命周期、采购门禁与详情信息架构尚无本轮完整 tree/test 核销证据；当前无专用 PR。需求和 scope 均保留，恢复时先核对公司 main 的现有实现，严格 `NOT_DEPLOYED`。**2026-08-25 只读核查**：**实现已在 main 上齐活**(`SupplierImportService`/`Impl`、`SupplierImportReceipt` 实体+仓储、preview/confirm/errorReport/template 四个 DTO、`SupplierController` 端点), 台账「尚无核销证据」指的是**验证**而非实现缺失。测试也存在(3 条 + 4 条), **但两个类名都不在 CI 窄选择器内 ⇒ push 上只编译不执行**, 这正是「无核销证据」的机械成因。**已修**：两个类重命名进选择器(`SupplierImportServiceImplTest`→`SupplierImportContractTest`、`CreateSupplierRequestRequiredProfileTest`→`CreateSupplierRequiredProfileContractTest`, 重命名前先本地跑过确认 7/7 全绿才搬, 避免把红的搬进 CI), 并补 4 条此前完全没有断言的**幂等出口**: ① 同键同文件重放返回既有结果且**一个供应商都不再创建**(断言 `verifyNoInteractions(supplierService)` 而非只看 createdCount —— 后者在「又建一遍又恰好同数」时同样成立); ② 同键不同文件 409(⛔ 不许静默按旧结果回放, 否则「换个文件重传」看起来成功而新供应商一个没进); ③ 并发抢键撞唯一约束 409 且在建任何供应商之前; ④ 行校验失败必须在建任何供应商之前抛 400。**变异对照**: 把摘要比对从 `equalsIgnoreCase` 改成 `equals` → `replayWithSameDigestReturnsExistingResultAndCreatesNothing` **红**(被岔进 409 分支), 其余 6 条仍绿。至此本项的「Excel 预览/幂等确认」一半有了会执行的背书; **剩余未核销**：状态生命周期、采购门禁、详情信息架构、Web 侧。⚠️ **另发现一处 schema 债(非导入缺陷)**：`suppliers` 表存在成对重复列, 且**每对只有一半是活的** —— 实测全库 18 家: `phone` 18/18、`contact_person` 18/18、`email` 10/18 有值, 而 `contact_phone` **0**、`contact_name` **0**、`contact_email` **0**、`business_license` **0/18**(全租户皆空)。导入侧写的是**活的那一半**(`supplierCode`/`contactPerson`/`phone`/`email`/`taxNumber`/`address`/`bankAccount`), 因此导入本身是对的; 但任何按实体字段名直觉行事的新代码都会写进死的那一半。⛔ 本轮据此得到一条判据: 「F006 12 家供应商资料全缺」是**错读** —— 那是量到了死列; 阳性对照(换成活列)显示 12 家电话/联系人齐全。

- `BUG-F006-R2-SKU-UNIT-001` — `blocked` — Owner: `/root/supplier_frontend` — SKU displayUnit、净含量单位、同维度 canonical 化与历史回填尚无本轮完整 tree/test 核销证据；保留 Web scope，Java 契约仍须协调者另行审计，严格 `NOT_DEPLOYED`。**2026-08-25 只读核查**：Web 侧 canonical 化**已相当成熟且真的有 CI 背书** —— `web-admin/src/utils/unitPricing.ts` 已分出「内部比较键」(`canonicalUnitCode`) 与「落库/展示」(`canonicalUnitCodeKeepingCount` + `RAW_COUNT_LABELS`/`DISTINCT_COUNT_LABELS`) 两条路, 明确保留用户手填的 件/个/只 原标签而不折成同一个字; 且 `Web Admin Gate` 跑的是 `npm test` **全量 vitest** + `vue-tsc -b`(与 Java 的窄选择器不同), 故 `UnitSelect.contract.spec.ts` / `MergePurchaseDialog.pricingUnit.contract.spec.ts` 这些契约测试是**真的会执行**的。**生产实测(F006 raw_material_types 330 行)**：同维度并存确实存在 —— `kg` 250 / `g` 3(同维度未折), 计数类 `个` 32 / `件` 1 / `只` 1(三标签并存, 属**有意保留**而非缺陷, 见上述 DISTINCT_COUNT_LABELS 的设计理由)。**Java 侧净含量契约已审计并补齐(2026-08-25)**：`ProductTypeServiceImpl.applyNetContentContract` 有一整套真实约束, 而全仓 `src/test` grep `netContent` 此前**只命中 `BomRecipeDraftLifecycleJpaTest`**(那是 BomRecipe 的字段) ⇒ 这条契约**一条断言都没有**。新增 `ProductTypeNetContentContractTest`(13 条, 落在 CI 选择器内), 覆盖: ① **核心承诺「容量型绝不伪装为克重」** —— `ml`/`L` 存净含量但 `gramsPerUnit` 保持 `null`(若折成克, 下游按克重结算的成本/报工会拿到一个凭空造出来的重量), 并配阳性对照 `kg → ×1000` 证明这条路真的会写 `gramsPerUnit`; ② 单位归一(克→g / 公斤·千克→kg / 毫升→ml / 升→**L 大写**, 大小写空格不敏感, 值域外 400)—— `L` 是大写这点特意钉死, 免得有人「顺手统一成小写」而下游按 `"l"` 精确比对漏掉; ③ 成对必填与非正数 400; ④ 旧 `gramsPerUnit` 兼容桥结构化为 `g`, 且结构化字段在场时覆盖旧字段(防「同时传 500ml 和 gramsPerUnit=500」绕过核心承诺)。**变异对照**: 把 `toGrams` 的 `case "ml","L" -> null` 改成返回原值 → 正好那 3 条守容量型的红, 其余 10 条(含阳性对照)仍绿。**剩余未核销**：历史回填。

- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001` — `blocked` — Owner: `/root/workprocess_governance` — canonical/mergedInto、未来候选排除、既有 Workflow/SKU/计划/报工引用不迁移，以及排序/单位从主数据移除的完整 JPA/Web 验收尚未本轮核销；保留 scope，生产重复组零治理。**2026-08-29 窄范围释放**：Owner/agent/task/branch/worktree/commit/open PR 均不可达，原 Base `0f2b8329c` 也是无关老提交；仅将 `WorkProcessTaskServiceImpl` 的 legacy DTO 输出单位投影及对应目标测试释放给 `BUG-LSM-LEGACY-WORKPROCESS-OUTPUT-UNIT-20260829`，其余广义治理 scope 继续保留。**2026-08-25 核销进度**：Java 侧行为契约已补齐 —— 新增 `WorkProcessGovernanceContractTest`(11 条, 落在 CI 选择器内), 首要一条钉住端点摘要那句唯一实质承诺「仅影响未来选择，**不迁移已有引用**」(断言治理全程 `verifyNoInteractions` 三个引用仓储: SKU 关联 / 工作流 / 报工任务); 另覆盖 MERGE 与 DEACTIVATE_OTHERS 的落库差异(互为阴性对照)、幂等重放(纯读, 不再写库)、幂等键冲突 409、跨重复组 409、MERGE 关键配置不一致 409、跨租户锁不足 404、mode 缺失 400、过滤后为空不写空审计。**变异对照(两条, 均确认能红)**：① 在治理循环里插一次引用仓储调用 → `mergeDoesNotTouchReferenceRepositories` 红(`No interactions wanted here`), 其余 10 条仍绿; ② 去掉 `mode == MERGE` 守卫 → `deactivateOthersDoesNotSetMergePointer` 红。每次变异前后 `touch` 源文件, 并先 grep 确认变异真的落进文件再看红绿。**实测定性(只读, 生产库)**：迁移已到位(`merged_into_id` 与 `work_process_governance_audits` 表在 prod 均存在), 端点已接线(controller→service), 但审计表**全库 0 行** ⇒ 这条路径**从上线至今一次都没被调用过**, 「生产重复组零治理」属实。F006 190 条存活工序里 `merged_into_id` 非空 **0** 条; 重名组共 **3 组 8 行**: 焯水×4 / 熟制(卤制)×2 / 修油×2, 且 8 行的 SKU 关联数**全为 0**。⚠️ **但先别急着治理**: 这 8 行的 id 前缀是 `WP-F006-NT-*` / `WP-F006-ZS-*` / `WP-F006-ZZB-*`, 形态上像**按产线/车间拆的同名工序**而非重复录入 —— 合并它们可能在语义上是错的, 需 Owner 先判定这三组到底是不是「重复」。**剩余未核销**：Web 侧工序治理界面、排序/单位从主数据移除、Flyway/JPA 层验收。

- `FEATURE-F006-R2-BOM-SUBSTITUTE-001` — `queued` — Owner: `/root` — Java CORE 已归档，但父任务的 Web、计划、领料、报工、成本和追溯快照尚未证明完成；继续由统一 Workflow/BOM 任务串行吸收，保留父任务 scope，严格 `NOT_DEPLOYED`。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE` — `in-progress` — Owner: `claude-code/f006-remaining-closure` — Base SHA `fee2509fa667344f7f8ba7a7b91a5fb2a32ae2a2`，worktree `cretas-f006-bom-gate` / branch `codex/f006-workflow-bom-gate-20260825`。**本批已做**：删掉 `applyPlanUnitAuthority` 里一道**到不了的** BOM 准入分支（`mode == WORKFLOW && activeRecipe.isEmpty()` 位于 `mode == WORKFLOW` 早退之后，条件恒为 false，`ACTIVE_BOM_REQUIRED` 从写下来那天起一次都没执行过）；WORKFLOW 模式的 BOM 准入由 `resolvePinnedBomAuthority` 的 `ACTIVE_BOM_FAMILY_REQUIRED` 唯一负责（活闸，已由新增闸钉住仍在）。新增 `PlanBomGateHasNoUnreachableBranchContractTest`（4 条，含阴性对照：`ProductConfigurationReadinessService` 那处**活的**同名码不许被顺手删掉；含阳性对照防「剥注释剥过头 ⇒ 恒真式」）。**实测定性（只读，生产库）**：① F006 共 13 个 ACTIVE `bom_recipes`，其中在 `bom_versions` 里有对应版本快照的是 **0 个**；`bom_versions` 全库仅 9 行、最近写入停在 2026-08-02 ⇒ **画布保存/激活 BOM 那条路根本不写 `bom_versions`，只有 ECN 手工流程（`BomVersionService`）才写**；而成本归集 `resolvePinnedPackaging` 读的正是 `bom_versions.snapshot_json` ⇒ 对画布建的 BOM 永远读不到（形态 D：同一个东西两份，读的是不再维护的那份）。② 但**退回实际耗用也救不了**：那 7 个批次 `material_consumptions` 里包材行数 **0**；`bom_recipe_items` 里 PACKAGING 的 `item_cost` 5 行全是 `0`（RAW 16 行全是 `null`）。**⚠️ 2026-08-27 更新：那批 `0` 已由 `V20260826_04` 改回 NULL**（全部 8 行的 `unit_price` 都是 NULL，判为「没填」而非「确认为零」），现全表 `item_cost = 0` 的行数为 **0**。⇒ 包材成本确实不可知，成本侧诚实阻断**是正确行为**，不是缺陷。**未决产品问题**：~~(a) 「包材可选 ⇒ 成本永远算不出」是否可接受？~~ **❌ 2026-08-27 撤销此问 —— 它的前提是错的归因。** 全量重测 `bom_recipe_items`（未软删）：
  ｜类别｜明细｜缺单价｜缺用量｜两样齐全｜
  ｜RAW 原料｜44｜22｜**42**｜**2**｜
  ｜PACKAGING 包材｜11｜8｜**0**｜3｜
  ｜AUXILIARY 辅料｜3｜0｜0｜3｜
  **包材一条都不缺用量，而原料 44 条里 42 条缺。** 三类里包材填得反而不是最差的（齐全率 辅料 3/3 ＞ 包材 3/11 ＞ 原料 2/44）。⇒ 真正卡住成本的是**原料没填参考用量**，不是「包材可选」。8-25 那个「F006 那 7 个批次包材成本不可知」的观察本身没错，但把它归因成「包材可选」是错的 —— 当时只看了那 7 个批次，没有全量。⚠️ 顺带：「标准单价」在 `raw_material_types` 里**两个工厂一条都没填过**（`unit_price` 全 NULL），只有 5 种物料有系统自算的 `moving_avg_price`。~~(b) `bom_recipe_items.item_cost = 0` 到底是「确认为零」还是「没填」？~~ **✅ 2026-08-27 已裁定并执行**：判为「**没填**」。决定性证据是**全部 8 行零成本的 `unit_price` 都是 NULL**（8/8），而全表 `unit_price = 0` 的行数是 **0** —— 本系统从不合法地记录零单价；物料是真空袋/外箱/标签，现实中不可能零成本。已由 **`V20260826_04`** 改回 NULL 并部署验证（`cost_zero` 全表归零，误伤面 0 行）。⚠️ **本句原文「现在写的是 0 而不是 null」已不成立**，勿再据此判断。⚠️ 另需注意：那 8 行当时**并未**使任何配方成本偏低（相关配方本就被别的缺口整体阻断），修它是**防将来**。(c) 成本归集该不该从 `bom_versions` 改读 `bom_recipes`+`bom_recipe_items`，还是该让画布激活时补写 `bom_versions` 快照？**⚠️ 2026-08-27 补充（半个答案已经在代码里了）**：`resolvePinnedPackaging` 里已有一条`pinnedBomSnapshotFromRecipe` 兜底 —— `bom_versions` 查不到时从 `bom_recipes` 直接取，且只在 `recipe.version == plan.selectedBomVersion` 时才用（避免拿新版本冒充旧版本）。🔴 但它**只救回了 snapshot、没救回 `version` 对象**，导致兜底**成功时** `version.getVersionNumber()` NPE ⇒ `/cost-breakdown` 500，界面常驻「系统处理异常」。已由 **PR #149** 修复（价格来源区分 `PINNED_BOM_ITEM_COST` 与 `RECIPE_FALLBACK_ITEM_COST`，不混为一谈）。⇒ 本项待裁定的其实只剩「要不要让画布激活时**补写** `bom_versions` 快照」，读侧已有可用兜底。停止条件：不得改写历史快照或生产数据；不得把「没填价」按 `0` 计入 COGS。

- `BUG-F006-PRODUCTION-COST-CLOSURE-001` — `in-progress` — Owner: `claude-code/f006-remaining-closure` — Base SHA `7d51fe22597e80ee9cd572575f58539c249fb170`，worktree `cretas-f006-cost-labor-split` / branch `codex/f006-cost-labor-split-20260825`。**已合入并部署三批**（PR #58 / #59 / #60）：① 算不出成本时说出真实原因；② 「设备/其他不强制填写」裁定补到 `costComplete`；③ 人工「明细不全」与「金额未知」拆开（`LABOR_TIME_DETAIL_MISSING` 不阻断 / `LABOR_COST_UNKNOWN` 照旧阻断）。生产核验：active `10010`/`cretas-backend`，PID `2237799` 启动 `14:53:57`，JAR mtime `14:53:34`，运行制品含 `LABOR_TIME_DETAIL_MISSING`×2 / `LABOR_COST_UNKNOWN`×1。**部署后只读探针（23 个 F006 生产批次，真跑接口非推断）**：`totalCost` 非空由 `2/23` 提升到 `16/23`；交接件点名的 `PB-PLAN-1787622818112-B3898275-15036` 已算出 `totalCost=93.12` / `perBoxCost=9.31`（= 原料 40.32 + 人工 51.57 + 包材 1.23，逐项对得上）；`LABOR_RATE_OR_TIME` 已从阻断码分布中消失；判据「无阻断项 ⟺ totalCost 非空」不一致 `0` 条；`calculationStatus` 全部仍为 `PARTIAL`（不阻断 ≠ 藏起来）。**剩余 7/23 条阻断码全部是 `PINNED_BOM_SNAPSHOT_MISSING`，且已定性为「成本侧行为正确」**：实测 F006 共 8 个 `bom_versions`（全部有 snapshot），而这些计划锁定的 4 个 `bom_recipe_id` 在 `bom_versions` 里**一个版本都没有** ⇒ 包材成本确实不可知，诚实阻断是对的。真正的修复属于 `F006-R2-WORKFLOW-FIRST-BOM-GATE`：不该让「没有激活 BOM 版本」的计划建单。**另一遗留（形态 D 两条轨）**：`PUT /{planId}/costs` 写 `production_plans.actual_equipment_cost`（喂分析/AI 报表），而成本归集读 `production_batches.equipment_cost`，用户补录进不了单位成本。停止条件：`totalCost` 的 COGS 口径不得松动（缺**单价**类照旧阻断），生产实体只读、零业务写入。

- `QA-WAREHOUSE-STORAGE-UI-VERIFY-20260825` — `review` — Owner: `claude-code/warehouse-storage-ui-verify` — 第一批已合入并部署 (PR #87 / `11d395f783`)：菜单入口缺失、弹窗 `is-closing` 卡死、成品移位位置存在性 fail closed、解绑与四级删除、浮点误差与卡片溢出、AI 识别改预览确认。部署后已在生产逐条复验（同一请求 200→400、菜单已可点、浮点卡片 5→0、溢出 5→0、删除端到端并服务端回读）。**第二批（PR 待开，worktree `cretas-wh-approval-closeout` / branch `codex/claude-warehouse-approval-closeout-20260826`）** —— Owner 追加要求的三件事：
  ① **App 端够不着仓库地图**：`WHLocationManageScreen` 原本只挂在被 `SIMPLIFIED_WAREHOUSE_NAV` 整栈隐藏的库存 Tab 下。⛔ 没有翻那个开关（会把 18 个库存查询屏一起放出来，违背 owner 裁定），改为单独挂进首页栈 + 首页加一张「仓库地图」卡；同时补注册 `WHTempMonitor`（该屏写死 `navigate("WHTempMonitor")` 而首页栈原名 `TempMonitor`，不补即死路），并加闸逐个核对它跳出去的路由。
  ② **走通数量审批**：发布了全生产第一个 `INVENTORY_ADJUSTMENT_APPROVAL`（厂长单级），仓管发起临时盘点（盘盈+0.1/盘亏-0.1）逐条量：录入后库存零变动（139 批逐条比对）→ 提交进 OA 不再自动通过 → **仓管自审 403 `OA_TASK_FORBIDDEN`（服务端硬拒，不是 UI 藏）** → 厂长待办可见而仓管待办为空 → 审批前库存仍未动 → 通过后发起人/审批人分别记为仓管/厂长 → 🔴 **应用差异 500「追踪码 7E651E80」过账整体回滚**。根因：凭证号发号器原生 SQL 里 `CONCAT('V-', ?2, '-')` 无类型上下文，PG 在 Parse 阶段推不出类型；pgjdbc `prepareThreshold=5` 使其在第 5 次升级为服务端 prepared statement 后每次必炸（解释了「库里 17 张凭证却今天必失败」）。已在 prod 库用 `PREPARE` 双向验证（现状式报错 / CAST 式返回 17，而真实 max 就是 `V-2026-0017`，未删仅 13 ⇒ 没把软删占号的旧缺陷带回去）。⚠️ 单测库是 H2，删掉 CAST 它照样绿 ⇒ 防回退靠源码闸，JPA 那条只证明「加 CAST 后 H2 仍跑得动」，测试注释写清了它不证明什么。另修：厂长待办显示 `INVENTORY_ADJUSTMENT <uuid>`、点详情「暂未接入单据详情页」、确认框标题写着「通过前请确认单据信息」却只有 UUID ⇒ 后端补结构化下发（盘点单号+仓库，批量取不引 1+N），前端接详情并让 `?stocktakeId` 真被消费。
  ③ **位置脏值清洗**：全后端 4 处把来源描述硬编码进 `storage_location`（含英文调试串），F006 16/18 条成品因此位置字段不是位置 ⇒ 位置筛选与「某位置有几批」恒为 0。四处全改为不写（留 null=未分配），加源码闸；存量事务内清洗 `UPDATE 16`，其他工厂零命中、数量字段零变更。
  **验证**：Java 23 条 / Vitest 153 条 / Jest 26 条 / `vue-tsc` EXIT=0 / RN `tsc` EXIT=0；四处修复各做变异对照并确认能变红。
  **未决**：`应用差异` 的修复尚未部署 ⇒ **F006 那张 `ST-202608-C20566F3` 仍停在「已审批」未过账**，等本批上线后再点一次「应用差异」收尾。

### 📌 2026-09-02 03:30 增量 · organizer 现地址 `liushanmen-organizer-handoff-fa5fb5-0c`（进程重启后改名；上面快照里的 `-bb` 已失效）

**新合 main（未部署，随 38 一起发）**：#479 分仓库存「账面/可用」并排 + 空态区分（复用 `getUnsettledConsumptionByBatchIds`，⛔ 没另写聚合）/ #480 缺陷 16 规格弹窗合并 + phase-1 删 `minOrderQuantity`/`leadTimeDays`（`active` 三层保留，`停用` 入口搬进合并弹窗 `:343/:347`，prod 复测两列非空行 = 0）。**待部署积压：#471–#475 #479 #480。**

**撤回 缺陷 37**（f006 查证 + owner「这不是讨论过吗」）：它描述的是 2026-08-18 那版；#460 已把「计划投入原料量」做成建单弹窗人工输入并于 23:51 部署。owner 03:1x 在 `MR20260902-0004` 填 3 kg **实测 7/7 预算值全中**（洋葱泥 0.251、盐 0.017、纸箱 0.069；预期值在点击前写下）⇒ **#460 核销**。此前几张单显示「按实际领用」只是没人填那个框。

**新登记 → 已派**：
- `FIX-LSM-AUX-DERIVE-AT-CONFIRM-20260902`（executor `ada380f67bd9c0e29`，sonnet）— owner 裁定「输入完原料后辅料自动实时计算、改了重算」= 他说的第 ② 条路。⚠️ 这条 23:xx 就裁了，排在打印按钮之后被进程重启冲掉，**是我漏派**。判据：RAW 1+1+1 ⇒ 洋葱泥 `0.2514`；改 5 ⇒ `0.419`（⛔ 不变=算了一次就缓存）；⛔ 不删 #460 建单输入；🔴 重算不得静默冲掉手改行；RAW 合计 0 时辅料不得算出假 `0`（与 #469「显式 0 = 不领」冲突）。**缺陷 43**（调拨确认框 8 行 ×0 淹掉 3 行真要搬的 ⇒ 折叠成「另有 N 项本次不领」，⛔ 不删）同一批。第 ① 条路（按成品计划反推）**不做**：需要 RAW 标准用量，与 owner 「RAW 每批现场定」相悖。
- `FIX-LSM-SINGLE-REQUISITION-PRINT-20260902`（executor `a3a7a38eaf271a120`，sonnet）— **缺陷 44**：#469 接上的打印按钮打出**空纸**（UUID 当单号、`(产品)`、`0.00 kg`、0 行）。`PrintController.buildMaterialRequisitionPayload` 全程不查库，`items = List.of()` 写死；同文件汇总版 `buildConsolidatedMaterialRequisitionPayload` 是接对的（形态 D）。判据升到第三级：**PDF 正文含这张单的 `MR…` 号 + ≥1 行真实物料名**；阴性对照换一张单号必须不同；#469 语义：显式 0 打 `0`、留空打「按实际领用」。

**在飞（更新）**：38（对抗终审打回一次：`:57` 那个 conjunct 零阴性覆盖，缺「AUX + CUSTOMER_OWNED + 别的客户 ⇒ 仍拒」；RAW 守卫本身没被拆；执行者在补，复审通过即合并部署）/ 报工屏 32·E·D·F `a6e3f5ea29ccbbf65` / 33 单位根修 `a28ab15f33a709568` / 生产菜单 19→9 `aafe529113d055dea` / 发货 41·39·行 ID `a73d78733c2ab7a81` / 上两条新派。**#443 停车**（未 rebase 落后 37、新流程 `stepFlow` null、作者已失联）。

**对照实验（f006，03:2x）**：A 线 `MR20260902-0003`（`CUSTOMER_SUPPLIED`）填 0+确认+调拨 ⇒ ❌「尚缺 4.000」；B 线 `MR20260901-0001`（`NULL`）**完全相同操作** ⇒ ✅ `PICKING → TRANSFERRED`，3 个 `-WKS-` 批次各 1 kg。**唯一变量 = `material_supply_mode`** ⇒ 38 范围钉死在客供料分支，brief 不扩面；**工厂自产线（采购→领料→报工→结单→发货）已全通 = 演示兜底**，不用另开备份线。#469 两象限核销（填 0 放行 / 留空被拦）。

**方法学（本段两条，均由 f006 自纠/指出）**：
1. 🔴 「必发生」的量**要从被测代码路径推，⛔ 不能从历史相似数据推** —— B 线拿 `TRF-MT-*` 批次数当必发生量读到「仍是 5」差点判失败，真相是领料现在走 `-WKS-`/`MATERIAL_REQUISITION`（7-03 双扣防呆后不再产 transfer），`TRF-MT-*` 是 8-30 旧路径的产物。上一次同形是拿 `finished_goods_adjustment_log` 当发货对照。
2. 🔴 打印类判据要走到第三级：**「按钮在」→「文件下得来」→「纸上是这张单的数据」**。我给 #469 定的「真下载到文件」停在第二级，文件真下了、PDF 真渲染了、纸上全是占位符。与第 1 条同形：挑了一个看起来相关但不区分的读数。

### 📌 2026-09-02 04:25 增量 · 部署 9/10 落地, 38 上线, 合并 55

**部署（判据：运行制品里的排他标记 + 假串 0 + 进程启动时间 > jar mtime）**
- **9**（`c49fce15ed`，#471–#475 #479 #480 #482）：Java ✅ 03:59:38 blue，`WarehouseInventoryController.getUnsettledConsumptionByBatchIds` 0→1；**web 被 01:21 遗留的 `/tmp/cretas-web-admin-deploy.lock` 拒**（PID 早不在）⇒ `DEPLOY_EXIT=1`、`RELEASE_FINAL_STATUS` 0 次。
- **10**（`4647003404`，+#478 缺陷 38）：✅ 04:08:18 green(10020)，`ProductionInventoryOwnershipGuard.isCompanyStockEligibleCategory` 0→1；web 复用 9 的可信 dist（03:55 构建），原子交换 04:10:01，链 `index.html → index-DGnGihV5 → index-DTIXTmZq → index-mA8SF2Fu` 含「收货量 − 已用 − 预留」。生产入口 `admin…/api/mobile/health`=200。
- **11**（`40fecfa067`，#483 #484 #485 #486 #488）：第一次被 **`/tmp/cretas-release.lock`（部署 10 自己 04:03 留下、进程已退）拒**，摘锁重发 04:2x，在跑。
- 🔴 **部署脚本缺陷（登记）**：两把 `/tmp` 锁都**不校验持有者存活**，一晚两次被陈旧锁拒。判「另一个 deploy 在跑」先 `kill -0 <pid>`。
- ⚠️ 观察项：green 期间 47 上**没有进程听 10010**（只 10020），直连 000 是常态；前 8 次同形，走 139 反代不受影响。未深追。

**合并（55）**：#478 38（终审通过：`:57` 两侧阴性对照 + 真入口无 mockStatic）/ #482 33 单位根修（`UNIT_ALIASES` 目标改成后端 normalize 不动点，4 张兄弟别名表登记未动）/ #485 31 订正 / #483 辅料实时推算（只 AUXILIARY；PACKAGING 无 `dosagePerKgG`、按计划产出量走原路 —— 执行者纠正了我的 brief）+43 折叠 / #484 41「不得超过」+39 三步 + 行 ID / #486 报工屏 32·E·D·F（成本 null 透传，前端 `costText()` 早就渲染「成本未知」）/ #488 44 打印读真数据（`getById` 租户内）。
**退回**：#487 生产菜单 21→11（不是 19→9，执行者量了）——`dispatcher` 丢「撤回审批」（hub 沿用 roles[] 门 ⇒ 改成 tab 级门禁、hub 可见性取并集）+ `echarts-wordcloud` 改 fire-and-forget 动态 import 有首渲染竞态 ⇒ 让它改回静态 import + 测试 mock。

**核销**：#460（owner 实测 7/7）、#469 两象限、37 撤回。**我的错**：#477 是我 03:10 合的（压缩前），之后拿已 MERGED 的 PR 轮询并写「等你推订正版」—— 上下文压缩丢了合并记录；订正走 #485。

**新登记（演示后）**：**46** `/warehouse/material-types` 菜单按 `module:'warehouse'` 放行、页面写权限 `canWrite('production')` ⇒ 仓管看得见点不开（我倾向改页面权限，owner 定）；**47**（owner）领料单把「出现」与「扣库存」绑死，缺「出现 + 不扣库存（现场取用：冰水/水/蒸汽/自制高汤）」一格 —— 两处都改（`appendSeasoningItems` + 报工 `planNative`），⛔ 不复用 `count_in_seasoning`（成本口径）；它解释了今晚撞 38 的成因。**主料配比走 (a)**（进 `nodesJson`、INPUT port 存归一化份额、改配比=发新修订，一天）——经 f006 转述，派前跟 owner 当面确认。**在途审批实例 2 条**（`851d0845…` + `ee9c2f3d…`），交接件写 1 是错的。

**对照矩阵（f006）**：{客供/公司} × {领料/报工} 四格三通，只坏「客供料 + 领工厂辅料」一格 ⇒ 工厂自产线整条可演。

### 📌 2026-09-02 05:05 增量 · A 线十道全通, owner 四条裁定, 48 阻断结单

**部署 11**（`40fecfa067`，#483 #484 #485 #486 #488）✅ 04:28:23 blue；`PrintController.getRequisitionNo` 0→1、38 标记仍 1、假串 0；web `index-C4dq8W0E → index-BVZmQlvQ → list-Bpdsj3u1` 含「按原料自动计算」。第一次被 `/tmp/cretas-release.lock`（部署 10 自己留的）拒，摘锁重发。**合并 57**（+#487 生产菜单 21→11，退回补过 tab 级门禁 + wordcloud 回静态 import；**未部署**）。

**A 线 ⑤→⑩ 全通（f006 04:19–04:21，owner 亲手）**：⑤ 92→91 … ⑩ 装箱 88 SUBMITTED；**38 闭环**：`material_consumptions 3737 MT-20260902-0278-WKS-… 3.542920 SEASONING` 与拦截时报的「需 3.54292」一致；⑧ 两个产出（88 + 回料 1）**只扣一次**（一条 89，不是 178）；**33 生产验收**：`PO-20260902-0002` 50 个 → 主仓 → 领料 10 个全程「个」；结单前 `CMR-20260831-753973766C24` 5900 / used 0（预扣不真扣）。⚠️ 33 第一次仍 422 是**浏览器缓存**（今晚第二次，34 同形）⇒ **验前端改动第一句先硬刷新；「服务器上有标记 ≠ 浏览器在跑它」**。

**🔴 缺陷 48（阻断，结单是演示最后一步）**：结单客供料闸**方向反了** —— 把「SO 客供总量」当「本批必须用完」（一张 SO 两种客供料 8900kg，本批 BOM 只含其一、用 100kg ⇒ 报「1399上脑 要求 5900 已报 100 / 50CL 要求 3000 已报 0」）。⛔ 照它补会把客户 5900kg 一次扣光，f006 叫停了 owner。**已派 🔒**（`a85f90135e94e680f`）：新判据「本批用的每样客供料 ∈ SO 清单 且 跨 SO 累计 ≤ 已收」，清单里没用/部分用 ⇒ 不说话；阴性对照：清单外的料仍拦、累计超收仍拦；同 brief 带结单弹窗 UUID 泄露 + 库存参考过期诊断。结单停在原地，讲稿「结单真扣」段改用 8-29/8-30 历史数据。
**缺陷 49**：同一需求量三处三个数（tooltip 0.0694 漏乘计划量 / 结单 6.94 ✅ / 配料单 13.88 = 2×）。tooltip 半并进 31 执行者（`aa3b35b873bab5177`），打印半给 44 执行者（`a3a7a38eaf271a120`，硬约束 7 重开分支）；两边钉「⛔ 不写第三个公式」。⚠️ 31 报缺陷时引用的原文数字本身就是错的 ——「有没有报」和「报得对不对」是两个判据。

**owner 四条裁定（05:00，当面）→ 全部已派**：
- **46 = 改页面写权限**（`ab9f7f449a170a3c3`，先查后端闸再改，避免变「点了 403」）
- **47 = 「自备料」**（owner：「它没有所谓的库存一说就是自备料」）：标在物料档案 `raw_material_types.self_supplied`（新迁移，下一序号），领料单出现不扣不拦 + 报工 `planNative` 不查不拦，⛔ 不复用 `count_in_seasoning`。🔒（`a3d5a2483b00d63a4`）。冰水配置今晚仍不手改。
- **主料配比 (a) = 对**：进 `nodesJson`，INPUT port 存归一化份额，改配比=发新修订，一天。**排在 47 合完之后**（同一个领料服务文件）。brief 要带配料单三条：配比 + 单锅产能（产品档案未配置）+ 保留「请先设置『单锅产能』」诚实提示。
- **40/42 出库照抄入库 = 对**：建发货单自动生成整单子发运（⛔ 不自动确认）+ 批次分配默认 FIFO 可改（续用发货执行者 `a73d78733c2ab7a81`，硬约束 7 重开分支）。

**在飞 7**：#487 已合待部署 / 31+49t / 46 / 47 🔒 / 48 🔒 / 40-42 / 49p。**下一次部署**：任一到达即发。

### 📌 2026-09-02 05:20 增量 · 部署 12/13/14, 合并 59, 主料配比改由工艺 chat 交付

⚠️ 时钟订正：上一段标「05:05」实为 04:4x（我按估计写的，下面全部用日志/系统时钟）。

**部署**：**12** 04:44 web-only（#487 生产菜单，`index-CDmtbKC4 → index-Cr-oYtLd` 含 `/production/material-flow`）✅ / **13** 05:13:59 green（#491，`RawMaterialTypeController` `warehouse:read_write` 0→1；web 页 chunk `list-C1AJznBq` `canWrite("warehouse")`=1、`("production")`=0）✅ / **14** 05:2x 在跑（#492，`SalesServiceImpl.autoPrefillFifoAllocations` 0→2）。

**合并 59**：#491 **缺陷 46**（owner 裁「改页面写权限」）：前端 `canWrite('warehouse')` + 后端 5 个端点（原料类型 CUD + 包装层级 PUT/DELETE）加 `warehouse:read_write`（否则前端改了就是 403）；阴性对照钉 `markByproduct` 仍只认 production。⚠️ 两条给 owner 的副作用：① **`warehouse_worker` 与 `warehouse_manager` 一起获得物料主数据写权限**（模块级权限分不开两者，若只想给经理要改成角色门）；② **推翻了 2026-08-22 的相反决定**（当时注释：「原料类型是主数据，仓库工人不该增删改」）。#492 **缺陷 42**：建子发运时后端自动 FIFO 预填（复用 `autoAllocateFifo`，单一实现点不会双触发；库存不足 `BusinessException` 捕获→warn→转手工，⛔ 不让建单失败）；**缺陷 40 早在 8-24 (`96175083beb`) 就存在**（FULL 模式自动整单子发运，不自动确认，二次建单无唯一性陷阱），执行者核了没重造 —— 走查时登记的 40 是误报。

**主料配比 (a) 改由工艺 chat `local-workflow-prototype-uat-1ddf54-3e` 交付**（它已在本机做完：INPUT 端口 `inputShareRatio` → `workflow_task_ports.input_share_ratio` → 领料单 RAW 行「按配比 30%」+ 确认领料填一个自动跳其余；LOCALUAT 实测 2082=300 ⇒ 1399 300 / 和牛50 400；在途计划钉旧修订不受影响）。**我不另派。** 排期：🔴 **它的迁移 `V20261101_23` 撞号**（已被 #464 用掉并上 prod）→ 改 `_25`（47 用 `_24`）；等 **47 合入** → 它 rebase（复用 #483 的 `manuallyTouched`，顺序「RAW 份额推算 → 触发辅料推算」，#483 的 0.2514/0.419 与 #469 语义不能红，加「手改行不被份额覆盖」阴性）→ **owner 在它 session 说「开 PR」**（它那条线的既定门禁，转述不算）→ 🔒 终审 → 合 → 部署 → **人工**：LIUSHANMEN 工艺 190/192 第 1 道三个 INPUT 填份额重发布（已开计划不变）。**独立登记**：路①「按成品量反推领料」需标准出成率，BOM RAW `yield_rate` 全 100 默认、真实出成率报工时人填。

**方法学（本段三条）**：① **迁移号是序号不是日期，并行线合前必须重数一次 max，⛔ 不能各自数各自的** —— 同号不同文件 git/编译/测试全静默，到启动才炸。② **说「X 还没发生」之前先跑一条命令让它自己说**（f006 今晚第四次「读数 vs 假设」：`tail` 退出码 / `TRF-MT-*` / 回料表 / 「#485 还没合」——最后这次压根没读）。③ 验前端改动第一句先硬刷新（34、33 两次被浏览器缓存骗）。

**在飞 4**：31+49t（`aa3b35b873bab5177`）/ 47 🔒（`a3d5a2483b00d63a4`）/ 48 🔒（`a85f90135e94e680f`）/ 49p（`a3a7a38eaf271a120`）。owner 04:5x 睡，48 落地后结单留到早上他在场。

### 📌 2026-09-02 05:40 增量 · 部署 15, 合并 62, 49 订正为「1 真 + 2 误报」, 48 终审卡单位口径

**部署 14** 05:21:41 blue（#492，`SalesServiceImpl.autoPrefillFifoAllocations` 0→1）✅ / **15** 05:35:56 green（#494 #495，`ProductionPlanServiceImpl.scanZeroStockNonRawMaterials` 0→1）✅。生产 = `6da9b7ec63`，无积压。

**合并 62**：#494 **缺陷 49（唯一真的那一处）**：根因不是打印算错 —— 同一计划两张领料单（`MR20260902-0003` / `-0005`，第二张是 f006 让 owner 建来领 L1纸箱 的）各自带整单包材需求 6.94，`aggregateMaterialRequirementRows`（配料单/生产工单/汇总领料单共用）跨单据相加成 13.88；修成 RAW/PACKAGING/SEMI 取 max、AUXILIARY 保留 sum（Sp12T8 既有断言）。#495 **缺陷 31**：根因比「漏报零库存」深 —— `scanMaterialAdvisory` **根本不读 `bom_seasoning_items`**（7 个辅料全在那张表，`bom_recipe_items` 只有 3 RAW + L1纸箱），与 8-18 seasoning 白名单缺口同形；新增「库存为 0（无论投料多少都会拦）」一组，复用 `resolveAvailableRawStock` 口径。
**🔴 49 订正**：tooltip 的 0.0694 是**熟肉饼 `PLAN-…-B141A9D8` 计划量 1.00** 的正确值，结单弹窗 6.94 是**美式汉堡饼 `…-34F8CD82` 计划量 100** —— 两张计划被并排比了（f006 自认形态 A）。49 = **1 真（打印加倍，已修）+ 2 误报**；执行者加了 100→6.94 / 50→3.47 缩放钉子。落盘件零改动。判据补一条：**跨界面比同一个量，先把「哪张单」写在读数旁边**。

**48（#496）终审：不通过，但方向与主体过**。过：`planShare`（本批必须用完 SO 全量的产生器）连根拔掉；`sourceOrderItemId==null` 时白名单按整张 SO（没反向收紧成按产品）；撤销结单 `ReportReversalServiceImpl:1595` 逐行软删 ⇒ `deletedAtIsNull` 排除；本计划以 `excludePlanId` 排除；`new ProductionSettlementConsumption()` 全仓唯一写点 `:5788`；`sourceType` 字面量两侧一致；旧「自动补行」已删且 derived+manual 全集再过一次 `evaluateAgainstLedger`。**卡**：① 新聚合只按 `materialTypeId`、一处不看 `unit` ⇒ kg 与 个/只 直接相加（修法：单位不一致就不比、按硬约束 4 当没量到，⛔ 不要按 (type,unit) 建 key —— 会造 `received=0` 桶）→ 已退执行者；② `receivedQuantity` 若为 0 明早换句话再拦 → **我 prod 实测已排除**：LIUSHANMEN 5 条客供需求 `received_quantity` 全填（演示 SO 1399上脑 5900/5900、50CL 3000/3000）。⚠️ 既有范围：闸只对 `isWorkflowPlan && CUSTOMER_SUPPLIED` 生效，非 workflow 来料计划不过闸，未改。
**48 执行者顺手诊断（另派 `a79b8504de3165b5e`）**：结单弹窗 UUID 回退在 `plans/list.vue:5483`（手动加的客供批次结单前 `batchNumber` 空）；「可用 0」不是缓存 —— `rawWarehousePicker.pickRawWarehouse()` 只选一个仓，主仓 40 + 生产仓 10 读成 0。

**在飞 3**：#496 单位修 → 复审 → 合 → 部署 → 通知 f006（owner 早上在场走结单）/ 47 🔒（`a3d5a2483b00d63a4`）/ 结单弹窗两处（`a79b8504de3165b5e`）。47 合后通知工艺 chat rebase 配比。

### 📌 2026-09-02 06:25 增量 · 48 上线(部署 17), 47 终审通过待 CI, 合并 65

**部署 16** 06:01 web-only（#499 结单弹窗 UUID→`批次号 · 物料名` + 库存参考按 RAW/LOGISTICS/WORKSHOP 三类仓合计显示分布，镜像 `ensureMaterialBatchAllowedForSettlement` :6022；`list-D8pxQbDO` 含「批次号待加载」）✅ / **17** 06:20:03 blue（#496 缺陷 48，`CustomerSuppliedSettlementCoverageGuard.consumedByOtherSettledPlans` 0→2、旧 `resolvePlanShare` 0 作阴性）✅。生产 = `2bcdb55dd1`；**未部署：#497**（待 CI）。

**#496 终审两轮 → 通过**：第一轮卡单位口径（新聚合只按 materialTypeId、kg 与 个 直接相加）与 `received_quantity`=0 前置（我 prod 排除：5/5 全填）；第二轮：三个聚合改 `UnitQuantity`、**三侧**单位一致才比、不一致跳过 + WARN（`ListAppender` 断言真打了）、聚合内混单位 `unit` 落 null ⇒ 任何比较都不可比（堵住 `merge` 里的漏）、写路径「剩余额度」减法同样守。终审：全关比半开安全（半开产出看起来权威的假数 = 形态 A¹⁰）。**非阻塞跟进（登记）**：`audit()` 里把单位不一致挂成 `Severity.INFO` 可见提示，不只留 `log.warn`（本仓「没人读的告警等于没有」）。既有范围：闸只对 `isWorkflowPlan && CUSTOMER_SUPPLIED` 生效。
**#497（47 自备料）终审两轮 → 通过**：第一轮卡快照/活值双源（领料三处读行快照、`planNative` 读档案活值 ⇒ owner 勾完冰水会卡在确认领料走不到报工豁免）；修成 `isEffectivelySelfSupplied` = 快照 OR 活值（单向加宽，`findByIdIn` 批量一次 + factoryId 过滤两层边界），矩阵测试两行、变异只红活值行。**⚠️ CI vitest 红 1**：`pendingRequiredQty.source.spec.ts` 按字面正则钉 #469 的留空判断，#497 在同一表达式加豁免 ⇒ 字面变性质没变（C‴），已退执行者改断言守性质 + 补「把 0 当没填 ⇒ 红」阴性。**上线后操作**：物料档案勾「自备料」即可，已生成的领料单不必重建；误勾后生成的单会把 true 定格进快照，取消勾选不改老单（迁移注释的既定意图）。
**#499**：48 执行者诊断的两条已修并上线（见部署 16）。

**方法学（本段）**：源码闸**守字面就会咬到同一表达式上的正当修改**（今晚第二次同形：#487 的 `reportApprovalMenuMatchesBackendRoles` 守 menu roles[] 被搬到 tab 时也得跟着搬）⇒ 源码闸断言性质（含/不含哪些形状），不断言整段字面。

**早上 owner 在场后（f006 已收）**：硬刷新 → `PLAN-…-34F8CD82` 结单弹窗不再报 5900/3000 那两行 → 阴性对照（手加一条清单外客供批次 ⇒ 拦）→ 真结单 → 核 `CMR-20260831-753973766C24` 5900→5800。

**在飞 1**：#497 断言修 → CI → 合 → 部署 18 → 通知工艺 chat rebase 配比（迁移 `_25`）。**待 owner 醒后**：配比 PR 在工艺 chat 说「开 PR」；46 的 `warehouse_worker` 同获写权限要不要收成角色门；单位不一致 INFO 提示。

### 📌 2026-09-02 06:40 增量 · 部署 18（47 自备料）核验通过, 合并 66, 在飞 0

**部署 18** 06:35:38 green（#497，`FactoryMaterialRequisitionServiceImpl.isEffectivelySelfSupplied` 0→1、48 仍 2、假串 0；`flyway_schema_history 20261101.24 success=t 06:35:46`；两张表 `self_supplied` 列都在；LIUSHANMEN 已勾 0；web `list-BPERIKjL`/`index-CS_YlESX` 含「自备料」经 `index-BwIJqWiw` 挂主入口）✅。#497 的 CI 红修法：`pendingRequiredQty.source.spec.ts` 改成括号计数提取 + 性质断言（含三种「没填」、不含三种「把 0 当没填」）+ 三个合成坏 fixture 阴性 + 真 `list.vue` 变异红绿；`list.vue` 字节不变。**生产 = main = `c3d59bbf93`，无积压，在飞 0。** 交接件 `2026-09-02-ORGANIZER-HANDOFF.md` 已订正为 06:40 版。工艺 chat 已收到 47 合入通知，在 rebase 配比（`_25`），等 owner 在它 session 说「开 PR」。

### 📌 2026-09-02 07:55 增量 · 部署 19/20, 合并 68, 48 热修上线, 建产品 NOT NULL 事故, #443 接手

**owner 醒后裁定/动作**：在工艺 chat 说「合并部署推送吧」（#503）；对我说「合了就直接发 19」「443 还没合并吗」。

**部署 19** 07:30:57 blue（`b2adcfb2f4` = #503 主料份额线 + #504）✅：jar 含 `InputShareRatios.class`/`WorkflowSheetDownstream.class`，`flyway 20261101.25 success=t 07:31:07`，`input_share_ratio` 两列在，web `index-Dd3XQTh7` 含「按配比」。工艺 chat 独立只读复核五项一致，已收本机 worktree。**人工待做（owner 亲手或在工艺 chat 让它陪）**：工艺 190/192 第 1 道三个 INPUT 填 30/30/40 重新发布。
**部署 20**（`909f71e2e6` = #505 缺陷 48 热修）：**第一次被脚本误回滚** —— `[BG 2/4]` readiness 循环跑在一条远端 ssh 里，只会返回 `UP/RESTART_LIMIT/TIMEOUT`，这次 ssh 35 秒断线返回**空串**，脚本走进「健康检查失败 ⇒ 停 idle + 回滚 jar」；journal green 07:47:40 Started → 07:48:17 Stopping，应用日志 07:48:26 `Started … in 44.651s`（正常）。⇒ **发布脚本三态漏洞（登记，未修）**：空串 = 没量到，应重探不应回滚（memory 已记）。**重发 07:49 → 07:52:00 green ✅**，`filterCustomerOwned` 0→2。生产 = main = `909f71e2e6`。

**合并 68**：#503（终审六条全过 + 27 commit 清单只 4 条碰写路径均过；跟进：`WorkflowReworkDeduction` 零生产调用点 = **回料扣减未上线，⛔ 演示别承诺**；领料读修订 `nodesJson`、报工读编译列，同一值两存储今天一致）/ #504 份额 0 不显示「按配比 0%」/ **#505 缺陷 48 热修**：#496 的规则①②被应用到本批**每一样**消耗，`COMPANY_OWNED` 的冰水/L1纸箱被报「清单之外」—— 我 brief 的「客供料」没落到批次 ownership（f006 认一半：它的阴性对照原文也没写「客供」）⇒ `filterCustomerOwned` 只对 `CUSTOMER_OWNED` 批次校验，三个喂入点全覆盖；终审通过，跟进：查不到 batchId 应 warn、`consumedElsewhere` 不按 ownership 过滤的罕见假拦截。**判据里的限定词不落到字段上等于没有。**

**🔴 建产品 NOT NULL 事故（07:47:51，owner 亲撞）**：建半成品「金检剔出不良品」`PTSEMI-LSM-DEFECT` 被拒 `null value in column "accepts_rework" violates not-null`。#503 迁移加了 NOT NULL 列，实体字段可空无 Java 默认 ⇒ Hibernate 显式插 NULL，DB 默认不生效。**热修已派**（`ad9a6a8bcf4a5cb20`：实体默认 false + 扫 #503 同形列）。⚠️ 这是「合并前 CI/终审都绿、上线后第一次真实写入才炸」—— 终审只判了 4 个写路径 commit 的账本风险，没查「新增 NOT NULL 列 vs 可空实体」这一形状，**登记为终审清单新项**。
**#506（advisory 口径）**：执行者找到更深根因 —— `resolveAvailableRawStock` 客供分支只查 `CUSTOMER_OWNED`，从没吃到 38 的类别规则（与 38 同根、38 只修了一半，f006 独立核到同一处）；修法复用 `isCompanyStockEligibleCategory` + 仓类型限 RAW/LOGISTICS/WORKSHOP。**CI 两道红**（JPA 启动校验 context 起不来，疑 JPQL 枚举全限定字面量），退执行者改成参数传集合。跟进：全仓数 `== CUSTOMER_SUPPLIED` 收窄查询的地方（`resolveMaterialStockUnit` :1649 同形）。
**#443 接手**（`afc3cddf0c8099e9f`）：rebase（落后 64，含 #447）、横幅接 `explainStepListFallback`、新流程 `stepFlow` null、画布↔步骤列表往返不丢边测试；回来过终审。

**新登记**：「有 N 行成本未显示 —— 检查来源半成品单价/工序成本/成品成本结算」三个原因都不对，真因 `laborSegments == null`（未录工时）100% 可判 ⇒ 提示应说「本道未录工时」。**方法学**：读数自相矛盾先怀疑标记（f006 用 `CUSTOMER_OWNED|ownership` 读出 7→0）；`filterCustomerOwned` 是好标记（排他/ASCII/19 字符/方法名）。

**在飞 3**：`accepts_rework` 热修 → 发 21 / #506 CI 修 → 合 → 发 21 或 22 / #443 → 终审。

### 📌 2026-09-02 08:40 增量 · 部署 21, 合并 72, owner 「在飞修好直接合并部署、能省就省」, 结单第三处漏改

**owner 裁定（08:1x）**：「在飞的内容修复好了以后全部直接合并部署」「热修合了直接发 21，好了叫我，中间能省就省」⇒ 终审只保留写路径新增项（#509），#443/#506/#508 CI 绿即合。

**部署 21** 08:26:44 blue（`26ac4d3699` = #506 + #508 + #443）✅：`FactoryWarehouse.SETTLEMENT_ELIGIBLE_TYPES` 0→1、`filterCustomerOwned` 仍 2、web `index-VEXa_owu` 含「线性步骤列表」（旧构建 0）经 `index-CHPn4nSG` 挂主入口。生产 = main = `26ac4d3699`。

**合并 72**：#506 advisory 口径（根因 `resolveAvailableRawStock` 客供分支只查 `CUSTOMER_OWNED`，与 38 同根；CI 红 = Hibernate 6 HQL **解析不了嵌套枚举全限定字面量** `FactoryWarehouse.WarehouseType.RAW` ⇒ 改成 `IN (:eligibleTypes)` 参数 + `FactoryWarehouse.SETTLEMENT_ELIGIBLE_TYPES` 唯一定义，2 参 default 重载委托 3 参，28 处测试桩零改动）/ #508 `accepts_rework`（**根因订正：列来自 #313 `V20261101_05` NOT NULL DEFAULT TRUE，不是 #503**；8 个构造点 1 个显式写 null、7 个同样暴露；实体默认 TRUE + 创建路径判空；⚠️ 标记不可 grep，行为判：建产品成功）/ #443 步骤列表（rebase 64 落后含 #447/#453/#455；横幅改由 `explainStepListFallback` 推导；新建业务流程空步骤列表 + 修 `canSave`；补悬空边阴性；111/111；按 owner「能省就省」未再送终审）。

**🔴 缺陷 48 第三处（结单仍拦，08:0x owner 亲撞）**：#505 生效后（「审计通过」绿条），点确认报「客供料只能用于同一客户、同一销售订单」—— `ProductionPlanServiceImpl:6161` 调 `ProductionInventoryOwnershipGuard.assertMaterialBatchAllowed` **3 参旧重载**（category null ⇒ 「一律按最严格 = 原料」）⇒ `COMPANY_OWNED` 冰水/L1纸箱 在结单被当客供原料核血缘。**38 同一形状第三次**（领料 ✅ 报工 ✅ 结单 ❌）；6 个调用点 5 个传了 category、唯独结单没传。f006 独立核到同一行。**热修在做**（`ae6b1c85d180026c0`：改 4 参 + 批量取 mainCategory + **删/废 3 参重载**——「默认最严格」的静默重载就是三次漏改的载体）。方法学：**改共享结构时，凡带「默认口径」的静默重载都算一处未改。** 顺带登记：`sameSalesOrderItem` 的 item 级判据在生产上永远走不到 equals（客供批次 `source_sales_order_item_id` 全 NULL）= 死代码，不动。

**#509（工艺 chat，缺口②）**：单成品 BOM → 联产 family 的 API 死锁 ⇒ 预检把「钉在目标修订上的完整 DRAFT family」判 `AUTO_MIGRATABLE`，发布时 4 参 `activateRecipe` 整体激活。终审**过**，合前加两处：4 参方法自己加 `@Transactional`（今天靠调用方 `synchronizeForPublish` 的事务）；同一修订两个合格 family 时**不静默择一**（改报 CONFLICT）。在途计划按 id 读无 status 过滤 ✅。
**工艺 chat 在 owner 直接授权下的 prod 配置**：工艺 197 v2 产品族（4 线各 10 道，共用原料 Cell；份额 美式 30/30/40、小吃吧 50.88/49.12、熟肉饼 50.73/49.27、汉堡排 88.66/11.34；回投矩阵在绞肉；金检出「不良品」共用 SKU `PTSEMI-LSM-DEFECT`）；BOM family 4 份 ACTIVE v2 钉 197/315，旧 v1 ARCHIVED；小吃吧/汉堡排旧工艺已停，**熟肉饼 192 等 `PLAN-…-B141A9D8` 结束再停**。直接改库 SQL 作只读记录进 `docs/audits/liushanmen/sql/`（随 #509）。演示口径：「主料比例、跨线回料、不良品、辅料自动扣减已配置；份额只对新建计划生效；**回料扣减未上线**」。

**新登记（不派）**：**50** 生产批次搜索无命中回落到未过滤列表（必须空态）；**51** 「分配工序操作员」入口藏在批次详情，owner 意见放计划行「更多」，保留批次侧 + 「取消负责人」+ 已完工禁用；45 `baogong001` 是仓管角色；调料绑定「物料必须有价」闸（14/24 味无价）。

**在飞 2**：结单 3 参热修 → 合 → 发 22 → f006 陪 owner 结单 / #509 两处小改 → 合 → 随 22。

### 📌 2026-09-02 09:05 增量 · 部署 22/23, 合并 74, 结单第三处上线, 在飞 0

**部署 22** 08:55:33 green（`ff2f69aaaa` = **#511 结单第三处热修**）✅：守卫类 3 参描述符 `(ProductionPlan;MaterialBatch;String)V` **1→0**（X 消失）+ 4 参描述符 1（Y 必在）+ 假串 0。#511 内容：`:6161` 改 4 参、按批次批量取 `mainCategory`、**删除 3 参重载与 `assertMaterialBatchOwnershipMatchesPlan` 3 参包装**（守卫 6 + 包装 4 = 10 个调用点全显式传 category）；真入口 `settleProduction()` 阳性/阴性；变异回 null 逐字复现 prod 原串；rebase 撞上 #506 同名 `resolveMaterialMainCategory`，保留 #506 的。
**部署 23** 09:02:39 blue（`ea8327082c` = **#509 联产 family 收编**）✅：`BOM_FAMILY_ADOPTION_AMBIGUOUS` 0→1。#509 终审过 + 两处 follow-up 已加（4 参 `activateRecipe` 自带 `@Transactional`；多合格 family 报 CONFLICT 不静默择一 + 阴性）；CI 红真因 = `ByProductActivationAndBomUxContractTest` 按 `private BomRecipe activateRecipe(` 字面定位方法体，提成 public 后失配（**源码闸守字面**第三例）。`docs/audits/liushanmen/sql/` 四个只读记录随之进 main。
**生产 = main = `ea8327082c`，在飞 0。** f006 陪 owner 走结单（预期读数：`CMR-20260902-2596675BDD9E` used 12、`production_settlement_consumptions` +3、计划 COMPLETED、`PB-…-47204` 9.8kg）。

**工艺 chat 下一条**：`WorkflowReworkDeduction` 接进结算（owner 在它 session 说「按照顺序吧」），🔒 到 PR 送终审。

### 📌 2026-09-02 09:30 增量 · 结单真扣落账, 部署 24, 合并 75, 「继续飞」六条 + #514 在审

**🎉 来料加工线整条走通**：`PLAN-1788305552728-D94F7E22` 09:05:34 结单 COMPLETED actual 9.80；客供 `CMR-20260902-2596675BDD9E` used 12.000 DEPLETED（`-WKS-` USED_UP）；`production_settlement_consumptions` +3（1399上脑 12 / 冰水 0.42361 / L1纸箱 0.68012 = 13.10）；辅料真扣（冰水 used 0.42361、L1纸箱 0.68012）。**成品批次未进 `finished_goods_batches` 是正确行为**：`production_settlements.posting_status = PENDING_WAREHOUSE_RECEIPT`，等「仓储确认入库」（前两次结单的成品行都是那步以 `Workflow output receipt` 生成）。#506 行为读数：计划列表「原料参考」不再报缺料、弹窗横幅消失。「金检剔出不良品」`PTSEMI-LSM-DEFECT` 07:49 由工艺 chat 建（`accepts_rework=f`）。

**部署 24** 09:25 web-only（`63123c529f` = #513 缺陷 50 搜索空态，`list-BiH_ENak` 含「没有匹配「」经 `index-BSAIgiRF` 挂主入口，旧构建 0）✅。生产 = Java `ea8327082c`（部署 23）+ web `63123c529f`。

**合并 75**：#513 —— 根因比登记的深：`GET /processing/batches-with-clerk-wip` **没有 `batchNumber` 参数**（Spring 静默丢弃），服务端分页 ⇒ 不在当前页的批次永远搜不到；#513 只做了前端过滤 + 诚实空态，**后端参数作第二个 PR**（同一执行者续做，`ContainingIgnoreCase`，⛔ 别在 `@Query` 里用嵌套枚举全限定字面量）。

**owner「继续飞」（09:1x）→ 六条已派**：51 计划行「分配工序操作员」入口（保留批次侧/取消出口/已完工禁用）/ `== CUSTOMER_SUPPLIED` 收窄查询全仓扫（含 `resolveMaterialStockUnit`，复用 `isCompanyStockEligibleCategory`）/ 「有 N 行成本未显示」改「本道未录工时」+ #496 两条跟进（INFO 提示、查不到 batchId warn）/ **NOT NULL 列 vs 可空实体全仓扫描做成闸**（`accepts_rework` 形状，含阳性对照）/ 发布脚本两处三态（陈旧锁 `kill -0`、readiness 空串重探不回滚）/ 批次搜索后端参数。**45**（`baogong001` 角色）f006 陪 owner **10 点演示后**在界面改。

**#514（工艺 chat）`WorkflowReworkDeduction` 接进正式报工**：🔒 送终审（重复扣减/撤销退回、幂等、跨线回投矩阵边界、对 48 累计口径的污染、顺手修的 `InterimSettleServiceImpl` continue）。**合并后 10 点演示前不发**（它改变报工投入量 100→108.5），演示后随下一批发。

### 📌 2026-09-02 10:10 增量 · 演示中冻结部署, 合并 82, 待发批次已攒

**演示期（10:00 起）不发部署。** 生产 = Java `ea8327082c`（部署 23）+ web `63123c529f`（部署 24）。

**演示前合入（未部署，等 owner 说演示结束一次发 25）**：
- **#514** 回料按配比自动扣减接进正式报工（🔒 终审两轮：Q1 两套 sourceType 词表结构性隔离 / Q3 矩阵在画布 policy 层 + 阴性 / Q4 不进 RAW_MATERIAL/CUSTOMER_OWNED 累计 / Q5 小结 continue 不误跳旧行；卡 Q2 幂等 → 4 行守卫「载荷已含 automaticRework ⇒ Outcome.empty」已补）。⚠️ **上线后报工投入量会加上回料（100 → 108.5）**。**登记既有问题**：撤销报工**不退回**提交时扣的回料（OUT 流水无 reportId、撤销只按 reportId 找流水），手工回料同样中招，本 PR 让它每天发生 —— 工艺 chat 开票 `codex/claude-rework-reversal`，第一个 commit 先放 H2 阴性钉「没还」。候选池 (factory, product, processOrder) 不含 planId，与手工同池。
- **#516** 缺陷 51 计划行「分配工序操作员」（经 `ProductionDocumentTrace` 取 `PB-*`，`findByFactoryIdAndProductionPlanId` 排除 CLERK_WIP；无 PB 禁用 + tooltip、多 PB 弹选；批次侧/取消出口/已完工禁用三条钉住）。
- **#517** 38 系统性扫：19 处分类，再修 2 处（`resolveWorkshopRawStock` 对辅料从不报「未调拨到生产仓」；`resolveMaterialStockUnit` 读成采购标签「箱」而非批次「个」）；`workshopCaliberAvailable` 登记不改。
- **#518** 发布脚本：陈旧锁**根因 = `release-cretas.sh`/`deploy-web-admin.sh` 的 `trap … EXIT` 覆盖了 `acquire_deploy_lock` 的清锁 trap**（bash trap 不叠加；`deploy-backend.sh` R43 早修过同坑）⇒ `$ACQUIRED_DEPLOY_LOCK_FILE` 三处统一；漂移重试 `exec bash "$0"` 同 PID 自拒 ⇒ 自重入检查；readiness 三态（空串=没量到 ⇒ 有限重探 + 独立直查，总预算仍 ~150s，上界 ~193s；真失败分支回滚 jar 原样）。终审过；**跟进 2 行**：重入判据加 `CRETAS_DEPLOY_REENTRY_PID` 合取，封 PID 回收撞泄漏锁的窄口（已退执行者）。
- **#519** 「有 N 行成本未显示」改为派生原因「本道未录工时（N 道）」（后端 `costMissingReasons`，只在 WIP 分支；clerk 分支工时缺失只贡献 0 不致 null，故不归因）+ #496 两条跟进（单位不一致挂 `Severity.INFO` 不影响 `clean`；查不到 batchId warn）。
- **#520** 调料工作区允许绑无价物料：`priceSource1/2` 双 null 不是 0；报工侧 `unpricedNames` 登记 + warning（与既有「调料成本暂记 0」四处同口径）。终审过：**权威成本走实际批次价**（`OrderCostBreakdownService:348` 独立登记 `SEASONING_PRICE:*` ⇒ `costComplete=false` ⇒ #503 null+note 守卫仍触发），0 只在报工屏估算层。**跟进**：工作区列表补「无价」计数（14/24 项需常驻入口）。
- **#513**（已随部署 24 上）根因：后端 `batches-with-clerk-wip` 无 `batchNumber` 参数 ⇒ 后端参数作第二 PR（在做）。

**在飞 4**：NOT NULL 列 vs 可空实体扫描闸（`a4a3a0f8e36f32f18`）/ 批次搜索后端参数（`a5e14e58d08f5ac35`）/ #518 跟进 2 行（`a4ef11071c69d0f8f`）/ 撤销回料票（工艺 chat）。**不做（需 owner 重裁）**：路①「按成品量反推领料」与 9-01「本单投入现场填」裁定冲突。**演示后**：45 `baogong001` 角色（f006 陪 owner 界面改）、成品「仓储确认入库」读数。

### 📌 2026-09-02 10:30 增量（时钟按系统 date 订正） · 合并 85, 待发 25 批次齐, 三道终审

**合并 85**（演示中冻结部署，均未发）：
- **#522** NOT NULL 闸：684 迁移 → 857 列 → 78 处「NOT NULL DEFAULT 列 + 可空实体无 Java 默认」全修（35 个实体；72 处还要 `@Builder.Default`，Lombok `@Builder` 会**静默丢掉字段初始化**；6 处实体被当自己的 PATCH 载体（`ApprovalWorkflow.nodesJson/edgesJson`、`ExportRule.*`、`ImportRule.dedupStrategy`）不能加默认 ⇒ `@PrePersist`，执行者是被周边测试红了才发现）。终审过：风险面按形状**闭合枚举**（patch 载体恰好 3、`Example.of` 0、copy 源 1 安全）；点名的 status/active 字段全走 DTO 更新，实体是 target。**跟进**：`copyForTarget` 显式传 null 绕过 `@Builder.Default`（`BomItemSubstitute.conversionExplicit` 补 `@PrePersist`）+ 闸注释写明看不见什么（已退执行者）。memory 已记。
- **#523** #518 跟进：重入判据加 `CRETAS_DEPLOY_REENTRY_PID` 合取，封 PID 回收撞泄漏锁。
- **#524** 缺陷 50 第二步：`batches-with-clerk-wip` 加 `batchNumber` 模糊参数，CLERK_WIP 经 `ProcessSheetRow ⋈ ProductionPlan` 子查询按计划号匹配，**分页在过滤后**；87 个 `*RepositoryQueryValidationTest` 绿。#513 的前端过滤保留为恒真式保险。
- **#520**（见上段）终审过。

**工艺 chat 撤销回料票摸出更重一层**：回料 OUT 流水无 `report_id` ⇒ 不只撤回不还，`validateFullWipLedgersBeforeMutation` 会让**产出该回料的整批**撤回时撞 `WIP_LEDGER_INVALID`（一旦有人从它领过回料）；修法 = 提交时把 OUT 流水挂到该行首条 YIELD 报工。小结路 (A) `consumeClerkSemiStrict` 同形（既有设计，登记）。第一版阴性测试是仪器问题（造的行无 YIELD 报工 ⇒ `submitReversal` 真 no-op，硬约束 9 阳性没亮），已换形态。

**待发 25**（等 owner 说演示结束）：Java `#514 #517 #519 #520 #522 #523 #524`（+ #518 已在 main 但脚本不入 jar）；web `#516`。⚠️ 行为变化：报工投入量含回料（100→108.5）；新建实体行拿 78 个列默认。
**在飞 2**：#522 跟进 / 撤销回料票。**演示后**：45、成品入库读数、路①需 owner 重裁。

## Scope 锁地图

- `FIX-LSM-SUPPLIER-PRICE-REQUIRED-20260901`：仅 `web-admin/src/views/procurement/suppliers/SupplierDetailDrawer.vue` 与对应目标测试。⛔ 不得改 `permission.ts` 权限矩阵; 若判定需改后端/导入路径对空价格的行为 → 🔒 停在 PR 交协调者, 不自行落地。
- `QA-LSM-APPROVAL-MENU-ROLES-20260901`（PR #450，待合）：`web-admin/src/components/layout/menuConfig.ts` 的 `/production/approval` 那一条、`web-admin/src/components/layout/__tests__/reportApprovalMenuMatchesBackendRoles.spec.ts`、`.github/workflows/web-admin-gate.yml` 的 `paths:`。⚠️ 与 `QA-LSM-APPROVAL-403-AS-ZERO`(已合, 改 `views/production/approval/list.vue`) **同屏但不同文件**, 无冲突。
- `QA-LSM-443-ACCEPTANCE-20260901`：`web-admin/src/views/platform/approval-workflow-editor/**`（#443 的 4 个文件）。⛔ 只读验收不改产品代码; ⛔ 不碰 `views/production/**`。

- `FIX-QC-VERSION-OTA-APK-20260829`：`frontend/CretasFoodTrace/src/screens/quality-inspector/QIProfileScreen.tsx`、`QISettingsScreen.tsx`、`src/i18n/locales/{zh-CN,en-US}/quality.json`、`src/__tests__/gates/nativeVersionConsistencyContract.test.ts`、`docs/dispatch/ACTIVE.md` 与当日归档；发布阶段只读使用官方 OTA/APK 脚本。验收：屏幕版本来自 `expo-application`，1.0.3 兼容 OTA 可拉取，1.0.4 APK 原生版本/runtime/versionCode 一致且强制更新门禁保留，签名、启动冒烟及公网制品验证通过。

- `ML-LIUSHANMEN-WORK-AREA-ROI-20260811`：仅 `tools/vision-lab/work_area*.py`、`tools/vision-lab/mine_tray_queue.py`、`tools/vision-lab/tray_workflow.py`、`tools/vision-lab/evaluate_tray_candidate.py`、对应 `tools/vision-lab/tests/`、`docs/ops/liushanmen-vision-lab.md`、`docs/dispatch/ACTIVE.md` 与当日归档；不得改写人工真值、保护集、原图、现有模型或生产。


- `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001`：销售需求、BOM净需求、采购建议、客户来料、仓储来源任务及对应 Java/Web/测试/dispatch；涉及 Entity/Repository/Flyway 时必须重登记最新 Base 并通过真实 JPA/迁移门禁。不得开启普通手工收料写入口或修改生产数据。


- `ENH-F006-SUPPLIER-IMPORT-001`：供应商 Java Entity/DTO/Controller/Service/Repository、采购门禁、共享 Excel 导入与目标测试；Web 供应商列表/详情/新增编辑/导入及 API/types/tests；必须真实 JPA Context（如触及查询），生产业务写入为 0。

- `BUG-F006-R2-SKU-UNIT-001`：仅 Web SKU 新建/编辑基础规格、displayUnit/净含量单位 helper 与目标测试；Java 净含量契约须另行登记不重叠 scope。

- `FEATURE-F006-R2-WORKPROCESS-DEDUP-001`：Java WorkProcess/Workflow/SKU 关联、DTO/Repository/Service/Controller、Flyway/JPA tests；Web 工序治理及排序/单位字段移除与目标测试；不得迁移既有业务引用或治理生产重复组。

- `FEATURE-F006-R2-BOM-SUBSTITUTE-001`：BOM 替代关系的 Web、版本/计划/领料/报工/成本/追溯快照与目标测试；Java CORE 已完成，父任务不得重复修改 CORE Flyway/Entity，必须与统一 Workflow/BOM 任务串行。

- `F006-R2-WORKFLOW-FIRST-BOM-GATE`：Workflow 草稿结构、BOM 完整性 DTO/Service/API、激活/发布/生产计划准入、Web 阶段引导与目标测试；不得改写历史快照或生产数据。

- `BUG-F006-PRODUCTION-COST-CLOSURE-001`：仅 `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProductionPlanServiceImpl.java` 的成本原因组装（`describeCostUnavailableReason` / `usableTotalCost` / 缺项中文标签）与对应 `src/test/java/com/cretas/aims/service/impl/*ContractTest`、`docs/dispatch/ACTIVE.md`。⛔ 不改 `OrderCostBreakdownService` 的成本算法、不改 Entity/Repository/Flyway、不动 `totalCost` 的完整性口径、生产 F006 业务写入为 0（本轮只用只读 GET 与只读 SQL 取证）。


## 阻塞项

- LIUSHANMEN：21 条空 `storage_type` 等待 Owner 向客户取得真实储存类型；结论到达前保持零写入，不猜填。
- VisionLab：正式 ROI audit 仍有 1 个 unjudgeable；计划任务迁移与运行失败已核销。
- LLM 运营：2026-09-13 aistore 续期换 key，以及 2026-09-04/2026-11-15 复审需要 Owner/供应商控制台动作。
- 餐饮 RN：实现已入 main，但当前 OTA runtime 与真实设备验收未确认。
- 凭证：旧 Aliyun 主账号 AK、旧模型供应商 key、Mall 微信长期凭证仍需各控制台吊销/重置并验证失效。
- F006 产品/架构：taxonomy suggestion、统一收料来源、AP 付款完整边界仍是无执行者 backlog；需求驱动收料与客户渐进创建保留 scope，后者须先裁定必填规则。

## 维护规则

- 只记录当前在飞、阻塞和下一动作；已完成事项移入带日期的 `archive/`。
- 创建 worktree、派发或合并前，先核对本表与 `git worktree list`。
- 本文件只有当前协调者可修改；子代理只能按 `AGENTS.md` 的结构化回执格式报告状态。
- 状态只允许：`queued`、`claimed`、`in-progress`、`review`、`merged`、`blocked`。
- 合并完成时必须同时归档任务、释放 scope 锁，不能留下已经结束的在飞行。
- 同一仓库默认并发最多 2 个代码执行代理和 2 个只读/测试代理；不得超过当前运行时槽位，且无独立价值的工作保持 DIRECT。
