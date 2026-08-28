# 交接册 2026-08-29c — 交给 Codex 的剩余工作

**起点 commit**：`c309a70dcd`（origin/main，写这份时 behind=0）
**授权**：owner 已批准 **生产环境读写删** + **任意用户登录**。F006 是主战场。
**⛔ 唯一例外**：非 F006 租户（LIUSHANMEN、MOCK_REST、R_XMX_CHAIN、R_GML_DEMO 等）的**写入**仍需单独确认；读随便。

---

## 0. 先读这一段：这个仓的验收纪律

这个仓的规则文件（`.claude/rules/measurement-and-wiring.md`，1000+ 行）是从连续踩坑归纳出来的。
**不按它做，产出的读数会是假的。** 最常犯的五条，每条都在过去两天真实发生过：

| # | 纪律 | 长相 |
|---|---|---|
| 1 | **诊断输出落文件再查，禁止 `\| head/tail`** | 管道会**替换退出码**（`cmd \| tail` 的 rc 是 tail 的，永远 0） |
| 2 | **部署判据 = 在运行中的制品里 grep 到本次改动的标记** | `DEPLOY_EXIT=0` 不是判据 |
| 3 | **验中文标记用 `grep -a`，⛔ 不要经过 `strings`** | `strings` 只抽 ASCII ⇒ 中文恒读 0，长得和「没部署上去」一模一样（2026-08-29 实测栽过） |
| 4 | **每条正向断言配一条能让它红的对照** | 变异不红时先证明**变异真的生效了**，再说断言没用 |
| 5 | **验证「X 没发生」必须配一个「Y 一定会发生」的读数** | 崩在写日志之前，和「决定不喊」长得一样 |

还有两条工程纪律：

- **worktree 永远 off `origin/main`**，不在主工作目录干活；提交用 `./scripts/safe-commit.sh "msg" 文件...`
- ⛔ **禁止 `mklink /J` 共享 `node_modules`** —— Windows 上 `worktree remove` 会把 target 一起掏空。
  2026-08-29 扫出这是**第三次**发生（`cretas-warehouse-rn-symmetry-20260823` 的 node_modules 早已空了，没人发现）。
  worktree 里自己装：`npm install --prefer-offline --legacy-peer-deps`

---

## 1. 环境与凭证（都已验证可用）

### 生产

```
Java 后端    47.100.235.168:10010   活跃 systemd 单元 cretas-backend
             jar: /www/wwwroot/cretas/aims-0.0.1-SNAPSHOT.jar
Python       47.100.235.168:8083
web-admin    https://admin.cretaceousfuture.com
DB           ssh root@47.100.235.168 → su - postgres → psql -d cretas_prod_db
             ⚠️ 库名是 cretas_prod_db, 配置默认值 cretas_db 是**测试库**, 查错库栽过
             ⚠️ 密码里含 `/`, 手拼 DSN 一律失败 ⇒ 用 peer auth (su - postgres)
```

### F006 账号（密码统一 `123456`）

```
f006_admin            factory_super_admin   张六膳          ← 手机原本登录的就是它, 用完还原回去
f006_workshop         workshop_supervisor   车间主管
f006_operator1        operator              六膳门操作员     ← 报工
f006_warehouse_mgr    warehouse_manager     六膳门仓储主管
f006_warehouse_worker warehouse_worker      六膳门仓库员     ← 入库/盘点
f006_quality_insp     quality_inspector     六膳门质检员
f006_finance_mgr      finance_manager       六膳门财务主管   ← 盘点审批/生效大概率归它
f006_cashier / f006_dispatcher / f006_hr_admin / f006_viewer / f006_dept_admin
f006_equipment_admin / f006_procurement_mgr / f006_production_mgr / f006_quality_mgr / f006_sales_mgr
```

登录接口（可直接拿 token 做 API 验证）：

```bash
ssh root@47.100.235.168 'curl -s -X POST http://127.0.0.1:10010/api/mobile/auth/unified-login \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"f006_admin\",\"password\":\"123456\",\"deviceInfo\":{\"deviceId\":\"x\",\"deviceModel\":\"cli\",\"platform\":\"cli\",\"osVersion\":\"1\"}}"'
# token 在 data.token
```
⚠️ 从本机直接 curl `47.100.235.168:10010` 拿不到（HTTP 000），**在服务器上 curl 127.0.0.1**。

### 真机（小米 11 Pro / Android 14）

```
adb   C:\Users\Steve\AppData\Local\Android\Sdk\platform-tools\adb.exe   (不在 PATH)
包名  com.cretas.foodtrace   v1.0.4 / versionCode 15
```

⚠️ **Git Bash 下 adb 的设备路径会被转成 Windows 路径** ⇒ 一律 `export MSYS_NO_PATHCONV=1`
（否则 `adb shell uiautomator dump /sdcard/ui.xml` 会写到 `C:/Program Files/Git/sdcard/...`）。

我写过一个操作助手，放在 scratchpad（会话级，可能已清，逻辑很短可重写）：
按文字定位并点击、截图、输入。**关键坑记在下面第 5 节。**

---

## 2. 剩余工作（按建议顺序）

### ① 盘点提示措辞 —— 有明确设计，直接做

**现状（实测）**：真机上选原料仓发起盘点 → 409：

> 该仓库本月已有进行中的盘点任务，请完成或拒绝后再发起

**但拦住它的那条在盘点记录里显示为「已审批」**，工人按提示去找「进行中」的对不上号；
而「已审批待生效」他多半也无权处置 ——「请完成或拒绝」是一句**做不到**的指令。

**根因（已定位，不用再查）**：

```java
// FactoryStocktakeServiceImpl.java:186-194
long existing = stocktakeRepo.countActiveStocktakeForWarehouseAndMonth(
        factoryId, req.getWarehouseId(), periodMonth);
if (existing > 0) { throw new BusinessException(409, "该仓库本月已有进行中的盘点任务，请完成或拒绝后再发起") ... }
```
```sql
-- FactoryStocktakeRepository.java
AND s.warehouseId = :warehouseId AND s.periodMonth = :periodMonth
AND s.inventoryScope = :inventoryScope
AND s.status NOT IN ('APPLIED', 'REJECTED')
```

`APPROVED` 不在排除名单里 ⇒ 被算作「进行中」。**闸是对的，作用域也是对的，错的只是那句话。**

⚠️ 我曾据「DB 里唯一的 INITIATED 在成品仓」推断「闸是全厂级的」——**查完 SQL 发现是错的**。
**DB 读数只告诉你有哪些行，不告诉你闸怎么筛的。别推，去看那条 SQL。**

**建议改法**：
1. Repository 加一个和 count **同条件**的 finder（返回实体而不只是计数），
   两处必须同条件 —— 漂开的后果是「闸说有一张，提示却说不出是哪一张」。
2. 消息里点名单号 + 用**前端同一套中文标签**说它的真实状态，并按状态给出**做得到**的下一步：

| status | 前端标签（`WHStocktakeListScreen.tsx:51-56`） | 该说什么 |
|---|---|---|
| INITIATED | 已发起 · 未录入 | 请先完成录入并提交，或驳回它 |
| COUNTING | 盘点中 · 未提交 | 同上 |
| PENDING_APPROVAL | 待审批 | 正在等审批，请联系审批人 |
| **APPROVED** | **已审批** | **已审批待生效，生效后才能发起新的盘点** |

⛔ **中文状态标签现在只存在于前端**（后端只有枚举）—— 这是「同一个东西两份」。
   要么把标签下沉到后端并让前端引用，要么后端只回 `status` 码由前端渲染。
   **两种都行，但不要在后端再手写一份中文标签**（那就是第三份）。

**判据**：改完在真机上重现那个 409，读到的提示必须点名 `ST-202608-6B907387` 且状态写「已审批」。

### ② 走完盘点链（被 ① 那条记录挡着）

挡路的是 **`ST-202608-6B907387` / APPROVED / 原料仓(WH-RAW `6ce8414d-b5d6-466f-a4d3-bcbe687bfa7e`) / 2026-08**。

F006 全部盘点单现状（`factory_stocktakes`，⚠️ **不是** `stocktaking_records`，那张表 F006 是 0 行）：

```
ST-202608-2A4C1F02  APPROVED   78339e2d(物流仓)   08-28 02:29
ST-202608-C6EC572F  REJECTED   bbede96c           08-28 02:28
ST-202608-6B907387  APPROVED   6ce8414d(原料仓)   08-28 01:35   ← 挡路的就是它
ST-202608-C20566F3  APPLIED    6ce8414d           08-25 23:19
ST-202608-C2738A28  APPLIED    6ce8414d           08-25 00:35
ST-202608-92595F20  APPLIED    98a1fb3a(成品仓)   08-25 00:25
ST-202608-808767FC  APPLIED    98a1fb3a           08-25 00:11
ST-202608-ED55F497  INITIATED  98a1fb3a(成品仓)   08-24 23:00
ST-202608-112FA192  APPLIED    6ce8414d           08-20 14:01
```

**建议**：⛔ **不要直接改库把它设成 APPLIED** —— 那绕开了业务链，也验证不了任何东西。
正路是**用 `f006_finance_mgr` 走审批/生效那条界面路径**把它推到 APPLIED，
顺便验收那条路径本身（它现在没人走过）。走不通再考虑改数据，并写清楚为什么。

然后在真机上用 `f006_warehouse_worker`：发起原料仓盘点 → 逐品录入实盘 → 提交 → 核对落库。
⚠️ 盘点页自己写着「差异自动报财务审批，审批通过后库存才调整；您无法直接修改库存数量」——
**录入时故意造一个小差异**，才能验到那条审批链；全录成一致等于什么都没验。

### ③ 平面图文字标注支持缩放

`web-admin/src/views/warehouse/storage-locations/`。区域/货架能缩放，**文字标注字号是固定的** ⇒
图放大后字不跟着放大，缩小后字压在一起。

相关已有资产（别重复造）：
- `warehouseLayoutModel.ts` —— 模型与上限常量
- `WarehouseFloorOverlay.vue` + `WarehouseFloorOverlay.spec.ts` —— 交互层，已有 `interactive:false` 的变异测试
- `floorPlanLimitsMatchBackend.spec.ts` —— 前后端上限一致闸（#316）

⚠️ 加新常量时记得：`web-admin-gate.yml` 的 `paths:` 里**逐个文件列**后端依赖，
   ⛔ 不要加 `src/main/java/**` 宽 glob —— 会把元闸 `gateTriggerSurfaceCoversWhatItReads`
   自己「证明不过宽」的阴性对照变成恒真式（2026-08-28 实测踩过一次）。

### ④ 非管理员只读态实测

现在**只做到源码级**，没有真登录验过。四层都在：

```
组件   WarehouseFloorOverlay  interactive:false  (有变异测试)
页面   :interactive="canDesignMap"  = canEditLayout && mapFullscreen
逻辑   每个改动 handler 早返回 if (!canEditLayout.value) return
模板   每个编辑按钮 v-if="canEditLayout"
canEditLayout = ['factory_super_admin','warehouse_manager'].includes(auth.currentRole)
```

⇒ 用一个**能进这个屏但不在名单里**的角色登录 web-admin（`f006_quality_insp` / `f006_viewer` 试）：
确认看得到平面图、且没有编辑按钮、拖不动、有「只读视图：你的角色不能编辑平面图」那句提示。

⚠️ 我没做这一步的原因是**当时不想覆盖浏览器里 owner 的活会话**（本仓有过「注入的凭证覆盖了一个能用的会话」的事故）。
   现在授权允许，**但仍建议用无痕窗口或独立 profile**，别踩同一个坑。

### ⑤ 按钮/文案/流程视觉验收（最大的一块）

owner 的原话是「确保所有的按钮功能文字流程是合理的」。本仓这类走查**历史命中率很高**：
一次 145 屏走查揪出 50 条接口层看不见的缺陷；另一次 287 屏走查**自己翻车**了
（大量截到 SPA 启动闪屏，289 张只有 96 个不同图像，却报「280 干净」）。

⇒ **做之前先给自己定一条「这张图是不是真渲染了」的判据**，例如两轮截图体量比、
   或断言页面上必然出现的某个业务串。**没有这条判据的走查会系统性地报假绿。**

已知的、可直接复用的判据来源：
- `docs/visual-acceptance/` 下有历轮台账（含 `2026-08-28-rn-app-444-screens.md`）
- 已建的文案闸：内部编号/黑话不许出现在用户可见串（前后端各有一道，且**都用过代理判据**，注释里写明了看不见什么）

**本轮真机走查已经确认没问题的，不用重走**（见第 3 节）。

### ⑥ 六膳门 21 条空 `storage_type` —— ⛔ 别猜

2026-08-28 15:18:31 同一秒批量建的 21 条真实物料（2082上脑 / 和牛50 / 保水剂T22…），`storage_type` 全空。
后端已加创建校验（只拦 create，不碰 update，好让存量能被补），**但存量那 21 条要客户自己填「冻货 vs 常温」**。
⛔ 猜一个填进去 = 制造一份看起来可信的假数据。这条**不是 Codex 能关掉的**，等 owner 找客户。

---

## 3. 已经验过的，不要重复劳动

| 事 | 结论 | 证据 |
|---|---|---|
| **报工链**（投入→时段→完工） | **通** | `production_reports` 23890/23891/23892 三行与设备动作逐一对上；`work_process_tasks` COMPLETED / 0.0300 |
| 「点第②道打开第①道」 | **不复现** | 点第2道打开的是`定量包装` |
| 上下道衔接 | **通** | 第2道显示「上道半成品可领 0.03 kg」= 第1道产出 |
| **扫码入库链**（手动输入路径） | **通** | `RCV-20260829-7751` CONFIRMED；PO 转 `PARTIAL_RECEIVED`；库存页出现 1 kg；冷启后待收货 3→2 |
| 入库凭证闸 | **有效** | 无照片时拒绝提交 |
| 物料平衡守卫 | **有效且措辞准确** | 「投入 0.04kg, 产出 0.03kg, 偏差约 25%… 系统不阻塞」 |
| **OTA 送达** | **通，且有行为证据** | 同机同工序那句话从「可投上限约 0」→「0.052」 |
| 「可投上限约 0」缺陷 | **已修已上线** | PR #320，双变异各打红不同断言 |
| 储存类型后端校验 | **prod 上真跑过** | 空值→400／填了→201／包材豁免→201，测试数据已删(322→320) |
| 现场盘点页签回归 | **无回归** | 三个页签逐个读到正文 |
| 平面图上限前后端一致 | **有闸** | PR #316 |

⚠️ **两个我报错又自己推翻的判断**，别被旧结论误导：
1. ~~盘点闸作用域是全厂级~~ → **错**，是仓库级，真因在 `NOT IN ('APPLIED','REJECTED')`
2. ~~被拒后「发起盘点」按钮变哑~~ → **没能复现**，干净路径下每次都正常

---

## 4. OTA 收尾方式（RN 改动必做）

RN 改动合进 main **不等于送达**。本仓有过「九天零送达而每次推送四步验收全绿」。

```bash
source ~/.ota-env                       # 提供 OTA_ADMIN_TOKEN
./scripts/ota/push-bundle.sh production android
# 期望: bundle live: rv=1.0.4 channel=production ts=<millis>
```

然后**必须**在设备上取一条行为读数：

```
冷启第一次 → 下载 (DownloadComplete, isUpdatePending=true)
冷启第二次 → 生效
读行为     → 挑一个「本次改动会改变、且肉眼可读」的串, 在设备上读它
```

⛔ `isUpdatePending=true` 只表示**下次启动**会用，**不是**「已生效」，别把它写进交接当成功。
⛔ 只重启一次读不到新行为，那不是没送达。

---

## 5. 真机自动化的坑（我这一晚全踩过一遍）

| 坑 | 表现 | 做法 |
|---|---|---|
| **uiautomator dump 含屏外节点** | 列表里按 dump 坐标点会**打空** | 可滚动列表按**截图上的可见位置**点 |
| **按钮位置会漂** | 上一条错误信息消失后按钮上移（2452↔2240） | **每次点之前现读一次坐标** |
| **同名文本** | 页面标题和提交按钮都叫「登录」 | 取**最靠下**的那个 |
| **`checked="true"` RN 未必暴露** | 读到 0 ≠ 没选中 | **看截图** |
| **输入框有残留** | 密码多一个字符 → 登录失败 | 清空后**先确认为空**再输 |
| **Git Bash 路径转换** | 写到 `C:/Program Files/Git/sdcard/...` | `export MSYS_NO_PATHCONV=1` |
| 🔴 **「点了没反应」** | 和「点歪了/控件没挂载/导航栈没回位」**长得一模一样** | 判缺陷前先找一条**能稳定复现**的路径走两遍 |

---

## 6. 善后约定

- 真机用完**还原成 `f006_admin`（张六膳）**，删掉自己放的临时文件（`/sdcard/Pictures/*`、`/sdcard/*.xml`）
- 真机产生的**业务数据保留**（它们是有效的 F006 记录）；**为测试造的字典/主数据要删干净并报 before/after 计数**
- 每轮结束跑一次 `pwsh -File scripts/check-worktree-junctions.ps1`，危险数必须是 **0**
- docs/`.claude/` 走 fastlane：`./scripts/deploy/publish-main-fastlane.sh --base-sha <SHA> --confirm YES-DIRECT-MAIN`
  （先 `--dry-run`）；碰 backend/web-admin 代码**走 PR**
