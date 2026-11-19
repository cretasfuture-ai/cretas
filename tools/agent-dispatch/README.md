# Codex 任务卡执行线

```text
organizer 登记 ACTIVE → inbox → running（独立 worktree + Windows Terminal 标签）
running → done（回执和 PR 核对通过）→ CI 全绿后由 organizer 处理
running → running（退回一次）→ blocked（再次退回、超时或执行线主动报告）
done → running（CI 失败或 PR 变化）→ 更新代码与回执 → done
```

## 启动

- Windows PowerShell 7.4+、Windows Terminal、已登录的 `codex` 和 `gh`、GitHub HTTPS `origin`。本机以 Codex CLI 0.170.0 验证。
- 首次使用新仓库时，按 Windows Terminal 内的提示核对并确认目录。新 hook 如需启用，先审查其内容。
- 默认队列 `C:\Users\Steve\agent-queue`，用 `AGENT_QUEUE` 覆盖。`dispatch.ps1` 的 `-Repository` 和 `-WorktreeRoot` 可指定隔离环境。
- 复制 `card-template.md`，在 ACTIVE 登记唯一 ID、Owner、Base SHA、scope 与验收命令，再把完整 `<ID>.md` 放进 `inbox`。建议使用 `codex/` 分支，Base 填当前 `origin/main`。路径大小写敏感，支持 `*`、`?`、`**`，禁止路径优先。
- PowerShell 中从本目录顺序运行以下命令。每条运行一轮，可由 organizer 定期调用。达到并发上限或仓库已有 30 个 worktree 时，剩余任务留在 `inbox`。

```powershell
./dispatch.ps1 -DryRun
./dispatch.ps1 -MaxRunning 3
./check-receipts.ps1
./ci-watch.ps1
./status.ps1
```

## 核对与接手

- Windows Terminal 的 `agents` 窗口按任务 ID 显示标签。核对脚本根据派工后创建的唯一 TUI 记录绑定 UUID，并把 Codex 会话改名为任务 ID。`*.state.json` 记录名称、UUID、worktree、分支、PR、时限和状态。
- 每次状态变化追加到 `ledger.jsonl`。`status.ps1` 汇总队列、PR、CI 和最后一笔记录。
- 派工提示要求执行线每完成一步读 `running/<ID>.inbox.md`。反馈同时写入文件和尝试 `codex queue`。忙碌时，Codex 可在当前轮结束后接收队列消息。卡终止时信箱随卡归档。
- `RECEIPT.json` 按 schema 写入 worktree 根目录，日志保留可查。修正回执后原子替换文件。重复扫描同一份回执只计一次。CI 失败后提交修复，再按当前 HEAD 更新回执。
- `done` 表示回执已通过且已开 PR。`ci_green` 要有实际通过的检查。合并由 organizer 核对后执行。
- Claude Code 或 Codex 均可做 organizer。默认派 Codex 执行。Claude Code 保留 organizer 判断、红线终审及 Claude 桌面专属操作，例如浏览器验收截图和会话管理。
- Claude 周额度达到 85% 时，在 Codex 交互会话打开同一仓库，先运行 `status.ps1` 并读本 README，沿用同一 `AGENT_QUEUE` 和 ACTIVE 逐条接手。
- `models.json` 管理执行模型。routine 使用 medium，redline 使用 high。两个档位当前都使用 `gpt-6.2-sol`。
