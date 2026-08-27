# 新会话可直接粘贴的 Prompt

```text
接手并完整执行：

docs/handoff/HANDOFF-2026-08-27-night-gates-and-red-burndown.md

（已在 origin/main 上，仓库相对路径可直接读；细节在同目录的
 HANDOFF-2026-08-27-java-red-burndown.md）

不要只输出方案。先完整读取最新 AGENTS.md、适用 CLAUDE.md、
.claude/rules/measurement-and-wiring.md、最新 docs/dispatch/ACTIVE.md 和上述交接件，
尤其是交接件的「我错在哪」那一节 —— 那里记着三个【推理链完全自洽但被跑一次否掉】
的判断，能省下一整轮弯路。
```

---

## 🔴 第一件事（在做任何改动之前）

**读今晚 02:00 那次 `java-full-nightly` 的结果。**

剩下的 **49 条基线全是同一个根因**（H2 schema 毒源，PR #197），
而 `ddl-auto=create`（#197）与 `forkedProcessTimeoutInSeconds`（#198）都已在 main。

| 结果 | 含义 | 动作 |
|---|---|---|
| 49 条转绿 | 机制诊断成立 | 削基线到接近 0 |
| **仍然红** | **诊断错了** | 回去重查，完整证据链在 #197 的 PR 描述 |

⛔ **在拿到那次读数之前，这 49 条一条都不要动。**

它们**本机全绿**（本机全量 11888 个测试里一条不红），只在 CI 上红 ——
surefire 的类执行顺序在 macOS 与 Windows 上不同，污染源没排在它们前面。

> **「本机跑绿」对这一类没有证明力。**
> 这句话已经写进 `application-test.properties` 的注释，
> 因为下一个人一定会用「我本机是绿的」来反驳那个改动。

⚠️ **`gh run view --log` 会截断**（实测 207446 行里搜不到关键那段）。要完整日志：

```bash
gh api "repos/cretasfuture-ai/cretas/actions/runs/<RUN_ID>/logs" > logs.zip
python -c "import zipfile; zipfile.ZipFile('logs.zip').extractall('.')"   # 多出 14 万行
```

---

## 开工前

1. `git fetch origin`，核对 `origin/main`、`git worktree list --porcelain`、
   `git status --porcelain`、ACTIVE 台账。
2. 主工作区 `C:\Users\Steve\my-prototype-logistics` 有大量其他 Owner 改动。
   **禁止** reset / stash / clean / 覆盖 / 直接改。从最新 `origin/main` 开独立 worktree。
3. `gh` 命令**必须带 `-R cretasfuture-ai/cretas`** —— 否则会解析到别的仓。
4. **削基线之前必须自己重跑那些类** —— `java-narrow-gate` 只跑
   `*ContractTest,*RepositoryQueryValidationTest,FlywayVersionUniquenessTest,*StartupGuardTest`
   这个窄选择器，大多数类**不在里面**，**CI 绿不能证明它们通过了**。

---

## ⛔ 挂账的别顺手做（各有理由，交接件里有全表）

- **BOM 草稿 Workflow 修订** —— 撞在飞任务 `F006-R2-WORKFLOW-FIRST-BOM-GATE`
- **83 条 web 孤儿路由 / 29 条 RN 孤儿屏** —— 要**按名字逐条人工判断**，
  ⛔ 静态启发式再推会开始误伤
- **`getActualQuantity()` 另外 3 处** —— 快照 / DTO 投影，是**设计问题**，不替它决定
- **`SalesFinanceApproveVoucherListenerTest` 的断言对象** —— 要用 `ArgumentCaptor`
  重构断言，**不是改桩**

---

## 若要派并行 agent，这五条必须写进 brief

第一轮我漏了第一条，是查 PR 时才发现 stash 栈顶压着**另一个 agent 的在途工作**，
再晚一步就可能被 `pop` 走。

- ⛔ **不许用 `git stash`** —— 多 agent 共享同一个 `.git`，**栈是共享的**。
  阴性对照改用 `cp` 备份 + `git checkout --`
- ⛔ **不许碰 `known-failing-tests.txt`** —— 并发冲突，由协调者统一削
- ⚠️ **CI 绿不能证明你修好了** —— 窄选择器不覆盖大多数类，**本机读数才是证据**
- ⚠️ **源码扫描型测试类名必须 `ContractTest` 结尾** —— 否则
  `GateSelectorCoverageContractTest` 会打回
- ⚠️ **PR body 用 `--body-file`** —— 反引号写进命令行会被 bash 当命令替换执行

> 八路 agent 没有一路走捷径。给它们的核心禁令是
> 「⛔ 不许为了变绿而改断言 —— 先回答这条断言守的是**需求**还是**历史**，
> 判断不了就不改，写进报告让我裁」。
