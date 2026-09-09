# 2026-09-07 新开票 · 详情

台账正文只留一句话与阻塞点（`check_dispatch_active.py` 每条 ≤ 300 字），完整读数在这里。

## CI-PYTHON-PG-TESTS-WOKE-UP-AND-FAIL-20260907

**读数**（#750 的 pytest，run 34128058332，`RUNNER_ENV: self-hosted` / **Python 3.10.12**）：

| | passed | skipped | failed | errors | 合计 |
|---|---|---|---|---|---|
| main `6ade1ab4f3` (08:19) | 9638 | 173 | 1 | 0 | **9812** |
| #750 (13:36) | 9637 | 165 | 3 | 7 | **9812** |

合计一字不差 ⇒ #750 没增删任何用例（它那两个 python 文件改的全是 docstring，+19/-0）。
挪动的恰好 9 个：8 个出 `skipped`、1 个出 `passed`，等于新红的 9 个。

**机制**：08:19 与 13:36 之间 WSL runner 装上了 PostgreSQL 17。
`test_chat_session_service.py:556` 走 `is_local_postgres_absent(exc)` —— 「PG 没在听」判 absent（skip），
「在听但密码不对」判真错误（raise）。⚠️ **这个判别式是对的**，⛔ 不该因为凭证错就静默 skip。
变的是环境：PG 从缺席变成在场，于是 9 个用例从跳过变成跑起来并失败。

**成因**：

- 3 个测试文件把 DSN 默认值硬编码成 `smartbi_user:smartbi_pass` —— `conftest.py:35`、
  `test_chat_session_service.py:534`、`test_chat_session_user_identity_postgres.py:17`；
  而 `.claude/rules/CREDENTIAL-MANAGEMENT.md:30` 明写「旧值 `smartbi_pass` 已废弃 (2026-04-25 修正)」。
- `python-gate.yml` 提供 `SMARTBI_PG_DSN` 的次数 = **0**，也没有 `services:` 容器。
- 第 9 个是 `test_pg_absence::test_absence_found_inside_exception_group` —— `NameError: ExceptionGroup`，
  即 **#748 修的那个 3.10 缺陷**（#748 当前冻结）。

⛔ **与 `CI-NINE-OPTIN-PG-TESTS-RUN-NOWHERE-20260907` 是两张票**：那张是 Java 侧（`java-gate-pr.yml`，
15 个 opt-in 里 6 个被点名），这张是 Python 侧（`python-gate.yml`）。⚠️ 两边数字都是 9 **纯属巧合**，
按数字合票会把两个不同车道的问题并成一个。

⚠️ 另一条读数与冻结前提对不上：pytest 这一步实测解析到 **Python 3.10.12**，不是 3.11.13。
`tracked-secret-scan` 确实在 windows-wsl-ci 上绿了 —— 但那是**另一个 step**。

## OPS-MUTATION-GUARD-HAS-NO-CALLER-20260907

`scripts/mutation-guard.sh` 存在、可执行、1725 字节，脏树 rc=1 / 干净树 rc=0 都实测过。
**全仓调用点 0 个** —— 唯一提及在 `docs/decisions/2026-08-18-餐饮AI读路径-本轮交接.md:173`，是一份交接文档。
在 workflow / husky hook / `package.json` / 任何脚本里出现的次数 = **0**。

🔴 **判据陷阱**：`grep -r mutation-guard` 读到 **11**，看起来像「接上了」。
其中 **10 处是同名不同物** —— `scripts/e2e/production-readonly/core/mutation-guard.js`，
一个 E2E 里拦非白名单写请求的 JS 模块，与这条规矩无关，而且它本身接得很好。

▎**判据**：`mutation-guard.sh` 出现在 workflow / husky / `package.json` 里的次数 > 0（今为 0）。
⛔ 不是「脚本在不在」，也不是按基名 grep 的命中数。

📌 这与 `OPS-CRON-ALERT-FILES-HAVE-NO-CONSUMER-20260906` 是**同一种形状**：
一个存在但没有消费方的东西（那边是 11 个告警文件零消费方，这边是一个守卫脚本零调用点）。
⇒ 那条 memory 第 ⑤ 段写着「已机制化，⛔ 不再靠记得」—— **那句话当时是真的**，
变的是前提：它从来没有获得调用点，于是退化回一个需要被记得的工具，于是有了第五次。


## CI-SIX-WORKFLOWS-STILL-PINNED-TO-HOSTED-20260907

**读数**：托管池 2026-09-07 **13:46 前后**停止派发。形态是
`labels=ubuntu-latest` / `runner=""` / `steps=0` / 2 秒失败 / 无日志。

| 时间 | `ops-script-tests` | steps |
|---|---|---|
| 13:04:38 | success | 10 |
| 13:45:31 | cancelled | 10 |
| **13:48:35 起** | **failure** | **0** |

⚠️ 阴性对照：同一个 PR 上 `labels=self-hosted,cretas` 的 job 全部跑绿，`runner=windows-wsl-ci`。
⇒ ⛔ 不是「CI 死了」，是**这些 workflow 还没跟着迁到本机**。

**12 个写死 `ubuntu-latest` 的 workflow，按触发方式分三档**：

| 触发 | 数量 | 文件 | 影响 |
|---|---|---|---|
| PR/push 自动 | **3** | `ops-script-tests` / `soft-delete-scan-surface` / `fresh-db-progress` | 每个 PR 上都红 |
| 定时 | **3** | `java-full-nightly` / `sop-corpus-staleness` / `fresh-db-progress` | 🔴 见下 |
| 只能手动 dispatch | 7 | `ci.yml` / `daily-integration` / `e2e-pr` / `e2e-post-deploy` / `kb-drift-check` / `threshold-parity-check` / `tool-isolation-audit` | 没人点就没影响 |

🔴 **要紧的顺序与直觉相反：定时那 3 个比红的 3 个更危险。**
红的会在 PR 上明晃晃摆着，有人看得见；定时的派不出去就是**彻底没有信号** ——
没有红叉、没有失败通知、日志里什么都没有。⚠️ 这比「跑了报绿但没产出」还低一档：
那个至少还有一次 run 记录，这个连 run 都不存在。

📌 `soft-delete-scan-surface` 与 `fresh-db-progress` **是 09-07 当天新立的闸** —— 立起来当天就落在跑不动的池子上。

**补迁的前置**：要过 `ci-runner-routing.md` 那六关（缩进字面量 / `setup-python` / Linux 专有命令 /
缺 Maven 与裸 `python` / 平台专用 wheel / `services:` 容器）。
⚠️ `ci.yml` 卡在第 6 关（要 Docker）—— **本机 WSL 有没有 Docker 尚未实测**，
⛔ 不要照抄 Mac mini 那条「没有 Docker 所以无解」的结论，那是另一台机器。

⚠️ 相关：#745（切回托管）已于 09-07 关闭 —— 它写对了它那一刻，前提变了。


## CI-WEB-ADMIN-VITEST-IS-FLAKY-ON-SELF-HOSTED-20260909

**同一个 commit（#759 `8326d2a1f2`）跑两次，红的是两组不相交的用例：**

| 第一次 (job 102178699059) | 重跑 (job 102210817075) |
|---|---|
| `ReturnBanner.spec.ts:30` | `approvalHub.mount.spec.ts:121` |
| `ProcessDataTable.inputAvailableStock.spec.ts:124` | `ProcessDataTable.inputSourceRows.spec.ts:146` |
| `PackagingBindingDialog.spec.ts:216` | `ProcessDataTable.workflowContext.spec.ts:346` |
| `PackagingBindingDialog.spec.ts:236` | `ProcessDataTable.workflowContext.spec.ts:385` |

两次都是 **3 files / 4 tests failed / 4691 passed** —— 🔴 **数字一模一样，文件一个都不重合。**
⚠️ 只看「4 failed」和总数会读成「稳定复现」。要把两次的**文件名**摆在一起比才看得出是浮动。

**四格对照**（本地为同机、同 `node_modules`、同全量口径）：

| | 结果 |
|---|---|
| 本地 main `3c69b7f49a` 全量 | ✅ 583 passed / 4687 tests, rc=0 |
| 本地 #759 `8326d2a1f2` 全量 | ✅ 584 passed / 4695 tests, rc=0（精确复现实现线报的数） |
| CI #759 第一次 | 🔴 A 组 4 红 |
| CI #759 重跑 | 🔴 B 组 4 红（与 A 不相交） |
| CI #758（近 main） | ✅ 绿 —— ⚠️ 浮动的闸偶尔全绿是正常的，**这个绿不构成强证据** |

**差异面**：CI 多 worker 并行（`web-admin-gate.yml` 注释里实测 CPU 350%），本地串行。
失败形状也吻合：3 条是 `expected vi.fn() to be called … Number of calls: 0`，1 条是
`Cannot call text on an empty DOMWrapper` —— 都是渲染/异步时序类，不是逻辑断言。

⛔ **不要改那些测试去迎合 CI**：它们在 main 和本地都绿，改它们等于把环境问题伪装成代码问题。

📌 这条比它牵出的那个 PR 要紧：**这道闸现在的绿和红都只携带有限信息。**
2026-09-08 夜它已经让 organizer 先后得出过两个错结论 ——
先是「#755 带进来的存量」（对照一做就塌），再是「确定性，与 #759 相关」（重跑一次就翻）。

