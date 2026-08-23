# Cretas GitHub 远端操作安全基线

本文件约束 Cretas 的分支、Pull Request、Actions 和 GitHub API 自动化。目标是让日常开发以本地 Git/worktree 为主，把 GitHub 写操作维持在必要、串行、可停止的范围内。

这里的数量限制是项目内部的保守线，不是 GitHub 官方配额。不得把官方限额当作日常运行目标。

## 1. 本地优先，远端按需

- 开发中分支和 worktree 默认只保留在本地。
- 只有已经完成范围审查、准备创建 PR 的分支才允许推送。
- 不为每个 Codex task 自动创建远端分支。
- 迁移或灾备只上传 `main`、必要的 release 分支和仍在工作的分支；不得批量上传历史 `codex/*` 分支。
- 每个任务最多保留一个 PR；更新同一分支，不反复创建替代 PR。

## 2. 远端分支与批量写入上限

- 活跃远端 `codex/*` 分支软上限为 20，硬上限为 30。达到 20 时先清理已合并分支；达到 30 时停止创建。
- 单批最多创建或删除 10 条远端分支。
- 删除前必须生成清单并由维护者确认；删除操作串行执行。
- 所有 GitHub 写操作进入单写者队列，一次只执行一个；相邻写操作至少间隔 1 秒。
- 分支创建、删除、PR 创建/更新/合并不得并发执行。

## 3. Actions 触发与并发

- 临时 `codex/*` 分支的普通 push 不触发完整 CI；检查由 PR 的 `pull_request` 事件承担。
- 合入 `main` 后才运行需要生成制品、全量构建或合并后告警的工作流。
- 所有自动工作流必须设置 `concurrency`。普通检查允许新提交取消同一分支的旧运行。
- 会创建/更新 PR、签名制品、部署或产生其他不可中断写入的工作流必须串行，且 `cancel-in-progress: false`。
- Daily Integration 最多每天运行一次；不得由多个机器人重复触发。
- 使用 `paths`/`paths-ignore` 将 Java、Python、Web、RN 和纯文档变更分开，避免无关全套运行。
- 新工作流默认保持禁用或仅手动触发；通过范围审查和一次受控验证后再逐条启用。

## 4. API 限速与停止条件

- 优先使用 webhook/事件触发，不高频轮询 PR、Actions、分支或检查状态。
- 能批量读取的数据只读取一次并缓存；不得为数百条分支反复逐条查询。
- 任何 `403`、`429`、secondary rate limit、abuse detection 或 `Retry-After` 响应都立即停止全部远端自动化写入。
- 收到限流后不得在循环中继续重试。仅在人工复核后，按 `Retry-After` 或递增等待恢复；重复失败必须终止。
- 错误包含 `account suspended`、`repository disabled` 或类似封控信息时，完全停止远端操作，等待人工处理。

## 5. 身份、令牌与凭证

- 正式公司仓库必须归属于 GitHub Organization；每位成员使用自己的账号，不共享公司登录账号。
- Actions 优先使用最小权限的仓库 `GITHUB_TOKEN`；跨仓库或复杂自动化使用安装在 Organization 的 GitHub App。
- 不以个人 PAT 承担公司的批量自动化；确需 PAT 时必须限定最小权限、有效期和用途。
- Secret、SSH key、部署凭证只存放在 GitHub Secrets/Environment Secrets 或批准的外部密钥系统中，不得进入 tracked 文件、日志或 artifact。
- 默认工作流权限为只读；需要写权限的 job 必须在 workflow/job 级明确声明最小权限。

## 6. 新仓库启用顺序

1. 确认仓库 Owner 类型为 `Organization`，成员和恢复联系人可用。
2. 保持非必要 Actions 禁用，完成干净代码迁移和 tracked secret 扫描。
3. 配置 `main` 保护、必需检查、禁止 force push，并验证管理员绕过策略。
4. 先启用轻量秘密扫描，再按 Java/Python/Web/RN 的目标门禁逐条启用。
5. 最后启用制品、Daily Integration 和部署相关工作流；每启用一条先完成一次受控验证。
6. 记录每次启用、关闭、限流或异常停止的原因与结果。

## 7. 当前迁移未完成条件

仓库只有同时满足以下条件，才可标记为公司 GitHub 迁移完成：

- Owner 在 GitHub API/UI 中确认为 `Organization`，而不是个人 `User`。
- `main` 已启用可验证的保护规则和必需检查。
- 必要工作流已逐条审计、限流并通过受控运行；其余工作流保持禁用。
- 远端分支数量在内部上限内，且没有历史临时分支批量迁入。
- 仓库、Actions、部署脚本和文档中不存在旧仓库硬编码或 tracked 凭证。

## 官方依据

- [GitHub Acceptable Use Policies](https://docs.github.com/en/site-policy/acceptable-use-policies/github-acceptable-use-policies)
- [Control workflow concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency)
- [REST API best practices](https://docs.github.com/en/rest/using-the-rest-api/best-practices-for-using-the-rest-api)
- [REST API rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
