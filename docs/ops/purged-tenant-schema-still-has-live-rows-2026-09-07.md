# 「已清除租户」的 schema 里有 34.7 万行，且应用用户是它的 owner

**票**: `OPS-PURGED-TENANT-SCHEMA-STILL-HAS-LIVE-ROWS-20260907`
**日期**: 2026-09-07
**状态**: 📋 **只登记 + 只量清楚，⛔ 未动任何数据 / 未改任何配置**
**发现**: Line B 在查别的东西时撞见（`tenant_purge_68.isapi_devices` 6 行）；本文件是全量普查。

---

## 一句话

**一个名字叫「已清除租户」的 schema，装着 34.7 万行、182 张表，
而应用自己的数据库用户 `cretas_user` 是它的 owner。**

---

## ① 六个残留 schema 的实测行数（`count(*)`，⛔ 不是 `reltuples` 估计）

```
tenant_purge_68            347,661 行   182 张表   非空 182/182
f006_clear_71                1,114 行    24 张表   非空  24/24
legacy_retired                  72 行     3 张表   非空   3/3
bak_trf_20260809_1790           20 行     8 张表   非空   8/8
bak_e2e_trf_20260809_8194       13 行     9 张表   非空   9/9
bak_e2e_trf_20260809_2699        6 行     5 张表   非空   5/5
                          ─────────
                           348,886 行
对照 public                 70,186 行   502 张表
```

🔴 **`tenant_purge_68` 的行数是 `public` 的 5 倍。**
⚠️ 而且**没有一张空表**（182/182 全非空）—— 它们 ⛔ 不是清理后剩下的空壳。

⚠️ 口径：这里数的是**总行数**，⛔ 不是 `deleted_at IS NULL` 的「活行」。
对「被清除的租户数据还在不在」这个问题，总行数才是要问的那个。

### 它是 `public` 的**结构性副本**，⛔ 不是零散残留

```
tenant_purge_68 的 182 张表, 表名与 public 重合 **182 / 182 = 100%**
行数最多的几张:
  decision_audit_logs   148,485
  tool_call_records      68,428
  ai_training_samples    34,411
  conversation_sessions  32,908
  ai_analysis_results     9,426
最早的统计时间: 2026-08-17  ⇒ 至少已存在三周
```

📌 前四张是**会话 / 审计 / 训练样本**类数据 —— 这一族通常是最敏感的那一类。

---

## ② 🔴 可达性：**两部分答案，⛔ 不能只答一半**

### 意外访问（走 `search_path`）：⛔ 不可能

```
db 级 search_path 覆盖    <none>
role 级覆盖               0 行
与残留 schema 同名的角色   0 个   ⇒ `"$user"` 解析不过去
会话默认                  "$user", public
应用连接串                ?currentSchema=public   (prod 跑的那份配置里就是这个)
阳性对照: pg_roles 里查得到 postgres / cretas_user ⇒ 这张表确实在被查
```

### 显式访问（带 schema 限定符）：✅ **完全可以，而且是读写全权**

```
tenant_purge_68 / f006_clear_71 / legacy_retired / 三个 bak_*   owner = **cretas_user**
has_schema_privilege('cretas_user', 'tenant_purge_68', 'USAGE')      = **t**
has_table_privilege('cretas_user', 'tenant_purge_68.isapi_devices', 'SELECT') = **t**
阳性对照: public.users 同样 = t
```

⇒ **`cretas_user` 是这六个 schema 的所有者** —— 应用用什么身份连库，就用什么身份拥有这些数据。

▎**「不在 `search_path` 里」⛔ 不等于「读不到」** ——
它只挡住**不带限定符**的访问；一句 `SELECT … FROM tenant_purge_68.x` 照样成立。
⇒ 准确的结论是：**要有人显式写出那个 schema 名才读得到，而权限上没有任何东西拦着。**

---

## ③ 它们**不在基线里** —— 这条是**证明**的，⛔ 不是推断

基线只 `pg_dump -n public`，所以「它们不在里面」是可预期的。⚠️ 但预期 ⛔ 不是证据。

⚠️ 而且 ⛔ 不能数**裸名字** —— 那会把「注释里提到这个 schema」也算成「它在基线里」
（本仓第十节那条：按文本特征找 X 会把谈论 X 的文本算成 X）。
⇒ 按**限定符形态**数，并拿 `public` 当阳性对照：

| schema | `CREATE SCHEMA` | `search_path` | `schema.` 限定 | 裸名字 |
|---|---:|---:|---:|---:|
| **public**（阳性对照） | **1** | 0 | **4626** | **9020** |
| tenant_purge_68 | 0 | 0 | 0 | 0 |
| f006_clear_71 | 0 | 0 | 0 | 0 |
| legacy_retired | 0 | 0 | 0 | 0 |
| bak_trf_20260809_1790 | 0 | 0 | 0 | 0 |
| bak_e2e_trf_20260809_2699 | 0 | 0 | 0 | 0 |
| bak_e2e_trf_20260809_8194 | 0 | 0 | 0 | 0 |

⇒ **四种形态全 0**，而 `public` 每一种都有。**从基线新建的库不会带上这六个 schema。**

---

## ⚠️ 方法论：「必须带 schema 过滤」这条今天遇到了它的反面

本仓立过：**这个库里任何按名字数的东西都必须带 schema 过滤**
（某次 census 没带 `table_schema='public'`，把 `tenant_purge_68` 的同名列数了进去）。

🔴 而 Line B 这次差点栽在**反方向**：查询写死了 `schemaname='public'` ⇒ **差点整个漏掉那 6 行**。

⇒ 完整版：

> **先列出有几个 schema，再决定滤不滤，并把「滤掉了什么」打成读数。**
> ⛔ 无条件加 `= 'public'` 与无条件不加，是同一个错误的两面。

---

## ⛔ 本票不做（等裁定）

1. **⛔ 不删任何数据**、⛔ 不改任何权限、⛔ 不动 owner。
2. 「这些数据**应不应该**还在」是产品/合规问题，⛔ 不是运维能自己拍的
   —— 一个叫 `tenant_purge_68` 的 schema 装着 34.7 万行会话与审计数据，
   **要么它不该在，要么这个名字是错的**，两种都要人来判。
3. 三个 `bak_*` 与 `legacy_retired` 体量很小（合计 105 行），⚠️ 但**同样是 `cretas_user` 拥有**。

关联：`docs/ops/fresh-db-baseline-bootstrap-2026-09-07.md`（第一段：schema 过滤那条）。
