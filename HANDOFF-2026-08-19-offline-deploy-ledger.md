# 离线部署台账 —— 2026-08-19（GitHub 仍 403）

**为什么要有这份**：GitHub 账号停用期间，prod 上跑的东西在 GitHub 上**查不到**。
每次离线部署都必须记：部署了哪个本地 commit、含哪些分支、DB 侧跑了哪些迁移。
恢复后逐条核销。协议见 `.claude/rules/worktree-and-main-only-deploy.md` 的「🔌 GitHub 不可用时」。

**上一份**：`HANDOFF-2026-08-18-github-suspended-offline-ledger.md`（那次**没有**离线部署）。
**本次有**。

---

## 一 · 本次部署了什么

| | |
|---|---|
| 部署时间 | 2026-08-19 14:19:48 → 14:24:51（总耗时 303s） |
| 环境 | prod，蓝绿：active 从 blue(10010) 切到 **green(10020)** |
| 部署树 | `../cretas-deploy-offline`（detached，`SKIP_GIT_CHECK=1`） |
| 本地 main HEAD | `5766ab9e04`（含本次改动 + 另一条线的 restaurant-ai commit） |
| **本次改动的 commit** | **`76ffe5fa72`**（fix）+ `63b27c2199`（docs），merge 点 **`c5329830cb`** |
| 分支 | `codex/claude-softdel-uniq`（10 个文件） |
| jar MD5 | `f8d6929819b93deaa2b10371b6b1f00f` |
| jar mtime / 进程启动 | 14:22:43 / **14:23:15**（进程晚于制品 ✅） |
| **DB 迁移** | **无** —— 本次不含任何 Flyway 迁移（Flyway 头仍是 `20261030.04`） |
| web-admin | **未部署**（本次只动 Java） |
| Python | **未部署**（本次未动） |

⚠️ `origin/main` 冻结在 `f635e6b65e`，与本地 main 差 **140 个 commit**。
⇒ **prod 上跑的东西比 `origin/main` 新** —— 任何人拿 `origin/main` 判断 prod 现状都会判错。

## 二 · 改了什么（一句话版，详见 `HANDOFF-2026-08-19-prod-error-census.md`）

prod 报错普查修的 4 个还在发生的 bug + 2 道闸：

1. `uk_voucher_factory_number` —— 号段 JPQL 被类级 `@Where(deleted_at IS NULL)` 静默过滤 → 改 `nativeQuery=true`
2. `uk_cache_key` —— 同源；缓存写路径改用跨软删的 native 查询 + 复活软删行
3. FK `created_by=1` —— 写死的 user id 随 F001 租户清理失效 → 改用事件自带的 `approvedBy`，为 null 显式失败
4. 四处 `@Async` 监听器丢掉 `save()` 的 merge 返回值 → 全部接住
5. 闸：`SoftDeleteBlindSpotContractTest` / `StaleDetachedMergeContractTest`（都做过变异）

## 三 · 部署验证留痕（硬约束 2：找到标记 ≠ 它能跑）

### 部署前闸

`FlywayVersionUniquenessTest` + 36 个 `*RepositoryQueryValidationTest` + 2 个新闸
= **38 个测试类 / 62 个测试全绿**。
（那 36 个会起 Spring 上下文逐条校验 repository 查询 —— 正是能抓住坏 native query 的那道。）

### 运行中的 jar 里的标记

```
① FROM vouchers v WHERE v.factory_id                     3     (VoucherRepository.class)
② findIncludingDeletedByFactoryIdAndCacheKey             1     (SmartBiAnalysisCacheRepository.class)
③ saveAndFlush                                            1     (SupplyChainOrchestrator.class)
   交叉对照: 旧 jar(.bak.20260819_142307) 的 saveAndFlush  0
   阳性对照: 两个 jar 都读得到 createProductionPlanFromSO  2
   阴性对照: ZZZ_NOT_A_REAL_MARKER                        0
```

⚠️ **第一次验 ③ 读出 0，是仪器坏了不是部署失败** —— 我用中文串当标记，
而 `strings` 抽不出 class 常量池里的多字节中文（阳性对照里连「自动创建生产计划」
这种 prod 日志打过 28 次的串都读 0）。**二进制制品的标记一律选 ASCII。**

### 真跑了一次（决定性）

调 `GET /api/mobile/F006/smart-bi/dashboard/executive?period=month` → **HTTP 200 / success=true**
（修复前这个端点正是抛 `DataIntegrityViolationException` 的那一个）。

库里那一行的变化：

```
修复前   F006 | dashboard:month | deleted_at=2026-07-15 00:24:08
修复后   F006 | dashboard:month | deleted_at=NULL | updated_at=2026-08-19 14:30:22
表总行数 5 → 5   ⇒ 是 UPDATE 复活, 不是新插一行
阴性对照 同工厂另两行(dashboard_llm_insights:month / dashboard:year) 仍是软删 ⇒ 只有我触发的那行变了
```

### 只算不写的验证（凭证号）

```sql
新代码会发的号  V-2026-0018   -- 不冲突
旧代码会发的号  V-2026-0014   -- 被一条软删的 POSTED 占着, 必撞
```

### ⛔ 没有验的（诚实登记）

- **③ FK `created_by`** 和 **④ stale-merge** 只验到「标记在运行中的 jar 里」，
  **没有真跑** —— 它们各自需要在 prod 上做一次真实财审 / 开一张真实凭证。
  ⛔ 不为了验证而造业务单据。
- 重启后 6 分钟窗口（阳性对照 1074 行，非空）内四个签名全 0，但**窗口太短**，说明不了多少。

---

# 第二轮部署（同日 17:21–17:36）—— 三处全部部署

| | |
|---|---|
| 本地 main HEAD | `268ee2b799` |
| 后端 | ✅ v20260819_172115，MD5 `856742881fcb65f87d98aba0307ab282`，271s，蓝绿切回 **blue(10010)** |
| **DB 迁移** | 🔴 **有** —— `V20261030_05__async_task_failure_ledger.sql`（新建 `async_task_failures` 表） |
| web-admin | ✅ 139.196.165.140，`index.html` mtime **17:29:12**，上传校验 8746660B match |
| RN bundle | ✅ `rv=1.0.4 channel=production ts=1787131948572`，119s |

## 部署前的组合验证（各自绿 ≠ 合起来绿，今天证过）

```
Java   *ContractTest + Flyway + RepositoryQueryValidation   867 tests / 0 failures
web    vue-tsc -b                                            exit 0 / 0 行
web    vitest run                                            398 files / 3249 passed
RN     tsc --noEmit   (阳性对照 --listFiles = 1160 个文件)     exit 0
RN     jest --maxWorkers=4                                   138 套件 / 1524 tests 全过
```

⚠️ 合并四条线时组合**红过一次**（两个 TS 错），修完才继续 —— 见 `6ab76c79e1`。

## 验证留痕

**后端**（进程 17:24:14 晚于 jar 17:23:46 ✅）：

```
SellableLineDomainResolver.class            NEW=1  OLD=0
AsyncTaskFailure{Ledger,Controller,Scheduler} 5 个 class 全在
SHORTFALL 缺料哨兵                           NEW=0  OLD=1    ← 反向标记
REVERSAL_SUPERSEDED                          NEW=1  OLD=0
PurchasePriceDerivation                      NEW=1  OLD=0
阳性对照 submitReversal                       NEW=4  OLD=4
阴性对照                                      0      0
Flyway 20261030.05 success=true @17:24:24;  表 async_task_failures 存在 YES
   (阳性对照: _04 也查得到)
业务接口 10010 → HTTP 200
```

⚠️ 中途有两个标记读出 **0，而它们的阳性对照也是 0** ⇒ 是我写错了包路径
（`service/reversal/impl/` 不是 `service/impl/`；`service/supplier/` 不是 `service/purchase/`）。
**没有阳性对照的话，我会把这两条报成「修复没上线」。**

**web-admin**（扫了 1920 个 js 文件）：

```
参考单价 (新增列)                 5
下方为空不代表这单没有收款 (新增)   1
不良品区 (本轮改的文案)            3
确认创建采购单 (已删组件) →        0    ← 反向标记
阳性对照 合并采购 5 / 销售订单 188      阴性对照 0
```

**RN / OTA 四步验收 —— ⚠️ 只过了三步**：

```
① 推送成功        ✓ bundle live rv=1.0.4 ts=1787131948572
② manifest 发新的  含本次时间戳(1) / runtimeVersion 1.0.4 / 阴性对照 0
④ 内容里有改动     Hermes .hbc 里 PENDING_FINANCE_REVIEW=1 / FINANCE_APPROVED=1
                  / WORKFLOW_RUNNING=1 (本轮 RN 新加的三个状态键)
                  阳性对照 factoryId=11  阴性对照 0
                  ⚠️ 标记必须用 ASCII —— Hermes 字节码不以 UTF-8 直存中文
③ 设备真的来拉      🔴 **未验** —— 刚推完, 设备下次启动才来问
```

🔴 **③ 正是「九天零送达」那次失败的那一步，而每次推送都报成功。**
⇒ **过一段时间必须回头查 `OTA_PULL` 埋点**，确认设备侧 `current` 真的变了。
在那之前只能说「推上去了 / manifest 认了 / 内容对」，**不能说「送达了」**。

四个版本字段部署前已核对**全部是 1.0.4**（`app.json version` / `runtimeVersion` /
原生 `expo_runtime_version` / `versionName`），并有 `nativeVersionConsistencyContract` 闸钉着
（本轮 jest 138 套件里包含它）。

---

## 四 · 恢复后要做的（逐条核销）

```bash
# ① 本次这条分支走正常 PR (它含 backend 代码, 按「合入通道双轨」必须走 PR)
git push -u origin codex/claude-softdel-uniq && gh pr create ...

# ② 确认 prod 上跑的 = PR 合并后的 main
#    ⚠️ 本次是从本地 main 部的, 恢复后要重新对账一次

# ⛔ 不要 git push origin main —— 那会把 140 个 commit 绕过 CI 一次性推上去
```

⚠️ 上一份台账里登记的那些分支（`codex/claude-attribution-steps` /
`codex/claude-dish-catalogue` / `codex/claude-followup-capability` /
`codex/claude-false-alarm-census`）**同样还欠着**，一并核销。

## 五 · 本次部署顺带观察到的两件事（未处理）

1. **部署脚本在服务器端把一个目录列表当命令执行了** —— 日志里刷了 60+ 行
   `bash: line N: <文件名>: command not found`（`生产录入bug.md` / `web-admin` / `package.json` …）。
   像是某处有个没加引号的变量展开。**不影响本次部署**，但它把真实报错淹在噪声里。
2. **`nginx: [emerg] bind() to 0.0.0.0:{80,8088,8089} failed (Address already in use)`** 刷了 5 轮，
   随后 `nginx: [emerg] still could not bind()`。而 upstream 切换**是成功的**。
   ⇒ 大概是某个 `nginx -t` 之类的探测跑在了错的位置。同样是噪声，同样会掩盖真问题。

⚠️ 这两条都属于「**日志在撒谎/在制造噪声**」那一族 —— 与本次修的
「`自动创建生产计划` 打了 28 次而库里 0 行」是同一个形状。登记，未修。

---

# 第三轮部署（同日 19:03–19:17）—— 四处（首次含 Python）

| | |
|---|---|
| 后端 | ✅ 进程 19:03:39 晚于 jar 19:03:08 |
| **Python** | ✅ **本轮首次** —— `deploy-smartbi-python.sh --env prod`，systemd 重启，健康检查 19:12:47 通过 |
| web-admin | ✅ `index.html` 19:13:58，上传校验 8750946B match |
| RN bundle | ✅ **两棵树**：1.0.4 `ts=1787138100382` / 1.0.3 `ts=1787138193504` |
| DB 迁移 | 无 |

## ⚠️ 差点丢掉的东西

三条 agent 的成果**都没有 commit**，只是工作区里的未提交改动（9 + 13 + 10 = 32 个文件）。
第一次 `git merge` 得到「Already up to date」、分支显示 **0 commits ahead**，
**而 grep 标记全是 0**。

▎**推论和内容不一致时，信内容。** 已就地提交后再合入。
⚠️ 这是同一天**第二次**遇到这个形状 —— 上一次（AI 画布那个编辑）真的丢了。

## 部署前组合验证（四栈）

```
Java        *ContractTest + Flyway + RepositoryQueryValidation   896 / 0 failures
web-admin   vue-tsc -b 0 行  +  vitest 401 files / 3298 passed
RN          tsc 0 行  +  jest 139 套件 / 1550 passed
Python      131 passed                                    ← 本轮新增的那一栈
```

## 为什么这轮要多部署一处

改了 `food_kb/api/manual_chat.py`，而 **Java/web-admin 的脚本不发 Python**。
动手前先确认了 `deploy-smartbi-python.sh` 到底同步什么：它 rsync 整个 `backend/python`，
exclude 列表只有 `__pycache__`/`*.pyc`/`.env`/日志/`venv*` —— **`food_kb/` 不在里面** ⇒ 会带上。
服务器 8083 进程的 `cwd` 实测是 `/www/wwwroot/cretas/code/backend/python`，对得上。

⚠️ 本仓有过「所有判据全绿而 Python 改动根本没上线」的记录，所以这一步不能靠记得。

## 验证留痕

**后端**（新旧 jar 交叉对照）：
```
fillWorkProcessCatalogParams  NEW=2  OLD=0     salespersonSnapshotOf  NEW=1  OLD=0
legalValuesHint               NEW=1  OLD=0
阳性对照 canvas               NEW=24 OLD=24     阴性对照 0/0
```

**Python**：文件 mtime 18:58:58；`def _estimate_max_tokens(depth: SopDepth)` ——
**depth 现在是参数本身**；三档 1200/2000/3000 都在；`:8083/health` = 200。

**web-admin**（扫 2287 个 js + css）：
```
cretas-cursor-col-resize 1(新)   _returnScroll 1(新)   horizontalScrollbarWheel 1(新)
阳性对照 cretas-cursor-crosshair 4(8-05 就有) / el-scrollbar__bar 11 / 合并采购 6
阴性对照 0
```
⚠️ 第一次这三个都读出 **0，而阳性对照也是 0** ⇒ 是我的 `grep -F "--cretas..."`
把开头的 `--` 当成了选项。加 `--` 终止选项解析后才是真读数。

**OTA 两棵树**：1.0.4 manifest `createdAt 11:16:20Z`／1.0.3 `11:17:49Z`；
Hermes 字节码里 `specification` 5、`boxQuantity` 2（本轮 App 补的字段），
阳性对照 `factoryId` 10、阴性对照 0。
⚠️ `salespersonSnapshot` 读 0 是**对的** —— 那是后端符号，不该出现在 RN bundle 里。

## 🔴 仍未验的那一步（同上一轮）

**「设备真的拉到了」** —— 那正是「九天零送达」失败的那一步，而每次推送都报成功。
⇒ 过一段时间查 `python-prod.log` 的 `OTA_PULL`，看 5 台 1.0.3 设备的 `current` 有没有真的变。

---

# 第四轮部署（同日 23:00–23:04）—— 只动 Java

| | |
|---|---|
| 部署树 | `../cretas-deploy-offline`（detached，`SKIP_GIT_CHECK=1`） |
| **部署的本地 commit** | **`d556bc9fc3`**（merge: 生产完成链路带上「谁干的」+ 闸补上位置参数那一档） |
| 含哪些分支 | `codex/claude-hardcoded-actor-0l` 3 个 commit：`a59f2985e1` / `97289f8c86` / `4a8f…`(Canvas+文档) |
| 合并基点 | main 在我开分支后被另一条线推进了 4 个 commit（全是 `backend/python` 餐饮，与本轮 Java 改动**零交集**） |
| jar MD5 | `dc203f9765d28b5cc797d2672c03c414` |
| jar mtime / 进程启动 | 23:00:01 / **23:00:32**（进程晚于制品 ✅） |
| DB 迁移 | **无**（`FlywayVersionUniquenessTest` 在合并树上通过） |
| web-admin / Python / RN | **未部署**（本轮只动 Java） |

## 部署前（在合并树上跑，不是各自的分支上）

```
FlywayVersionUniquenessTest              1 passed   ← 合并后、部署前的撞号闸
HardcodedActorIdContractTest             4 passed
ProductionCompletedActorPropagationTest  7 passed
CanvasToolActorGuardTest                 6 passed
ReverseTransferServiceTest              11 passed
ProductionPlanBatchCompletionChainTest   5 passed
```

**与干净 main 的回归比对（同口径，这一点是关键）**：
41 个既有失败类，两侧都是 `343 tests / 59 failures / 65 errors`，
逐条方法名 `comm` 比对 **0 差异** ⇒ 本轮没有引入任何新失败。

⚠️ 第一次比对多出 1 条（`ProductionPlanRepositoryQueryValidationTest`），
那是我拿**全量跑的分支**去比**只跑 41 类的基线** —— 口径不同。
差异来自 H2 里 `restock-fixture-user` 的主键撞车，是执行顺序敏感的既有脆弱性，与改动无关。
⇒ **同口径重跑后 0 差异。**

## 部署后判据（硬约束 2：找到标记 ≠ 它能跑）

**① 运行中的 jar 里的标记（ASCII，新旧交叉对照）**

```
标记                新 jar   旧 jar(.bak.20260819_230026)
completedBy           1        0
resolveBatchActor     1        0
requireUserId         1        0
阳性对照 createProductionPlanFromSO   3        3   ← 证明 strings/grep 仪器是活的
```

**② 真跑一次那条路径**（改动给「完成生产」加了必填 `Authorization`，怕把接口改坏）

```
POST /api/mobile/F006/production-plans/PROBE-NONEXISTENT/complete
  不带 Authorization      → 401 {"message":"未授权，请先登录"}
  带一个无效 token        → 401 同上
```

⇒ 走的是鉴权拦截器的业务 401，**不是** Spring 的「缺少请求头」400 ⇒ 接口没被改坏。

⚠️ 这一步我第一次是从**本机** curl 的，拿到 `HTTP=000`，而且 `-o` 没覆盖旧文件，
打出来的是上一轮遗留的 Java import 和 pytest 结果。
**阳性对照也是 000** 才认出是本机网络不通、不是接口坏 —— 改从服务器内部打才是真读数。

## 本轮未处理（需 owner 定）

prod `internal_transfers` 里 2 行 `requested_by = 0`
（`TRF-20260816-5192` / `TRF-20260817-9377`，F006，DRAFT）。
**prod 库只读，没动。** 建议置为 NULL：界面表现一样，但 `0` 会被误读成一个真实用户 id。
