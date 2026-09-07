# 2026-09-07 批次收口 —— 本轮完成并移出 ACTIVE 的条目

⚠️ 移出 ⛔ 不等于「没有后续」：每条下面写了**它留下的东西**。

## CI-GATE-MERGES-A-STALE-BASE-SHA-20260907

**完成依据**：#722 已合: 闸取 base 活 tip + payload_stale/payload_behind_by 三读数

移出时的原文：

- `CI-GATE-MERGES-A-STALE-BASE-SHA-20260907` — `queued` — Owner: Line C — 🔴 #707/#713 没修完的一半：闸合进的是 payload 里的 `pull_request.base.sha`，**快照会滞后**。#710 run 34074881917 拿到 `base=f3ba01d172` 而 main 已 `ced5a45397`，全绿而 `--is-ancestor` 判否。抓手是**测试总数 13235 vs 13237**，⛔ 不是 `merged_ok`。建议运行时 fetch 活 tip。

## OPS-FRESH-DB-CANNOT-BE-BUILT-20260907

**完成依据**：被 baseline bootstrap 取代并落地(#735/#737/#739): 新库能建, 502 表/2044 索引与 prod 逐位相同, 失败迁移 0

移出时的原文：

- `OPS-FRESH-DB-CANNOT-BE-BUILT-20260907` — `in-progress` — Owner: Line C — 🔴 从零建不出库：Flyway 死在第 267 条（加列早于建表 152 位）。⇒ 新测试环境 / 灾备 / 新租户库今天一个都建不出。⛔ 不逐个修，先一趟 continue-on-error 量全深度。详情 [archive](archive/2026-09-07-schema-parity-and-ci-gates.md#ops-fresh-db-cannot-be-built-20260907)。

## CI-GATE-SCANS-DEAD-MIGRATION-DIRS-20260907

**完成依据**：#738 扫描根由 spring.flyway.locations 派生(Line C 提案), census 110→107; 3 个只在死目录的索引名已登记

移出时的原文：

- `CI-GATE-SCANS-DEAD-MIGRATION-DIRS-20260907` — `queued` — Owner: Line C — 🔴 P0：那道闸扫整个 `db/` 而 Flyway 只跑 `db/flyway/`；3 个部分唯一索引名只在死目录里 ⇒ 闸说「有」而新库拿不到。详情 [archive](archive/2026-09-07-schema-parity-and-ci-gates.md#ci-gate-scans-dead-migration-dirs-20260907)。

## CI-GUARDED-PATHS-MUST-BE-DECLARED-20260907

**完成依据**：#728 `# GUARDS=` 声明闸 + #732 双向登记棘轮, 均已合; 上线后三次抓住加它的那个 PR 自己

移出时的原文：

- `CI-GUARDED-PATHS-MUST-BE-DECLARED-20260907` — `queued` — Owner: Line C — 「被断言的东西不在触发面里」今天 4 次。⛔ 不反解（第 3 例根本不是 workflow）：用 `# GUARDS=<glob>` 声明。详情 [archive](archive/2026-09-07-schema-parity-and-ci-gates.md#ci-guarded-paths-must-be-declared-20260907)。

## OPS-PROD-ONLY-PARTIAL-INDEXES-20260907

**完成依据**：#725 V20261101_39 已合; 后续 prod-only 由 #741 V20261101_40 收口

移出时的原文：

- `OPS-PROD-ONLY-PARTIAL-INDEXES-20260907` — `review` — Owner: Line C — PR #725：`V20261101_39` 把两条只活在 prod 的部分唯一索引补进迁移；真 pg17 上 A/B/C/D 四态跑过。详情 [archive](archive/2026-09-07-schema-parity-and-ci-gates.md#ops-prod-only-partial-indexes-20260907)。


## BUG-I18N-KEYS-LEAK-CUSTOMER-CREATE-DIALOG-20260906

**完成依据**：#688 已合（8 处泄漏：`useTranslation('management')` 而键写 `common.xxx`）；
OTA bundle `1788716508930` 已推送并在小米真机上取到**行为读数**：
新建客户弹窗标题 `customerManagement.modal.createTitle` → 「新建客户」，裸键计数 **1→0**，业务写入 0。
⚠️ 送达证据是**真机上读到改动后的行为**，⛔ 不是 OTA 推送的五步回执（那次「零送达」时回执也全绿）。

移出时的原文：

- `BUG-I18N-KEYS-LEAK-CUSTOMER-CREATE-DIALOG-20260906` — `review` — Owner: Line A — #688 已合（8 处泄漏：`useTranslation('management')` 而键写 `common.xxx`；补 modal 节 + 5 键 zh/en 双语；单屏键存在闸 4/4，变异红）。待 OTA 送达后真机复看。
