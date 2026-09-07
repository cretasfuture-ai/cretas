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

