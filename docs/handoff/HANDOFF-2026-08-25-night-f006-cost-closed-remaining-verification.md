# F006 成本链已闭环 / 剩余 7 项待验证 —— 交接件

**编写时间**：2026-08-25 夜
**上一轮执行者**：Claude Code（本文件由它自己写）
**交接对象**：新会话
**性质**：状态交接 + **已排除的错误路径**清单；不是任何未验证项已通过的证明

---

## 0. 先读这一条

**本轮最有价值的产出不是代码，是「哪些路是死路」。** 第 4 章逐条写了我走过又被实测否掉的判断。
下一个会话若不读它，很可能重走一遍今天的弯路 —— 我自己在同一晚就重复踩过三次本仓明文记载的坑。

---

## 1. 当前真值（开工前必须自己重新核验，不要采信本文件）

```bash
git fetch origin
git rev-parse origin/main                 # 本文件写作时: 2a585cf5681b7f89ce2a1ffa9a7ddedab727840c
git worktree list --porcelain
git status --porcelain                    # 主工作区有大量其他 Owner 改动, 严禁改动
MSYS_NO_PATHCONV=1 git show origin/main:docs/dispatch/ACTIVE.md
```

| 项 | 本文件写作时的观察 | 接手要求 |
|---|---|---|
| `origin/main` | `2a585cf5681b7f89ce2a1ffa9a7ddedab727840c` | **重新 fetch**，实际值优先 |
| 生产 active 槽 | `10020` / `cretas-backend-green` | 蓝绿会交替，**每次实时读 upstream** |
| 进程 | PID `2331770`，启动 `2026-08-25 20:33:42` | 仅历史基线 |
| JAR mtime | `2026-08-25 20:33:19` | 同上 |
| 主工作区 | `C:\Users\Steve\my-prototype-logistics`，脏 | **禁止** reset/stash/clean/覆盖 |

⚠️ **CI job 现在是绿的**（`985 tests / 0 failures`）。若你看到红，先查是不是 `Attest deploy artifact provenance`
——那一步已被降级为 `continue-on-error`，理论上不该再让 job 红。

---

## 2. 本轮已闭环（不要重做）

21 个 PR 合入、13 次生产部署。**成本链路上已无已知代码缺陷。**

### 2.1 成本链（P0，交接件原本的主线）

| PR | 做了什么 | 生产实测 |
|---|---|---|
| #58 | 算不出成本时说出**真实**原因（原提示是假的） | 原料成本 ¥40.32 全 COLLECTED，那句「没归集到原料领用成本」是错的 |
| #60 | 人工「明细不全」≠「金额未知」拆开 | `amount=26.00` 有值，只缺工时段 |
| #63 | 成品必须有包材（owner 裁定） | 13 个 ACTIVE BOM 全是成品，只有 3 个配了包材 |
| #65 | 辅料一律选填 + 发布前提示（owner 裁定） | 原切类成品 50 个，一刀切会全挡住 |
| #72 | **带完价必须重算 `item_cost`** ← 真正的根因 | 8 行可算里 5 行不一致（62.5%） |
| #74 | pinned BOM 快照缺失时回退 `bom_recipes` | `bom_versions` 全库 9 行、最近写入停在 08-02 |
| #77 | 撤掉我自己引入的回归（回退丢缺项） | `0.0/complete=true` → `null/false` |
| #78 | 已完工批次全 0 成本判「没归集」 | 同上，生产实测确认 |

**结果**：`totalCost` 非空 **2/23 → 16/23**；交接件点名的
`PB-PLAN-1787622818112-B3898275-15036` 已算出 `totalCost=93.12 / perBoxCost=9.31`
（= 原料 40.32 + 人工 51.57 + 包材 1.23，逐项对得上）。

**F006 存量 `item_cost` 已重算**（一次性，事务内，前后对账）：不一致 `3 → 0`。
⛔ **LIUSHANMEN 那 2 行没动** —— 真实客户租户，无写入授权，原样留给 owner 裁决。

### 2.2 其他

- **客户必填规则**：不需要裁定，四个面（DTO / Web 表单 / Excel 导入 / 销售准入）本就一致，
  台账里那句「冲突」是**过期描述**。已加 `CustomerRequiredFieldsContractTest` 钉住。
- **CI 三条存量红线**（#66，由 sub agent 完成，我独立核实过 diff 无放宽棘轮）：
  裸单位比较是**真缺陷**（静默丢量）、基线留空档、闸钉错范围。`949/3` → `985/0`。
- **CI attestation**（#68）：Free 组织不支持私有仓 attestation（只有 Enterprise Cloud `$21/人/月`），
  且它失败会**连累制品上传**。已降级为可选，**制品恢复上传**（169MB 实测）。
- **安卓真机**：小米 M2102K1AC / Android 14，APK `1.0.4`/`15`，OTA 通道可达，
  真实登录 `f006_admin`（`factoryId=F006`），生产进度页真实数据。
  顺带修掉三个互不相干的版本号（真实 1.0.4 / 登录页写死 1.0.0.1 / 关于页写死 1.0.0，#64）。

---

## 3. 剩余 7 项（本轮**完全未动**）

全部保留在 `docs/dispatch/ACTIVE.md`：

1. `ARCH-F006-DEMAND-DRIVEN-MATERIAL-RECEIPT-001` —— 需求驱动收料
2. `ARCH-F006-UNIFIED-MATERIAL-RECEIPT-SOURCE-001` —— 统一来源矩阵
3. `BUG-F006-PURCHASE-AP-PAYMENT-BOUNDARY-001` —— 采购应付四维
4. `ENH-F006-SUPPLIER-IMPORT-001` —— 供应商导入
5. `ENH-F006-MATERIAL-TAXONOMY-SUGGESTION-001` —— 分类建议
6. `BUG-F006-R2-SKU-UNIT-001` —— SKU 单位
7. `FEATURE-F006-R2-WORKPROCESS-DEDUP-001` / `FEATURE-F006-R2-BOM-SUBSTITUTE-001` —— 工序去重 / 替代料

⚠️ 这些的现状描述**以 ACTIVE 为准，不要采信交接件原文** —— 本轮已证明台账里存在过期描述
（客户必填那条就是），**每一条都要自己先跑一次/查一次再下结论**。

### 3.1 已知的、与它们相关的实测事实（省你重查）

- **物料成本录入率低**：辅料 135 个仅 8 个有价（6%）、原料 119 个 20 个（17%）、包材 62 个 29 个（47%）。
  成本链代码已通，但数据侧补不齐就仍然算不全。
- **`InboundType` 现有取值**：`PURCHASE_ORDER` / `CUSTOMER_SUPPLIED` / `INVENTORY_COUNT` /
  `SUPPLIER_RETURN` / `FACTORY_RETURN` / `LEGACY_IMPORT` / `OTHER`。
  **缺 `TRANSFER` 和「生产退料」** —— 统一来源矩阵要补的是这两类。
- **BOM 架构**：`BomRecipe` 自带 `workflowRevisionId`/`workflowNodesSnapshotJson`/`bomFamilyId`/
  `targetTerminalNodeId`/`outputRole` —— 它**就是画布融合架构**，`bom_recipe_items` 是新的。
  老的是 `bom_versions`（ECN 手工流程，画布不写）。

---

## 4. 🔴 已排除的错误路径（本文件最有价值的一章）

### 4.1 关于成本，我走过又被实测否掉的判断

| # | 我的判断 | 实测否定它的证据 |
|---|---|---|
| 1 | 「两张表没接通，要重写归集」 | 读侧桥接 `settlementConsumptionsFor` **早就有**，原料成本一直归集得到 |
| 2 | 「BOM 没同步物料 SKU 的价」 | `bom_recipe_items.unit_price` 与 `raw_material_types.unit_price` **逐字一致** |
| 3 | 「`itemCost=0` 是没填」 | **填了**。是 `applyDtoToItem` 先算、`applyMaterialMasterPricing` 后带价且不重算 |
| 4 | 「BOM gate 能清掉剩余 7 条阻断」 | **清不掉**。那些批次实际包材领用行数 0、BOM 包材价也是 0 |
| 5 | 「全 0 时回退实际领用更好」 | **更糟**。回退丢缺项，从「诚实报缺」变成「报 0 且说完整」 |

**教训**：这条链上每一层看起来都像根因。**不要在第一个自洽的解释处停下**——
判据是「拿真实数据逐行对账」，不是「解释得通」。

### 4.2 仪器自身的坑（我今晚踩的，全部是本仓明文记载过的）

| 坑 | 症状 | 判据 |
|---|---|---|
| **`mv .bak` 带回旧 mtime** | Maven 跳过重编 → **跑的是变异后的字节码** → 「变异不红」被误读成「断言没用」 | 每次变异/恢复后 **`touch` 源文件** |
| **heredoc 吃转义** | `\\*` → `\*`，Java 报「非法字符」 | 含转义的内容**全程不经过 shell**，用 Write 落盘 |
| **squash 后在原分支追加** | `gh pr merge` 报冲突 | 合并后**立刻**从新 `origin/main` 开新分支 cherry-pick（我一晚踩 3 次） |
| **UTF-16 manifest 用 UTF-8 搜** | 全是 0，差点报「APK 不支持 OTA」的反向结论 | **任何 0 读数都要阳性对照**（搜一个必然存在的串） |
| **测试是恒真式** | 断言 `packagingCost=18.91`，变异前后都一样 | 实际领用是**叠加**不是替换 —— 断言前先确认自己量的是哪条路 |
| **并发发布锁** | 另一 session 的 Maven 在跑 | **禁止 `rm` 锁**；`Get-Process` 确认存活后等它 |

### 4.3 边界（不可越）

- **跨租户生产写入 = 0**。LIUSHANMEN 那 2 行不一致的 `item_cost` **不要顺手修** ——
  即使 owner 说「全部做完」，那也不包含其他租户的生产数据。
- `totalCost` 的 COGS 口径：**缺单价类必须继续阻断**
  （`RAW_MATERIAL_PRICE`/`PACKAGING_PRICE`/`SEASONING_PRICE`/`SEMI_FINISHED_OR_FINISHED_FEED_PRICE`/
  `LABOR_COST_UNKNOWN`/`PINNED_BOM_*`）。已由阴性断言钉住，**不要为了让数字好看而放宽**。
- 「不强制填写」的只有：`EQUIPMENT_COST` / `OTHER_COST` / `LABOR_TIME_DETAIL_MISSING`。

---

## 5. 剩余阻断的定性（都**不是**代码缺陷，别去"修"）

| 阻断码 | 数量 | 性质 |
|---|---|---|
| `PINNED_BOM_OUTPUT_BASIS_UNVERIFIABLE` | 5 | BOM 产出基准是「盒」而计划产出 0.04kg —— **真实数据不匹配**，诚实阻断正确 |
| `NO_COST_COLLECTED` | 若干 | 批次确实什么都没归集 —— 诚实阻断正确 |

要让它们算得出成本，动的是**数据**（补录工时/设备成本/统一产出单位），不是代码。

---

## 6. 验证要求（新会话的主线）

对剩余 7 项，**每一项**都要：

1. **先跑一次/查一次真实数据**再下结论 —— 台账描述可能过期（已有先例）
2. 目标测试 + **变异对照**（变异不红时先证明变异生效，再谈断言是否有用）
3. 触及 Entity/Repository/JPQL/Flyway → 真实 JPA Context 测试（本仓 `@DataJpaTest` + `test` profile）
4. 闸命名必须落在 CI 选择器里：`*ContractTest` / `*StartupGuardTest` / `*RepositoryQueryValidationTest`
5. 发布判据只看：`DEPLOY_EXIT=0` **且** `RELEASE_FINAL_STATUS` **恰好 1 次**
6. 部署后**真跑一次那条路径**并核对运行制品含本次改动的标记 —— 脚本 SUCCESS 不算

---

## 7. 未闭环 / 待 owner 裁决

1. **LIUSHANMEN 2 行 `item_cost` 不一致** —— 无授权，未动
2. **物料成本录入率**（辅料 6% / 原料 17% / 包材 47%）—— 要不要在物料 SKU 侧加「没录成本」提示？
3. **CI attestation** —— 现降级为可选。若要恢复防伪造能力需 Enterprise Cloud（`$21/人/月`，2 席位 ≈ `$504/年`）
4. **制品运输链路** `10.66.66.1:22` 不通 —— 制品已能产出但取不回来，prewarm 仍落空，
   每次发布多花约 180 秒。**这与 attestation 是两个独立问题**（我一度混为一谈，已订正）
5. **RN 版本号修复真机未验证** —— 需重新构建 APK 或推 OTA，本轮无发布授权

---

## 8. 证据位置

- 21 个 PR 的描述里逐条写了根因、判据、变异对照结果、**以及未验证项**
- 真机证据：`D:\Temp\claude\...\scratchpad\E2E-F006-ANDROID-20260825-1550\`
  （截图 + UI dump；session 级临时目录，**不进仓库**，需要长期保存请自行归档）
- 生产只读探针脚本：同目录 `probe_cost2.py`
