# 第八轮：全角色逐屏视觉验收

**日期**: 2026-08-29
**目标**（用户原话）: 修复完所有的内容 去视觉验收 确保所有的按钮，功能 流程是合理的

---

## 走查覆盖

| | |
|---|---|
| 角色 | **17 / 17**，每个角色的登录都从 auth store 读到了 username/factoryId（⛔ 不是「跑完了没报错」） |
| 屏数 | **548** 张主图（另有滚动分段图，合计 1105 张） |
| 退出码 | 17 个角色全部 rc=0 |
| 器件 | `tests/vshot/walkthrough.spec.ts`（运行时 BFS 走导航 ref，每屏全页截图 + 文本落盘） |

⚠️ **走查中途改过代码**：OTIF 屏的修复在 admin 那一轮跑完**之后**才落盘，
所以那一轮的读数没有被污染。其余角色不含报表 tab。

⚠️ 补了 `frontend/CretasFoodTrace/playwright.config.ts` —— 这些 spec 在仓里躺了一阵，
**没有配套配置**，每次跑都临时拼一份，「上次是怎么跑的」不可复现。

---

## 修掉并已上线的（8 个 PR，全部在生产上复打过）

| PR | 缺陷 | 判据（部署后实打） |
|---|---|---|
| #364 | `SecurityUtils.getCurrentUserId()` 在本项目**恒返 null**，43 个调用点全线拿不到当前用户 | `/api/mobile/devices` 500→200；提醒 0→3；`customer-price-history` 401→200；`/users/me/profile` 401→200 且改名落库 |
| #367 | OTIF 报表缓存**自我投毒**（`subList` 写得出去读不回来） | 连打 3 次全部 200（第 2、3 次命中缓存） |
| #368 | 全 App 仪表盘**水平镜像**（刻度 80 在左边） | 真机 + web：刻度 20 在左 80 在右；FPY 46.1% 指针刚过中线 |
| #369 | 没有打卡记录被报成「全厂 23 人缺勤 / 出勤率 0.0%」 | 人数 23、部门 1 保留；出勤五项全 null |
| #370 | 一个附属的上传步骤把整道 CI 闸判成红，还 skip 掉闸自己的「判定」 | java-full-gate 恢复能说出结论；新闸接进 source-hygiene 并真的执行了 |
| #371 | 「产能利用率」在两屏上是两个数（一个装的是设备可用率） | 两个接口都给 2.3 ✅ |
| #372 | 效率分析屏四个数没有一个是它标签说的那个量（两个是硬编码 0） | 算不出给「—」，不画进度条 |
| #360/#361 | 自助改自己的资料要「人事管理写权限」；27 条按钮红旗逐条核完 | 见各自 PR |

### 真机送达证据（⛔ 不是 OTA 回执）

OTA 推送后在真机上打开「准时交付率 (OTIF)」：

```
准时足量交付率 (OTIF)
  这段期间没有订单，算不出准时交付率。
交付指标明细
  —          —          —
  OTIF率     准时率     足量率
订单统计
  准时率  —      足量率  —      OTIF率  —
```

改之前这三行是 `0.0% / 0.0% / 0.0%` 带满格底条，中间还画着三个指向 0 的表盘。

---

## 🔴 已查实但**本轮没修**的（都带证据，供裁决）

### 1. SmartBI 的 13 个接口里 **8 个是 404**

```
404  smart-bi/analysis/sales        404  smart-bi/analysis/finance
404  smart-bi/analysis/department   404  smart-bi/analysis/region
404  smart-bi/alerts                404  smart-bi/recommendations
404  smart-bi/datasets              404  smart-bi/switch-dimension
200  smart-bi/dashboard/executive   405  drill-down / query / upload / upload-and-analyze（路由存在）
```

Java 侧确认**根本没有**这些路由（只有 `/analysis/dynamic`），不是路径写错。
⇒「销售分析」「财务分析」两屏**永远不可能有内容**，而屏上写的是
「数据加载失败，请重试」—— 暗示是暂时性故障。

**建议**：把 404 与网络故障分开说（「这个分析还没上线」），或从导航里摘掉。
⛔ 让用户去重试一件永远不会成的事，比空着更糟。

### 2. `@DataScope` 在生产上**整体 no-op**

`DataScopeAspect` 拿 `SecurityUtils.getCurrentUserId()`（恒 null）→ ctx 为 null →
**跳过 scope 设置**。10 处 `@DataScope` 从来没生效过。

⚠️ 一改就同时翻转「用户能看见哪些行」，必须单独一轮逐屏验收。
已在 `CurrentUserIdSourceGateTest.DELIBERATELY_NOT_MIGRATED` 里**登记并写明理由**。

### 3. 后端下发的权限**整份被 App 丢弃**

后端发 `permissions: ['sales:*']`（扁平数组），
`authService` 按 `permissions.modules.processing_access` / `permissions.features` 去读
—— 数组上没有这两个属性 ⇒ `factoryUser.permissions` 对每个用户都是 `[]`。

App 之所以还能用，是因为 `types/auth.hasPermission` 有「权限为空就按角色兜底」这一支，
于是**实际生效的是前端硬编码的角色→权限表**。

⇒ **在 web-admin 里给某个用户调权限，对 App 零效果。**
   同族：`HomeScreen` 的 5 张功能卡比对 `processing_access` 这类后端从不产出的字面量，
   于是**所有角色**（含 `*:*` 超管）的首页「功能模块」都只有一张「系统设置」。

### 4. 错误边界把英文技术信息给了一线用户

```
这一页打不开
其它功能还能用，可以返回上一页继续。
TodoDetail: Cannot destructure property 'refId' of 'route.params' as it is undefined.
[重试这一页]
```

中文文案是好的，最后那行不是。给仓管/操作员看的界面不该出现这个。
（这些屏本身只有走查用无参数导航才到得了，真实用户走不到 —— 但边界文案对**所有**崩溃生效。）

### 5. `ReportDashboard` 的「设备利用率」还是 0.0%

而产能利用率报表说 2.3%。与 #371 修的是同一族（同名不同量），但在另一张卡上。

### 6. GitHub 制品配额（需 owner 动手）

95 个制品 10.28 GiB，其中 **10.11 GiB 已过期**。保留期已经是 1/3 天，不是保留期的问题 ——
过期制品要等 GitHub 每 6–12 小时重算才释放，而每次合并产出 169 MiB。

```bash
gh api --paginate /repos/cretasfuture-ai/cretas/actions/artifacts \
  --jq '.artifacts[] | select(.expired) | .id' \
  | xargs -I{} gh api -X DELETE /repos/cretasfuture-ai/cretas/actions/artifacts/{}
```

那些制品已经下载不到，删掉不损失可用的东西；但这是对共享 CI 的不可逆操作，没有代劳。

### 7. 仪表盘的数值文字与指针重叠（观感问题，非读数错误）

---

## 本轮建的闸（全部做过变异验证）

| 闸 | 条数 | 变异后 |
|---|---|---|
| `SecurityUtilsCurrentUserIdTest` | 7 | 改 attribute 名 → 3 红 |
| `CurrentUserIdSourceGateTest` | 6 | 塞回一处旧写法 → 红并点名文件 |
| `CacheableResultRoundTripTest` | 6 | （守知识，用生产那份序列化器） |
| `CacheableBodyHasNoBackingViewContractTest` | 3 | 改回 `subList` → 红并打印那一行 |
| `GaugeChartGeometry.test.ts` | 10 | 换回镜像换算 → 6 红 |
| `PersonnelAttendanceUnknownTest` | 4 | `attendanceTracked=true` → 2 红 |
| `KpiCapacityUtilizationSourceContractTest` | 4 | 改回取 availability → 2 红 |
| `efficiencyMetricsMustNotBeFabricated.test.ts` | 7 | `laborEfficiency: 0` → 1 红 |
| `check_artifact_upload_policy.py` | 三态 | 三个方向都验了，含 rc=2 |

⚠️ 有两道闸建完之后**只被编译、没被执行**（类名进不了 CI 选择器），
是 #370 修好仪器之后才翻出来的。已改名。
▎**本地 `mvn -Dtest=...` 跑得到 ≠ CI 跑得到。**

---

## 我在 F006 留下的写入

- 中转台账确认 ×1、盘点单 ×2（早前轮次）
- `f006_warehouse_mgr` 的 `full_name` 改过两次（验证自助改名），已改回「六膳门仓储主管」

F006 是测试租户，但记在这里免得下次有人当成用户数据。
