# LIUSHANMEN 走查线 organizer 增量日志（2026-09-02 → 2026-09-05，原样存档）

> 2026-09-05 从 `ACTIVE.md` 整体搬出：台账不再承载增量日志（每条 ≤300 字），日志按天写 `archive/`。
> 第一段「凌晨快照」的任务条目在 `2026-09-05-active-entries.md`；这里是它的标题与之后 26 段增量。

### 📌 2026-09-02 凌晨 · LIUSHANMEN 走查线 · organizer 状态快照（`liushanmen-organizer-handoff-fa5fb5-bb`）

（任务条目见 `2026-09-05-active-entries.md`）

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

### 📌 2026-09-02 11:34 增量（系统时钟）· 合并 89, 在飞 0, 仍冻结等演示结束

**时钟事故（两边同犯）**：我台账「10:10 / 11:2x」按估计写，f006 整轮没跑 `date` 差整一小时（把 09:05 的 DB 时间戳当「刚刚」）。三方核对 10:32 一致（本机 / 47 / DB）。⇒ **时间只用 `date`，⛔ 不估**（形态 A：想知道「现在几点」，量的是「我以为过了多久」）。

**合并 89**：
- **#526** #522 跟进：`BomItemSubstitute.conversionExplicit` copy 处 coalesce + `@PrePersist`；闸注释写明看不见什么（显式 null 调用点 / 默认语义对不对）。**CI 曾红**：同一实体两个 `@PrePersist`（JPA 每类每事件只允许一个）⇒ EMF 起不来 ⇒ 所有 `@DataJpaTest` 红 —— 与 #506 的嵌套枚举字面量**症状逐字相同、根因不同**（memory 已记：看 log-failed 第一个非 H2 `Caused by`；本地跑 `-Dtest=*RepositoryQueryValidationTest`）。
- **#527** 撤销报工还回提交时扣的回料（🔒 两轮）：c1 H2 阴性钉「没还」（仪器教训：只带回料上游的行不写 YIELD 报工 ⇒ `submitReversal` 走 no-op，前两版探针白量）；c2 `executeReversal` ①.0 软删前按 `row_payload` 还回。第一轮卡两处：**计数单位 SFI「扣盒还 kg」**（`consumeClerkSemiStrict` 扣前折算成原生单位、还回直接减 kg ⇒ 4 盒蒸发/凭空造）⇒ 建成对的 `restoreClerkSemiStrict` 复用 `resolveSemiFeedQtyInSourceUnit`；**REVERSE 挂 reportId 违反批次作用域**（`WIP_SCOPE_INVALID` 在 txnType 分派前，这次没炸只因写在校验后 ⇒ 该批次后续撤销永久锁死）⇒ `reportId=null`、血缘 `sourceRef=reversal-log:<id>`。复审过：`reportId=null` 不撞 `PARTIAL_COMPENSATION` 的真因是 `involvedSfiIds` 两个来源（本批报工流水 / 本批 WIP）都够不着别批产的回料 —— **涌现性质无测试钉住**（跟进①）；用例 (a) 第二次撤回走「无报工 no-op」对此恒真。c4 棘轮 `UserFacingUnitTranslationRatchet` 52→53 改 `UnitDisplayNames.display`，合进的 squash 含它。跟进②`consumeClerkSemiStrict` 委托同一 helper；跟进③票外那张范围改 **OUT + REVERSE 一起**合规。
- **#528** 工艺画布辅料 cell 标 `¥?` + BOM 气泡缺价计数（只认后端 `MISSING_PRICE`）。

**待发 25**（等 owner/f006 说演示结束）：Java `#514 #517 #519 #520 #522 #523 #524 #526 #527`；web `#516 #528`。⚠️ 行为变化：报工投入量含回料（100→108.5）；78 个列默认；撤销报工会还回料。
**演示后**：45 `baogong001`（改前读数 `id=1653 role_code=warehouse_manager`）；成品批次 `unit_cost=NULL` 定性（点一次「重算成本」）；销售出库需 `lsm004`（`SO-20260902-0001` 仍 FINANCE_APPROVED）；路①需 owner 重裁。

### 📌 2026-09-02 12:36 增量（系统时钟）· 合并 91, 在飞 1, 仍冻结

**合并 91**：
- **#530** #527 跟进 1+2：钉「还回流水够不着 `involvedSfiIds` 两个来源」（本批每份报工含已软删按 reportId 查不到 REVERSE；本批 WIP 里没有回料 SFI）；扣减/还回/查询三处折算收成私有 `toSourceUnit(sfi, feedKg)`（第一版委托按批号再查库让契约测试读成「缺克重」，改成只吃已加载 SFI）。
- **#531** 产出批被别批领过又还回后能整单撤回（🔒 过）。**诊断修正**：今天拦的是 **G1 `hasExternalDownstreamConsumption`**（只认带 reportId 的 REVERSE 作补偿 ⇒ 无 reportId 的 OUT 永远算外部领用），`WIP_LEDGER_INVALID` 是第二道 —— #527 终审只追到第二道。修法 (b)：「外部领用血缘」定义一处（`isExternalConsumptionOut/Reverse`：前缀 `interim-settle-out:` / `reversal-log:` / `interim-settle-reverse-out:` 且 reportId=null），G1 按同一 SFI **OUT − REVERSE 净额 > 0** 才算仍被领着（领着 ⇒ `DOWNSTREAM_CONSUMED` **保留**），账本校验对这两类豁免 reportId/配对但**余额照算 + 符号断言**；来路不明的无 reportId OUT 仍 fail-closed。终审核：报工路径与小结路 OUT 同一写入口 `clerkConsumptionRow`，前缀常量 `CLERK_OUT_REF_PREFIX`（命名债：报工的 OUT 也叫 interim-settle-out，已加注释不改名）；合前补 **净额 < 0 ⇒ `WIP_LEDGER_INVALID`**（新开的静默放行路径，硬约束 4）+ 阴性用例。不选 (a) 的理由：挂消费批报工撞 `WIP_SCOPE_INVALID`，挂产出批报工被 ② 当本批正量 REVERSE。
**派（工艺 chat）**：份额两个存储收敛 —— 领料改读编译列 `workflow_task_ports.input_share_ratio`，`fromNodesJson` 只留编译器；阴性：改 nodesJson 不重编译 ⇒ 领料不变。

**待发 25**（等 owner 本人「可以发」；「时间过了」「在飞 0」都不是放行条件）：Java `#514 #517 #519 #520 #522 #523 #524 #526 #527 #530 #531`；web `#516 #528`。

### 📌 2026-09-02 12:59 增量（系统时钟）· 合并 94, 在飞 1, 仍冻结等 owner 放行

**合并 94**：**#533** 缺陷 16 phase 2（`V20261101_26` DROP `min_order_quantity`/`lead_time_days`，prod 非空 0 行两次复测，实体无 `@Column`；关系层 `supplier_materials.*` 同名字段**保留**，owner 未裁）/ **#534** 份额存储收敛：动手前量出「只读编译列」会让 **29%（5/17）** 领料单静默失去份额（编译列只在转批次后才有；3 张先于首批次、2 张无批次）⇒ 改成 **有实例读编译列 / 否则读修订 nodesJson**，且**解析器合一** `InputShareRatios.parsePercent`（编译器与领料同一判官 —— 两个解析器已不一致才是真会漂的层）；漂移闸 `parsePercentIsTheSingleJudge`；阴性「修订改 10/90 没重编译 ⇒ 领料仍 30/70」。

**给 owner 定（工艺 chat prod 只读）**：`product_process_workflow_activations`：美式汉堡饼 → **197 v2**（08:10:55），小吃吧汉堡饼 → 193、汉堡排 → 191、熟肉饼 → 192 **仍指旧单成品图**（工艺表里 190–193/197 都 PUBLISHED；「停用」载体是激活表，早上记忆「191/193 已停」与库不符）。今天 00:38 / 07:32 两个美式计划钉 190/rev 308（无份额）是在 08:10 激活前建的。**要四个成品都走族图 ⇒ 三条激活改指 197（熟肉饼等在途结束）；建计划 UI 走 BOM family 能识别 197 但激活表指旧图 —— 哪边是「计划钉工艺」的权威要裁。** prod 上 17 张按计划生成的领料单 `input_share_ratio` 全 null = 还没有 197 v2 的计划生成过领料单，不是链路没接上。

**待发 25**（等 owner 本人）：Java `#514 #517 #519 #520 #522 #523 #524 #526 #527 #530 #531 #533(迁移 _26) #534`；web `#516 #528`。**在飞 1**：409 无 `actionHint` 扫（`ae31c75c88fbfb067`）。**未派（登记太粗或需裁定）**：路① / 46 角色门 / 激活权威 / production_manager fallback / priceSource 词表 / 规格单位联动 / 采购门槛随单位 / 各角色首页空态 / 拼音搜索统一 / 质检 0 检验项。

### 📌 2026-09-02 14:06 增量（系统时钟）· 演示结束, 部署 25 落地, 生产 = main `4304bc88d7`, 合并 95

**owner 11:40「结束演示了」**（f006 转达；发的授权 owner 早上直接给过）。**部署 25**（14:03:11 green > jar 14:02:49；`restoreClerkSemiStrict` 0→3 / `resolveCompiledInputShares` 0→1 / `filterCustomerOwned` 仍 2 / 假串 0；`flyway 20261101.26 success 14:03:21`，`supplier_material_purchase_specs` 两列已不存在；web 13:58 `list-FgGEbnUW` 含「计划尚未生成成品批次」经 `index-DHSrdcp0` 挂主入口；health 200）✅。内容：#514 回料自动扣减 / #516 分配操作员入口 / #517 38 扫尾 / #519 工时提示+#496 跟进 / #520 调料无价 / #522+#526 NOT NULL 闸 78 处默认 / #523 脚本重入 / #524 批次搜索后端 / #527+#530+#531 撤销回料与产出批撤回 / #528 缺价标记 / #533 缺陷 16 phase 2 / #534 份额解析合一。
⚠️ **时钟/延迟事故第三次**：从 f006 转达放行到我真正起跑隔了约 2 小时（消息延迟），我又按估计写了「11:45 起跑」；f006 13:57 按三行判据读到「还是上午那版」—— 因为构建才跑 1 分钟，「还在跑」与「跑完没上」在 jar/进程/flyway 三行上同形，**区分靠 exit 文件 + 进程**。⇒ 报「起跑」时贴 `date`；判「没上」前先看有没有在跑。
**f006 登记**：点「重算成本」落空 20px 差点把「请求没发出」读成「重算不填成本」（读结果前核弹窗关没关、`updated_at` 动没动）；演示期间 owner 又结了 `PLAN-…-34F8CD82`（COMPLETED / 88kg，卡仓库确认入库），计划汇总 4 条 / 112kg / 99.79kg / 89.1%。
**两个行为读数**（报工投入量含回料、78 列默认）：⛔ 不主动在真实租户造数据；改前基线读现有 `row_payload` / `information_schema.column_default`，改后等 owner 真实报工。
**45**：#516 已在主入口链 ⇒ f006 陪 owner 改 `baogong001` 角色（改前 `id=1653 role_code=warehouse_manager`）。
**在飞 1**：#536（409 提示）终审抽 10 条 4 条为「必要但不充分」（守卫读计划钉住的修订，hint 让改活的 BOM/画布）⇒ 补「回计划重钉」半句并全扫 37 条；闸注释写明只量有无不量真伪。
**等 owner 裁**：激活表权威 / 三条激活改指 197 / 46 角色门 / 路①。

### 📌 2026-09-02 14:59 增量（系统时钟）· 部署 26, 合并 96, owner 三条裁定, 在飞 0

**部署 26** 14:56:24 blue（`7680a75a4e` = #536 409 提示：47 处 `withHint`，其中 8 条按终审改成「说清机制 + 真出路 + 如实标限制」—— BOM 钉住的指向计划列表「更新到当前配方」（仅未开工），Workflow 冻结快照的指向「修复并重新发布后创建新批次」；前端 `request.ts` 无 hint 的 409 也 sticky 显示 message；ratchet 197、`KNOWN_DEBT` 17→1）✅：jar 14:56:06，`ProcessSheetServiceImpl.class` `grep -a` 到「更新到当前配方」2 /「开工时冻结」2，假串 0；web 14:53 build。CI 曾红两次：单位棘轮 52→55（两处 hint 拼裸单位，与 #527 同坑；棘轮正则把同一 throw 里的 `BusinessException(` 与 `.withHint(` 各数一次所以 +3 不是 +2）。**跟进（非阻塞，登记）**：5 条 BOM 钉住 hint 补「已开工需先撤销本批报工（三信号归零后可重钉）或新建计划」；3 条 Workflow hint 补「本批次请一并取消/作废」；终审只抽了 8 条，其余 39 条同形未重看。

**owner 在工艺 chat 裁（14:2x）**：① 族图激活：三个成品都改指 197 —— **实测已就位**：`GET /product-process-workflows/{productTypeId}/activation` 小吃吧→193 `enabled=false`、汉堡排→191 `enabled=false`（早上工艺 chat 查表只看 `active_workflow_id` 没看 `enabled`，形态 A 读错列），建单对它们只剩 197 可解析；**只剩 192（熟肉饼）等 `PLAN-1788008667414` 结束再停**（deactivate 对在途计划本就 409）。② 路①「按成品量反推领料」**不做**，维持 9-01。③ 如上。**仍等 owner**：46 角色门。

**f006**：25 独立核验通过（口径不同：数含串的 class 文件数而非 strings 次数，⛔ 两组数别并排比）；`unit_cost` 重算**未验**（五次点击都落在遮罩、nginx 日志无该请求 —— 阳性对照是它自己同会话的请求可见，所以「没有」是真没有；需 owner 点一次「确认重算」）。

**在飞 0。生产 = main = `7680a75a4e`（Java）/ web 14:53。**

### 📌 2026-09-02 15:32 增量（系统时钟）· 部署 27, 合并 97, 在飞 0

**部署 27** 15:30:32 green（`35108edf2a` = #539 409 提示跟进：9 条 BOM 钉住 hint（含复扫出的 `PINNED_BOM_NOT_FOUND`）补「已开工的计划需先撤销本批报工（三信号归零后即可重钉），或改为新建计划」；3 条 Workflow 冻结快照 hint 补「请前往批次详情执行「整单撤回」将其取消/作废」（核过是真实按钮，走 `ReportReversalServiceImpl` 置 CANCELLED）；其余 38 条复扫无同形；登记两条既有同形未动 `PLAN_BOM_AUTHORITY_INCOMPLETE` / `PINNED_BOM_FAMILY_INVALID`）✅：jar 15:30:14，`ProcessSheetServiceImpl.class` `grep -a` 「整单撤回」2 /「三信号归零」3，假串 0，health 200。**生产 = main = `35108edf2a`。在飞 0。**

**待 owner**：46 角色门。**待 f006**：45 改前后 `role_code`；成品 `unit_cost` 重算（owner 点一次「确认重算」）；销售出库（`lsm004` 建发货单 → `lsm006` 确认）。**待时机**：192 停用（`PLAN-1788008667414` 结束后）。

### 📌 2026-09-04 17:57 增量（系统时钟）· 两天后重核：生产 = main，无待发

**触发**：工艺 chat 09-04 转达 owner「部署 25 放行吧」。我第一反应引用了两天前的 `date`（又一次拿旧时间戳当现在，见 memory `feedback_still_deploying_reads_identical_to_not_deployed`），重核后：
- **main 自 `35108edf2a`（09-02 部署 27）起 +32 commit**（#543–#571，food-kb 线 SOP 语料/探针、#557 aiassist 去餐饮 tab、#565–#567 web-admin 修复），**零 backend/java 改动**。
- **Java** prod jar 09-02 15:30:14 = 最新（无 Java 改动）。
- **web** 139 index.html 09-03 19:48 已含 #567「改用画布」；此后无 web 改动。
- **Python** 47 部署树 `manual_chat.py` / `f006_production_sop.md` / 契约测试 md5 = origin/main；8083 进程 09-03 21:05:17 > 最后 python 变更 21:04:41（#571）。⇒ food-kb 线自己发过，且进程晚于文件。
⇒ **owner 说的「部署 25」= 09-02 14:03 那批，早已上线核验；当前无任何待发。** 已回工艺 chat；已向 f006 索要 45 / `unit_cost` / 销售出库 三个读数。
**仍等 owner**：46 角色门。**等时机**：192 停用。

### 📌 2026-09-04 18:27 增量（系统时钟）· owner 让我在应用内浏览器替他点：客供线最后一段走完 + 三条新发现

**owner「你帮我点击吧，在应用内浏览器打开，我帮你登」**——他登录、我点（⛔ 我不输密码）。
- **`unit_cost` 定性**（登录态 生产经理）：「重算成本 → 确认重算」请求真发出，返回诚实拒绝「还差 1 项没采集齐（已归集 ¥1.9538）：原料批次 452b3e5c… 缺单价。成品已按实收数量入库，单位成本留空（不填 ¥0）」。批次 = `CMR-20260902-2596675BDD9E-WKS-76d1a2bf`（**客供**，unit_price NULL）。⇒ **不是缺陷，是口径**：来料加工客供主料没有采购价 ⇒ 成品成本永远算不出。**待 owner 裁**：(a) 加工口径 = 辅料 + 包材 + 工时，客供料显式记 0 并标 CUSTOMER_SUPPLIED（我倾向）/ (b) 给客供批次录名义价。⚠️ f006 复算消耗行 × 批次价 = ¥1.5808，与 ¥1.9538 差 ¥0.373 来源不明（无 cost_category 报工行）—— 探针 `a42d5a03d73bfaf46` 在复算组成（假设：冰水被算两次 ≈ 0.377）。
- **销售出库**（owner 切 `lsm004` → 我点）：列表页「创建发货单」→ 主单 `DLV-20260904-4086`；**🔴 缺陷 40 第二入口漏改**：列表页建单没自动生成整单子发运（#492 只接在 `detail.vue:1105`），已派 `ab7d0334c62b27c80`（抽共享 helper 或挪到后端 `createDeliveryRecord`）。订单详情「新建分批发运」→ 子单 `DLV-20260904-4086-01`（客户自提 9.8 kg，母单剩 0.2 短交）。**🔴 #492 自动 FIFO 预填返回 batches=0**（日志 `FIFO 成品推荐 … batches=0, skippedUnitMismatch=0`）而成品批 `PB-…-47204` AVAILABLE 9.8 / WH-FG / CUSTOMER_OWNED / 客户与 SO 均匹配，**手工「分配批次」弹窗能看到并预填 9.80** ⇒ 建单时那条查询（疑 `findAvailableCustomerOwnedBatchesByWarehouse` 带 warehouseId 分支 / prestocked 分支）与推荐接口口径不同；探针 `a9fd0ac81f8d458e2` 在定位排除谓词。我手工「确认分配」9.8。
- **仓库确认出库**（owner 切 `lsm006` → 我点「确认并扣库存」18:26:06）：`PB-…-47204` DEPLETED shipped 9.8；子单 SHIPPED；母单 PARTIALLY_SHIPPED；SO PARTIAL_DELIVERED；**AR_INVOICE ¥78.40 = 9.8 × ¥8 含税 ✅**（8-28 口径）。⚠️ 观察：该 AR 的 `outstanding_amount` / `payment_status` 均 NULL —— 已让 f006 对照老 AR 判「挂账时不填」是设计还是缺陷。短交 0.2 的结案路径现在有活样本（台账「短交结案 path」）。
- **仍等 owner**：45 目标角色（改 `baogong001` 成什么）；46 角色门；成本口径 (a)/(b)。**在飞 3**：40 第二入口执行者、FIFO 0 探针、¥1.9538 组成探针。

### 📌 2026-09-04 18:42 增量（系统时钟）· owner 四条裁定, 探针两条结论, 在飞 6

**owner 裁定（当面）**：**45 不改**（`baogong001` 保持 warehouse_manager）/ **46 只让仓储主管改**物料字典（加角色门，worker 只读 + 提示）/ **成本口径 (a)**：来料加工客供主料显式记 0 并标 CUSTOMER_SUPPLIED，成品成本 = 辅料 + 包材 + 工时 / **短交两条路都支持（补发 / 短交结案），但必须点确认**，⛔ 不自动关。

**探针结论**：
- **¥1.9538 = 1.2000 纸箱 + 0.3800（`traceCost` 追溯 CLERK_WIP 链，叶子冰水）+ 0.3738（结算行 217 同一批冰水再并一次）**，到分复现。缺陷：`OrderCostBreakdownService.settlementConsumptionsFor` 去重集只看当前成品批自己的 `material_consumptions`（:767-777），而 `computeForBatches` 过滤掉 CLERK_WIP、`traceCost` 却独立走进去 ⇒ 结算 rollup（本是视图）被当第二次事件；javadoc「(计划,批次) 组合 0 个」在错粒度上成立。
- **FIFO 自动预填 batches=0 是一次性瞬态**：同一 `findAvailableCustomerOwnedBatchesFefoAllWarehousesExcluding`、同一数据、零中间写入，2 分 2 秒后返回 1；JPQL 逐谓词对 prod 行重放全过；活 jar `javap` 与源码一致；手工弹窗与自动走**同一个** `recommendFifo`。不修，登记：若复发抓 `pg_stat_activity`/Hikari 池状态。⚠️ 顺带：`DataScopeResolver.java:89` 每几秒 `bad SQL grammar [SELECT data_scope FROM role_definitions …]`，**每个请求都失败** ⇒ 兜底方向决定严重度，已派查。

**在飞 6**：40 第二入口（`ab7d0334c62b27c80`）/ 成本重复计 + 第二 PR 口径 (a)（`a76a43d4bc3282400`，🔒）/ AR `outstanding_amount`/`payment_status` 挂账 + 回填迁移（`a6d1863c025059ba4`，🔒）/ 46 角色门（`a08b4ff879c504d10`）/ 短交两路 + 显式确认（`a7107535c50c8f4af`，🔒）/ DataScopeResolver（`a2188e3b036c19d42`）。到 PR → CI/终审 → 合 → 发 28。

### 📌 2026-09-04 20:31 增量（系统时钟）· 发 29/30 上线, 六票落地, worktree 72→39

**发 29**（20:17，`c5f6f61858`）：#576 成本重复计去重（结算去重集扩到计划级含 WIP）/ #577 AR_INVOICE 挂账初始化 + 回填迁移 `_27` / #578 缺陷46 物料字典 5 个写端点加 `@RequireRole(warehouse_manager, factory_super_admin)`。判据：EXIT=0、FINAL_STATUS 1 次、jar mtime 20:16:49 < 启动 20:17:11、`initializeOpenItem` 1 / `findByFactoryIdAndPlanProductionBatchesIncludingWip` 2 / 假串 0；web 入口链 456 chunk 命中「物料字典由仓储主管维护」。

**发 30**（20:28，`05bf2e05bd`）：#579 DataScopeResolver / #580 客供口径 (a)。判据同上（`RoleDefinitionRepository` 4 / `customerSuppliedItems` 1 / 假串 0），并**量到库里的效果**：`role_definitions` 30 行（此前该表在 prod **从来不存在**）、`yield_operator=SELF`（六膳门 10 个报工账号此前在 6 个 `@DataScope` 端点上是全厂范围）、AR_INVOICE 15 张回填成 **PAID 5 / PARTIALLY_PAID 2 / UNPAID 8**，与终审预算逐字一致。

🔴 **本轮三条并行线各自数 Flyway max 又撞成 `_27`**（#577/#579/#581），拆成 27/28/29，合前当场重数。这是 09-02 之后第二次同形。

⚠️ **登记不修**：① #576 去重是**批次级、数量盲**（prod 现有风险量 0.026 单位）；② AR 的 outstanding/payment_status **只写不读**，`recordArPayment` 未维护，收款路径下一票；③ `DataScopeResolver` 认不出的 role_code 仍 `default → ALL`（fail-open 的另一半，本次只收紧了"查询失败"）；④ 客供行 `SourceCost.unitPrice`/`RawMaterialDetail.unitPrice` 保留原价（今天零消费方）。

**#581 短交两路 + 显式确认**：终审阻断——prod `sales_orders` 的 `ck_so_status` CHECK 未扩 SHORT_CLOSED，部署全绿但一点结案就 500（H2 无此约束所以 23 文件全绿）；另 CI `BusinessException409HintCoverageContractTest` 红（新 409 缺 `.withHint`）。两条在修，落地发 31。

**worktree 清理**（Opus 子代理）：72 → 39，主仓 `web-admin/node_modules` 前后均 251，junction 0 指向仓库。摘 32（19 个 PR MERGED + 11 detached 在 origin/main 上 + 2 内容已进 main），删 19 条本地分支。4 个目录被活进程占着句柄未删（⛔ 未升级强删），7 个停在本地 main 的空树按「拿不准就留」保留。⚠️ 与本次无关的旧账：主仓 `frontend/CretasFoodTrace/node_modules` 自 08-31 起为空。

### 📌 2026-09-05 02:02 增量（系统时钟）· 四条登记项清零 + 两条衍生, 发 32/33/34

**起点**：owner 裁定「剩下四条登记不修的，全做完」。派三条独立线并行（成本 / 财务 / 安全），衍生出两票。

| PR | 内容 | 发布 |
|---|---|---|
| #586 | `DataScopeResolver` 认不出的 role_code → `default: ALL` 补成 fail-closed `SELF`，节流 WARN 复用同一套 | 发 32 |
| #587 | 结算去重从「批次级、数量盲」改为 `contribution = max(0, settlementQty − consumedQty)`，钳零；客供 `unitPrice` 保留原价的不变式钉死 | 发 32 |
| #588 | **AR 收款核销路径建出来**（仿 `PayableSettlementServiceImpl`，但 AR 无指定发票入口 ⇒ 按 SO 内 FIFO 分摊 + `PESSIMISTIC_WRITE`）；账龄表 CUSTOMER 侧翻成读 outstanding | 发 32 |
| #589 | `role_definitions.unactivated.data_scope` `ALL → SELF`（迁移 `_31`）——DB 行优先于 switch，#586 那个 case 在 prod 永不触发 | 发 33 |
| #590 | material-batches 读端点加 `@RequirePermission`；RN `Promise.all → settleAllIndependently` | 发 34 |

**发布判据**：三次均 `DEPLOY_EXIT=0` + `RELEASE_FINAL_STATUS` 恰 1 次 + jar mtime < 进程启动 + 标记命中 + 假串 0 + health 200。发 33 另在库里核到 `_31` installed(23:46:36) 且 `unactivated=SELF` / `factory_super_admin=ALL`（阴性对照）；发 34 用 **javap 按方法块**核注解（⚠️ `strings` 数不出来——常量池对同类内重复字符串去重，改动前后都是 1）。

🔴 **#590 拆票，客户列表的门撤回 = 登记不改**。实测两条路都有真代价：加 `sales|finance` 门会挡住 `production_manager`（20 个计划里 **7 个挂客户**，全是 CUSTOMER_ORDER 来源）；给它开 `sales:read` 会连带打开 **17 个控制器 / 49 个端点**（含 ArAp、Invoice、PaymentRequest、PriceList）。⇒ **产品决策，待 owner 裁定**。当前状态：该端点对同厂任何已登录用户开放（Bug #318 的有意决定），而 L1 里 hr_admin / equipment_admin / quality_inspector / quality_manager / procurement_manager 对 sales 与 finance 均无权却读得到全厂 29 条客户。

⚠️ **本轮新增登记不修**：① `material_consumptions` 无 unit 列、单位隐含跟批次走，prod 106 行两侧一致但**无约束保障**；② 同一批次可能以两个价进成本（消耗段 `mc.unit_price`、超出段 `mb.unit_price`，prod 370 行里 14 行不同）；③ 既有两条「不并入」断言分辨不了「整行丢弃 vs 并入 0」，该区别只由 #587 新增的 excess 测试守着 ⛔ 别删；④ AR `>5 分钟无 paymentReference` 的重放挡不住，而 #588 抬高了它的代价（会把发票推到 PAID）；⑤ `ar_ap_payment_allocations` 的列名/FK 名仍叫 `payable_*` 但已装 AR（命名债）；⑥ **`viewer`(level 50) seed 为 ALL，比 level 30 的一线更宽** —— 调查结论是 working-as-designed（除 system 外全模块只读、排除在价格与财审可见之外、prod 仅 2 个观察者账号），保持不动。

🔴 **两条方法学教训**：
1. **权威源分叉**（形态 D）：`PermissionServiceImpl.PERMISSION_MATRIX` 说 `finance_manager` 有 `sales:read`，而权威的 L1 `platform_role_permissions` 是 `-`。⛔ 估权限影响面一律查 L1，不看代码矩阵。
2. **数据修复不能用代码测试守**：#589 改的是 seed 行，而它的变异打在 resolver 代码上——seed 被改回 `ALL` 那些单测一条都不会红。补了源码契约测试（`_31` 置 SELF + 无更高版本把它设回 ALL）。⚠️ 优先方案 testcontainers 被否：CI 现路由到自建 Mac mini，**那台没有 Docker**，照做会让整道闸起不来。
3. **`_30` 作废勿用**（`spring.flyway.out-of-order=false`，将来建 `_30` 相对已应用的 `_31` 会被拒）。

**CI 侧**：自建 runner 出过一次 `ENOENT .git/objects/e5` 的 checkout 失败，重跑即过。⚠️ 重跑后立刻读 `gh pr checks` 会看到**行数变少的假绿**（重跑的 job 尚未登记）——判据加了「行数 ≥ N 且 pending=0」。

### 📌 2026-09-05 12:09 增量（系统时钟）· OTA 送达链断了三天 —— 证书续了但没推给 CDN

**发现路径**：02:03 推完 OTA，按「回执 ≠ 送达」去包里找标记，下载被 `SEC_E_CERT_EXPIRED` 拦下。⇒ **不是下载失败，是线上证书过期。**

```
dl.cretaceousfuture.com    notAfter Sep  2 02:12:18 2026 GMT   ← 已过期(发现时 9-4 18:04 UTC)
ota.cretaceousfuture.com   notAfter Nov  7 2026                ← 好的
```

🔴 **两个域名证书状态不同，正是它藏得住的原因**：`updates.url` 指 **ota.**（有效）⇒ 清单 200、`push-bundle.sh` 六步回执全绿；而 `launchAsset` 与全部 assets 指 **dl.**（过期）⇒ 安卓 TLS 握手失败 ⇒ 零送达。

**受影响面三条**（同一张证书）：OTA 包、OTA 素材首拉、**APK 直链**（`MobileBusinessServiceImpl:80` 的 `app.version.android.download-base` 默认就是该域且 prod 未覆盖 ⇒ 新设备装不上）。⚠️ **运行时素材不在内**（已验伪）：RN 源码只在一个测试文件里引用该域，上传走 OSS 另一域，prod 三张素材表的 http URL 合计 0 条（阳性对照：`batch_evidence_photos` 本身 0 行、`payment_records` 55 行但 0 行存 URL）。

🔴 **根因订正（我最初判断是错的）**：我在 **47** 上查「有没有续期机制」，查不到就报「6-04 手工签、从没续过」。**acme.sh 在 139 上**，`dns_ali` DNS-01、`0 19 * * *` 天天跑、管 8 个域名，全部正常续 —— **9-01 就签好一张 11-30 到期的证书，在磁盘上躺了四天**。断的是域名 conf 里 `Le_DeployHook` 为空，**续完没推给 CDN**。
▎**判据没错，量错了机器。** 且「有续期」与「续期结果送到了」是两件事，与「回执 ≠ 送达」同构 —— 判据要落在**客户端看到的那张证书的到期日**。

**已修（f006 执行，owner 授权；我独立复核）**：`dl.` 与 `download-cdn.vitaglobal.icu` 各补 `--deploy-hook ali_cdn` + `DEPLOY_ALI_CDN_DOMAIN`（后者线上 serial == 磁盘 serial ⇒ 7-29 那次是手工推的，10-27 必然重演）；`admin.` 补 `Le_ReloadCmd='nginx -s reload'`（此前靠**搭邻居 reload 的便车**，偶然不是设计）。**新闸** `/root/check-live-certs.sh` 两台每天跑，量**客户端实际看到的证书剩余天数**而非「acme 跑没跑」，域名清单从 `acme.sh --list` 的 Main+SAN **推导**（新域名自动进，`dl.` 正是作为 `download.` 的 SAN 被覆盖）；三态退出码**三态各构造过一次**，两条 rc=2 措辞互不相同，另有 ledger 每次必追加一行作「必发生」对照。
✅ **复核**：闸文件在、cron `30 21` 排在 acme `0 19` 之后、`Le_DeployHook='ali_cdn,'` + `SAVED_DEPLOY_ALI_CDN_DOMAIN` 存住、`admin` 的 reload 已补。
⚠️ **已知盲区（不藏）**：清单只覆盖 acme.sh 管的域名；手工传 CDN 或别处签发的证书不在内。

**恢复核验**（11:39，不加 `-k`）：证书 `Sep 1 → Nov 30`、SAN 含 `dl.`；包 `http=200 size=6,725,456 ssl_verify=0`；APK `http=206 ssl_verify=0`。⇒ **02:03 那次推送本身是成功的，不用重推**，清单仍指同一包（`/production/1788544899864/`）。

**包内容也证到了**（此前被证书挡着做不了）：⚠️ `.hbc` 是 **gzip 压过的**（头 `1f8b`），解压前 grep **全 0、含阳性对照** —— 是阳性对照跟着归零才认出仪器坏，否则会得出「包里没有我的改动」这个**反向**结论。`gunzip` 后 15,259,839 字节、头 `c61fbc03`=Hermes：`SHORT_CLOSED` 1 / `settleAllIndependently` 1（#581/#590 都在）、假串 0、阳性 9 与 32。
⛔ **最后一跳仍未证**：设备真去拉并应用（`checkAutomatically: NEVER`，App 只在代码显式调用时查更新）⇒ 需真机开一次，看短交结案显示中文而非 `SHORT_CLOSED` 字面。已转 owner。

📌 **本轮方法学**：① 判据没错也可能量错机器；② 有机制 ≠ 结果送到了；③ **扫描条数与已知总数对不上时先怀疑仪器**（f006 按目录名找 conf，acme 的 EC 目录带 `_ecc` 后缀 ⇒ 8 个漏 7 个，是「只印 1 行」这个数不对才认出来）；④ 阳性对照的价值在**它跟着一起归零**的那一刻。
