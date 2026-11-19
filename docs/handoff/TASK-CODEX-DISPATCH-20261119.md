# 任务卡 CODEX-DISPATCH-01：Codex 执行线自动派活管线（第一版）

- 发起：Claude Code 降本第 17 任 organizer，代 Steve（2026-11-19 22:3x 北京时间）
- 背景：Claude Code 周额度已用 78%，预计 11-20 晚用完、11-23 19:00 重置。Steve 要把执行线交给 Codex，派活要自动化，
  而且 Codex 用终端交互会话（不是桌面端），他能切到终端标签看、能直接对话。
- 分支：codex/agent-dispatch-pipeline（已从 origin/main 3fa5cb47e2 开好，就在当前 worktree）
- 只能改：tools/agent-dispatch/**、docs/handoff/TASK-CODEX-DISPATCH-20261119.md（本文件末尾写回执）、AGENTS.md 末尾加一节「任务卡执行线」（≤ 15 行）
- 不准：改 .github/workflows、scripts/deploy、backend、web-admin、frontend；不 ssh 任何服务器；不合并 PR；不部署

## 要做的东西

队列目录（仓库外，固定路径，脚本里可用环境变量 AGENT_QUEUE 覆盖）：`C:\Users\Steve\agent-queue\`
```
inbox/      新任务卡 <ID>.md，organizer（Claude 或 Codex）写进来
running/    派发后移到这里，旁边 <ID>.state.json（线程名、worktree、分支、开始时间、PR 号、状态）
done/       回执核对通过 + PR 已开
blocked/    核对不过 / 超时 / 执行线自己说卡住，附原因
ledger.jsonl  每次状态变化追加一行（时间、ID、旧状态、新状态、原因）——「一定会发生」的读数
```

1. `tools/agent-dispatch/card-template.md`：任务卡模板，字段：ID、Owner(organizer 会话名)、Base SHA、分支名、模型档位(routine/redline)、
   目标与完成条件、允许改的路径(glob 列表)、禁止范围、必须读的规则、验收命令、停止条件、回执要求。
2. `tools/agent-dispatch/dispatch.ps1`（PowerShell 7）：
   - 扫 inbox，每张卡：`git -C <主仓库> worktree add -b <分支> <C:\Users\Steve\cretas-wt\<ID>> origin/main`（目录不带空格）；
   - 在 Windows Terminal 新标签里起交互式 codex：`wt -w agents new-tab --title <ID> -d <worktree> codex -m <模型> -c model_reasoning_effort=<档位> "<首条提示>"`，
     首条提示 = 读任务卡全文 + 「做完在 worktree 根写 RECEIPT.json（schema 见 receipt.schema.json）并开 PR，卡住也写回执 status=blocked」；
     会话名用 ID（查清 codex 0.160 怎么给交互会话起名/取 thread id，`codex queue --thread` 要用到；写进 state.json）；
   - 并发上限参数 -MaxRunning（默认 3），超了就留在 inbox；
   - 档位映射写在一个配置文件里（tools/agent-dispatch/models.json）：routine 与 redline 各自的模型和 effort，先都用本机 config.toml 现在的模型，routine=medium、redline=high；
   - 加 -DryRun 只打印将要做什么。
3. `tools/agent-dispatch/receipt.schema.json` + `check-receipts.ps1`：对 running/ 里每条线：
   - 有 RECEIPT.json 才核：PR 真存在且 head 分支对得上（gh pr view）、`git diff --name-only origin/main...HEAD` 全在允许路径内、
     回执里的测试命令 exit_code==0 且执行数 > 0（回执必须带命令、退出码、执行数、日志路径）；过 → done/，不过 → 用 `codex queue --thread <名> --message "<不过的原因>"` 打回给那条线，同一张卡打回 ≥ 2 次 → blocked/；
   - 超过卡里写的时限没回执 → blocked/（原因 timeout）；
   - 每次变化追加 ledger.jsonl。
4. `tools/agent-dispatch/ci-watch.ps1`：对 done/ 里的 PR 用 `gh pr checks` 看；出现 fail → `codex queue` 把失败作业名和日志链接塞给那条线并把卡挪回 running/；全绿 → 在 ledger 记 ci_green（合并由 organizer 做，脚本不合）。
5. `tools/agent-dispatch/status.ps1`：一屏输出 inbox/running/done/blocked 各几张、每张卡的线程名、PR、CI、最后一次 ledger 时间。organizer 只看这个。
6. 一个 `tools/agent-dispatch/README.md`（≤ 60 行）：流程图（文字）、命令、Claude/Codex 谁都能当 organizer 的说明、额度平衡规则（见下）。

额度平衡规则（写进 README，脚本里 models.json 体现）：执行线默认派 Codex；Claude Code 只留 organizer 判断、红线终审、要用 Claude 桌面专属能力（浏览器验收截图、session 管理）的事；
organizer 必须由 Claude Code 担任（Steve 2026-11-19 22:5x 改定，取代此前「≥85% 切」）。只有同时满足两条才允许把 organizer 交给 Codex：
Claude 周额度 ≥ 95%，且按近 24 小时消耗速度推算在下次重置前大概率用完（当前% + 每小时消耗% × 距重置小时数 ≥ 100%）。README 写清这两条判据和切换方法（Codex 交互会话里读 status.ps1 + 本 README 接手）。

## 验证（必须做，写进回执）

- 先实测 `codex queue` 能把消息送进一个正在跑的交互会话：开一个测试会话，queue 一条「回复 PONG-<随机数>」，看会话记录/输出里出现该串；记录送达方式与延迟。若不能可靠送达，改用「执行线每完成一步读 running/<ID>.inbox.md」的文件信箱兜底，并说明。
- 端到端干跑一张假卡：ID=CODEX-PILOT-00，内容「在 tools/agent-dispatch/PILOT.md 写一行 hello，开 PR（标题加 [pilot] 不合并）」，跑通 inbox→running→回执→done，ledger 有 ≥ 3 行；再造一张越界卡（允许路径不含它要改的文件）证明 check-receipts 会打回（阴性对照）。干跑完关掉 pilot PR、删掉 pilot worktree 和分支。
- PowerShell 脚本过 `pwsh -NoProfile -Command "& { . ./x.ps1 -DryRun }"` 无报错。

## 回执（写在本文件末尾，然后 commit 只带允许路径并开 PR，标题中文，正文末尾加 🤖 Generated with Codex）

改了哪些文件；codex queue 实测结论；pilot 干跑的 ledger 摘录；阴性对照结果；PR 链接；没做完/没验证的项。

### 执行回执

- 交付范围为 `tools/agent-dispatch/` 内的任务模板、回执 schema、模型配置、四个入口脚本、共用函数和 README，以及 `AGENTS.md` 末尾的「任务卡执行线」与本回执。
- 交付分支 `codex/agent-dispatch-pipeline`。开工时将任务卡给出的 `3fa5cb47e2` 快进到当时 `origin/main` 的 `7f95ae17f7`。Pilot 与阴性对照使用独立 Git 副本和独立队列。共享主仓库当时有 40 个 worktree，派工上限为 30。
- 本机 CLI 0.170.0 通过真实 Windows Terminal 交互标签实测。`codex queue --thread <UUID>` 在执行线运行 28 秒等待命令时成功入队，入队到助手回复 `PONG-46a917faf7` 约 32.6 秒，上一轮结束后开始处理。反馈同时写入 `<ID>.inbox.md`。已验证脚本从 TUI 记录取 UUID，并用 app-server `thread/name/set` 将会话改为任务 ID。另用 0.160.0 的 CLI 帮助及协议 schema 确认该版本支持按 UUID 或精确名称投递、提供会话命名接口。
- 阳性卡 `CODEX-PILOT-00` 的实际提交为 `1d93cd418d55c929f08efeb63b84377bc807733b`。`Get-Content` 断言 `PILOT.md` 恰好一行 `hello`，退出码 0，执行数 1。实际 PR 和提交均通过回执核对，GitHub 检查读数为 `ci_green`。[Pilot PR #1976](https://github.com/cretasfuture-ai/cretas/pull/1976)

| UTC 时间 | 任务卡 | 状态变化 | 原因 |
| --- | --- | --- | --- |
| 14:40:59 | `CODEX-PILOT-00` | inbox → running | 创建 worktree |
| 14:41:00 | `CODEX-PILOT-00` | running → running | Windows Terminal 接收启动命令 |
| 14:44:43 | `CODEX-PILOT-00` | running → running | 绑定会话 `01a1f443-cefb-7321-b09e-9a5180027e36` |
| 14:47:04 | `CODEX-PILOT-NEG` | running → running | 退回越界文件 `tools/agent-dispatch/OUTSIDE.md` |
| 14:47:47 | `CODEX-PILOT-00` | running → done | 回执核对通过，PR #1976 |
| 14:47:58 | `CODEX-PILOT-NEG` | running → blocked | 第二份回执仍越界 |
| 14:48:08 | `CODEX-PILOT-00` | done → done | `ci_green` |

- 阴性卡允许 `tools/agent-dispatch/ALLOWED.md`，实际提交 `OUTSIDE.md`。退回两次均通过 `codex queue` 入队，交互会话均回复「已读」，文件信箱也留下两次反馈。同一份回执复扫没有重复计数。`-MaxRunning 1` 时，第二张卡留在 `inbox`。
- 在独立队列模拟 CI 失败响应，验证带作业名和日志链接的信箱反馈、`done → running` 及回执超时后的 `running → blocked`。真实 GitHub 已覆盖阳性 `ci_green`。PowerShell 7.7.0 下四个入口按 `pwsh -NoProfile -Command "& { . ./入口.ps1 -DryRun }"` 检查通过。解析检查、`check_resident_rules.py`、`check_dispatch_active.py` 和 `git diff --check` 均通过。
- Pilot PR 已关闭，远端测试分支、两条本地测试分支和两个测试 worktree 已删除，三条测试会话已归档。临时信任配置已恢复到测试前哈希。隔离队列的 `ledger.jsonl` 与回执、日志保存在 `C:\Users\Steve\cretas-dispatch-pilot-20261119`。
- 未做 0.160.0 的完整终端交互复测。新隔离仓库首次启动需要在终端确认目录。正式队列当前为空，真实派工的验证使用隔离队列。
- 交付 PR 已开，等待仓库检查。[交付 PR #1979](https://github.com/cretasfuture-ai/cretas/pull/1979)
