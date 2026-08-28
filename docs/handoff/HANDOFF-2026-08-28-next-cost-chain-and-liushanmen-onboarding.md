# HANDOFF 2026-08-28 —— 给下一棒：成本入账链的两头 + 六膳门为什么没开工

**上一棒**：`HANDOFF-2026-08-28-warehouse-line-closeout.md`（仓储 P0 + 凭证口径，**已闭环**）
**本文**：那条线收尾时量出来的**三件更要紧的事**。它们都不是缺陷修复，是**业务没跑起来**。

⚠️ 本文自包含 —— 不需要读上一晚的全部过程。上一棒的技术细节在上面那份里。

---

## 0. 接手前确认（30 秒，⛔ 不要跳）

```bash
git fetch origin main
git rev-list --count HEAD..origin/main    # 非 0 就先同步
```

**判 prod 跑什么** ⛔ 不要看部署树的 `.git`：

```bash
# 活跃槽会切(本轮在 10010/10020 之间切过 4 次), 不要沿用上次的端口
ssh root@47.100.235.168 "for s in cretas-backend cretas-backend-green cretas-backend-blue; do \
  printf '%-24s %s\n' \$s \$(systemctl is-active \$s.service 2>/dev/null); done"
```

**连生产库**（端口从监听反查，⛔ 不要 `pgrep -f` —— 会抓到你自己的探针）：

```bash
ssh root@47.100.235.168 'P=$(ss -lntp | grep -oP ":1002[02].*pid=\K[0-9]+|:1001[08].*pid=\K[0-9]+" | head -1)
  eval "$(tr "\0" "\n" < /proc/$P/environ | grep -E "^(DB_NAME|DB_PASSWORD)=" | sed "s/^/export /")"
  export PGPASSWORD="$DB_PASSWORD"; psql -h localhost -U cretas_user -d "$DB_NAME" -f -' < your.sql
```

📌 `DB_NAME=cretas_prod_db`（⛔ 不是配置默认值 `cretas_db`）。
📌 测试凭证：F006 / LIUSHANMEN 账号密码统一 `123456`。

---

## 1. 🔴 成本入账链：我们修好了中段，两头都空着

上一棒的 ④（PR #235）解决的是「**哪些消耗算成本**」——
`VoucherConsumptionSourcePolicy` 那张进/不进凭证的表。**中段是对的。**

### 下游一：科目映射基本没配

```sql
select factory_id, business_type, count(*) from voucher_subject_mappings group by 1,2;
```

2026-08-28 实测：**一共 6 条**

| | |
|---|---|
| 租户 | **全部 LIUSHANMEN，F006 一条都没有** |
| 业务类型 | **全部 `PURCHASE`（采购）** |
| 消耗 / 生产领用 | **一条映射都没有** |

⇒ 生产领用的凭证即使按新口径正确生成，**也没有对应的借贷科目可落**。

⚠️ **我没查清的一层**：F006 有 **20 条凭证**却 **0 条科目映射** ——
那些凭证的科目是怎么来的？默认配置？硬编码？
**这是下一棒第一件该查的事**，因为它决定「补映射」是补数据还是改代码。

### 下游二：近两个月一次账都没导出去

```sql
select factory_id, count(*), max(created_at)::date from voucher_export_records group by 1;
select to_char(created_at,'YYYY-MM'), count(*) from vouchers group by 1 order by 1;
```

```
凭证产出   2026-08 共 20 条(最新 8-28) —— 还在生成
最后导出   2026-07-09, 且那两条 row_count = 0(空文件)
上一次有内容的导出  2026-07-08  QUANTITY_AMOUNT_LEDGER  1473 行
目标系统   金蝶 KINGDEE_KIS / KINGDEE_YXSKY
```

⇒ **凭证在生成，但没人把它导进金蝶。** 成本口径修得再对，账没进财务系统等于没记。

⚠️ 要先分清两件事：是**没人去点导出**（运营问题），还是**导出坏了**（产品问题）。
判据：拿 F006 手动导一次 8 月的账，看 `row_count` 是不是 0。
**7-09 那两条 `row_count=0` 很可疑** —— 它可能就是「导出坏了」在两个月前的第一次表现。

---

## 2. 🔴 六膳门（真客户）根本没开工

```sql
select 'material_batch' t, count(*) from material_batches where deleted_at is null and factory_id='LIUSHANMEN'
union all select 'production_plan', count(*) from production_plans where deleted_at is null and factory_id='LIUSHANMEN'
union all select 'material_consumption', count(*) from material_consumptions where deleted_at is null and factory_id='LIUSHANMEN'
union all select 'voucher', count(*) from vouchers where factory_id='LIUSHANMEN';
```

2026-08-28 实测：

| | 六膳门 | 对照：F006（测试租户）|
|---|---:|---:|
| 活跃账号 | **31** | 15 |
| 物料批次 | **2** | 140 |
| 生产计划 | **0** | 36 |
| 消耗记录 | **0** | 245 |
| 凭证 | **0** | 20 |
| 期初库存 | **没建** | — |

**账号一个月前就开好了（7-26 配的审批流），31 个人，卡在 2 个物料批次。**

⇒ 上一晚装的所有控制（自审收窄、盘点审批、sourceType）
**是给一个还没开工的工厂装的**。控制有效 ≠ 业务跑得通，我们只证了前者。

### 这才是最该问的一件事

**去问六膳门为什么停在 2 个物料批次。** 可能性各自的下一步完全不同：

| 猜测 | 怎么验 |
|---|---|
| 不知道从哪开始（期初建账没人教）| 陪他们走一次「期初建账/期初入库」 |
| 试过一次撞了什么 | 查 8 月的 error 日志里 LIUSHANMEN 的 4xx/5xx |
| 就是还没打算用 | 问 Owner 商务口径 |

⚠️ **⛔ 不要靠猜。** 这个答案比修任何一个缺陷都更决定这套系统有没有用。

---

## 3. 手机端只验到门口

真机（小米 M2102K1AC，`adb` 在
`C:\Users\Steve\AppData\Local\Android\Sdk\platform-tools\adb.exe`，不在 PATH）：

```
App     com.cretas.foodtrace 1.0.4 (2026-08-20 装, 独立发布包, 非 dev client)
后端    www.cretaceousfuture.com -> 139.196.165.140:443   ✅ 正常
登录    f006_admin/123456 -> 成功, 进「经营总览」          ✅
```

**只验到登录 + 首页渲染。** 工人真正用手机做的是**报工、扫码入库、盘点**，
首页那四个入口（生产进度/销售订单/采购订单/成品库存）一个都没点进去。

### adb 三个坑（省你半小时）

- Git Bash 下设备端路径要 `MSYS_NO_PATHCONV=1`，
  否则 `/sdcard/x.png` 被转成 `C:/Program Files/Git/sdcard/x.png`
- 某些界面 `screencap` 返回 **0 字节**（FLAG_SECURE）⇒ 用 `uiautomator dump` 读 UI 树
- `input text` 后**先 dump 核对再提交** ——
  ⚠️ 上一棒因为密码多输了一位（`•••••••` 7 位），得出「App 连不上后端」的错误结论，
  还在那个坏读数上盖了一整套自洽的因果链

---

## 4. 上一条线的技术残留（小，可延后）

1. **精度口径**：账面数有 4 位小数（如 `17.2484`），实盘录入只许 2 位
   ⇒ 那些行**永远录不成零差异**。⛔ 未修：改哪一侧是产品口径问题且牵扯成本计算。
2. **① 的零差异侧只在 0 行的空仓库上验过**（有差异侧是 85 行真实用例）。
   原料仓已解除阻塞，值得补一次**有明细**的零差异用例。
3. **六膳门调拨流仍只授权 `factory_super_admin`** —— 与 F006 修过的坑同形：
   全厂只有 1638 一个活跃超管，#237 之后他发起的调拨**会被拒且无人可审**。
   ⚠️ 真实客户侧，**动之前问 Owner**。
4. 六膳门 `INVENTORY_TRANSFER_APPROVAL` **v2 草稿停在 2026-07-29**
   （`draft`/`enabled=f`，不生效、无害）—— 谁改到一半没发布，值得问一句。

---

## 5. 建议顺序

| # | 事 | 为什么排这个位置 |
|---|---|---|
| 1 | **问六膳门卡在哪** | 唯一决定这套系统有没有用的问题；其它都是它的下游 |
| 2 | **手动导一次 F006 八月的账** | 一次操作就能分清「没人点」还是「导出坏了」；`row_count=0` 已经很可疑 |
| 3 | **查 F006 那 20 条凭证的科目从哪来** | 决定「补科目映射」是补数据还是改代码 |
| 4 | 手机端走完一条真实业务链 | 工人的真实入口，目前只验到首页 |
| 5 | §4 的四条技术残留 | 都不阻塞 |

---

## 6. 生产现状（本轮留下的，全部可核）

```
审批配置(每租户每种单据恰好 1 条生效)
  F006       盘点 v3  有条件节点  [finance_manager, factory_super_admin]
  F006       调拨 v1  无条件节点  [finance_manager]
  LIUSHANMEN 盘点 v1  有条件节点  [finance_manager, factory_super_admin]
  LIUSHANMEN 调拨 v1  无条件节点  [factory_super_admin]   <- 见 §4.3

本轮建的单据(全部 applied_at 为空 => 一张都没调整过库存)
  ST-202608-E8ACDE44 REJECTED(1647)  六膳门盘点审批流验收
  ST-202608-6B907387 APPROVED(1309)  卡死样本, 已用 SQL 补齐同步
  ST-202608-C6EC572F REJECTED(1309)  零差异条件节点的阴性对照
  ST-202608-2A4C1F02 APPROVED(1309)  零差异条件节点的正向
  TRF-20260814-3498  REJECTED(1551)  超管自审收窄的生产实证
```

已合并 PR：**#235**(sourceType) **#237**(超管自审收窄) **#250**(盘点单终态同步)。

📌 常设规则已上 main：`.claude/rules/standing-authorization-and-visual-acceptance.md`
—— **默认直接做完不设人肉闸门；「不问」≠「不验」；能用界面证明的就用视觉验收。**
