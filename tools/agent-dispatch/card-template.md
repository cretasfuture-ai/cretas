# 任务卡 TASK-EXAMPLE-01

首个 JSON 代码块供脚本读取。文件名必须是 `<id>.md`。先登记 ACTIVE 的任务、Owner、Base SHA、范围和验收命令，再把卡放入 inbox。

```json
{
  "id": "TASK-EXAMPLE-01",
  "owner": "organizer 会话名",
  "base_sha": "替换为当前 origin/main 的 SHA",
  "branch": "codex/task-example-01",
  "tier": "routine",
  "timeout_minutes": 90,
  "allow": ["tools/example/**"],
  "deny": ["tools/example/private/**"]
}
```

## 目标与完成条件

写清可观察的产出与证据。

## 禁止范围

列出禁止访问或变更的系统、数据与路径。路径同时写进 `deny`。

## 必须读的规则

`AGENTS.md`、`CLAUDE.md` 及任务适用的规则。

## 验收命令

列出原样执行的命令，以及如何确认真实执行数。

## 停止条件

列出应停下并写 blocked 回执的情形。

## 回执要求

按派工提示的 `receipt.schema.json` 写工作区根目录的 `RECEIPT.json`。记录实际改动、提交、PR、测试命令、退出码、执行数和可复查的本地日志路径。卡住时写 `status=blocked` 和原因。
