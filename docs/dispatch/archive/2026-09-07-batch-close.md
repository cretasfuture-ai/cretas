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

## BUG-I18N-DUPLICATE-TOP-LEVEL-KEYS-20260907

**完成依据**：#709 已合 —— 既有 i18n 闸修掉一类假阳性（预算 486 → 350），补重复顶层键检测与查表双向自检。
⚠️ 该闸此前与另一道同名闸整份重复过一次，`Write` 返回 `updated` 就是撞名警报。

移出时的原文：

- `BUG-I18N-DUPLICATE-TOP-LEVEL-KEYS-20260907` — `review` — Owner: Line A — #709 已合：既有 i18n 闸修掉一类假阳性（预算 486 → 350），补重复顶层键检测与查表双向自检。⚠️ 该闸此前与另一道同名闸整份重复过一次，Write 返回 `updated` 就是撞名警报。

## UX-WAREHOUSE-VALIDATION-GUARDS-UNREACHABLE-20260907

**完成依据**：#699 已合 —— 边界不可见的两屏补可见提示，另两屏标注为防御性（用户走不到）。
⚠️ 「防御性」这个判定的保质期由可达性决定，可达性一变结论就失效 ⇒ 结论配了闸。

移出时的原文：

- `UX-WAREHOUSE-VALIDATION-GUARDS-UNREACHABLE-20260907` — `review` — Owner: Line A — #699 已合：边界不可见的两屏补可见提示，另两屏标注防御性，全 App 普查 37 处上棘轮。待真机复看。

## OPS-MERGED-IS-NOT-DEPLOYED-20260907

**完成依据**：2026-09-07 22:41 从 clean 的 `origin/main` detached 树发布，prod Flyway **37 → 41**。（⚠️ 2026-09-10 订正：原记「09-08 22:41」错了一天；权威时刻是 Flyway `installed_on` = `2026-09-07 22:41:31`，与备份文件名 `.bak.20260907_224118` 一致。）

| 判据 | 读数 |
|---|---|
| `DEPLOY_EXIT` | 0 |
| `RELEASE_FINAL_STATUS` | 恰好 1 次，`=deployed` |
| `_38`/`_39`/`_40`/`_41` | 全部 `ok=true`，22:41:31；失败行 0 |
| 新索引 | 抽查 4 个 → 4/4 在 `pg_indexes` |
| 进程晚于制品 | jar 22:41:02 → 进程 22:41:22 |
| 运行中 jar 含本次标记 | `V20261101_41` 的 `uk_labels_label_code` → 1 |
| 蓝绿 | blue(10010) inactive / green(10020) active，139 网关已跟切 |
| 真跑业务路径 | `/api/mobile/health` 200；`unified-login` 401（活着并在校验） |

**部署前置**：4 条迁移含 12 条 `CREATE UNIQUE INDEX`，`IF NOT EXISTS` 只按**索引名**跳过、⛔ 不容忍重复数据 ⇒ 每条都可能 fail closed。
逐条在 prod 数重复组（谓词从 SQL 机械解析，⛔ 不手抄）：**12/12 表存在，重复组全为 0**。
⚠️ 分母：`conversation_memory` 366 行 / `material_batches` 21 / `labels` 15 / 另两张各 1 行 —— **其余 7 张是空表**，那 7 个「0 重复」恒真，只够证明迁移不会炸。

⚠️ **过程中两次跑成 no-op，都被判据接住**：

① `--base-sha` 误传成当前 HEAD ⇒ `git diff BASE HEAD` 为空 ⇒ `RELEASE_SELECTION=none`。
后台通知写「exit code 0」而实际 `DEPLOY_EXIT=1` 且 `RELEASE_FINAL_STATUS` **0 次** —— 抓住它的是「不出现本身就是失败信号」，⛔ 不是退出码。
正确的 base 是 prod 当前跑的 commit，从上一次成功回执的 `head_sha` 取到 `2e9d2c515f83`。

② `--tests` 不接受通配符（`*RepositoryQueryValidationTest` 实际匹配 53 个类，被 preflight 拒）。

移出时的原文：

- `OPS-MERGED-IS-NOT-DEPLOYED-20260907` — `queued` — Owner: organizer — 🔴 prod Flyway 停在 `20261101.37`(2026-09-06), 而 main 已到 `_41` ⇒ **今天合的 4 支迁移一支都没上线**(4 条零约束索引 / 两处唯一性推回写时**在 prod 上尚未生效**)。⚠️ 台账记的是 PR 状态, 而 **MERGED ≠ prod 生效** —— 这个差在今天的账里看不见, 需要一个字段。
