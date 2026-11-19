#requires -Version 7.4
[CmdletBinding()]
param(
    [ValidateRange(1, 30)][int]$MaxRunning = 3,
    [string]$Repository = 'C:\Users\Steve\my-prototype-logistics',
    [string]$WorktreeRoot = 'C:\Users\Steve\cretas-wt',
    [switch]$DryRun
)

. "$PSScriptRoot/common.ps1"
$root = Get-QueueRoot
$Repository = [IO.Path]::GetFullPath($Repository)
$WorktreeRoot = [IO.Path]::GetFullPath($WorktreeRoot)
if ($WorktreeRoot -match '\s') { throw 'worktree 根目录不能含空白字符' }
$models = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'models.json') -Raw | ConvertFrom-Json -AsHashtable
$lock = if (!$DryRun) { Enter-Queue $root }
try {
    $cards = @(Get-Cards $root 'inbox')
    $runningCount = @(Get-Cards $root 'running').Count
    Write-Host "待派发 $($cards.Count) 张，在飞 $runningCount/$MaxRunning 张。队列 $root"
    if (!$cards.Count -or $runningCount -ge $MaxRunning) { return }

    $active = Get-Content -LiteralPath (Join-Path $Repository 'docs/dispatch/ACTIVE.md')
    $null = Get-GitHubRepo $Repository
    if (!$DryRun) {
        Invoke-Native git @('-C', $Repository, 'fetch', '--quiet', 'origin', 'refs/heads/main:refs/remotes/origin/main') | Out-Null
    }
    $base = Invoke-Native git @('-C', $Repository, 'rev-parse', 'refs/remotes/origin/main')
    $worktrees = @(Invoke-Native git @('-C', $Repository, 'worktree', 'list', '--porcelain') |
        Where-Object { $_ -like 'worktree *' }).Count
    foreach ($card in $cards) {
        if ($runningCount -ge $MaxRunning -or $worktrees -ge 30) { break }
        $id = $card.BaseName
        $duplicates = foreach ($folder in @('running', 'done', 'blocked')) {
            if (Test-Path -LiteralPath (Join-Path (Join-Path $root $folder) $card.Name)) { $folder }
        }
        if ($duplicates) { Write-Warning "$id 已位于 $duplicates，保留待派发卡供协调者核对"; continue }

        $state = @{
            id = $id; thread_name = $id; thread_id = $null; repository = $Repository
            worktree = Join-Path $WorktreeRoot $id
            branch = $null; pr = $null; ci = '未检查'; returns = 0
        }
        try {
            $spec = Read-Card $card
            $state.branch = $spec.branch
            $registered = $active | Where-Object { $_ -match ('`' + [regex]::Escape($id) + '`') -and
                $_.Contains($spec.base_sha) -and $_.Contains($spec.owner) }
            if (!$registered) { throw 'ACTIVE 缺少关联的任务 ID、Base SHA 或 Owner' }
            if (!$base.StartsWith($spec.base_sha, [StringComparison]::Ordinal)) { throw "Base SHA 已过期，当前 origin/main 为 $base" }
            $branch = Invoke-Native git @('check-ref-format', '--branch', $spec.branch)
            if ($branch -cne $spec.branch) { throw '分支必须使用明确的 Git 分支名' }
            if (Test-Path -LiteralPath $state.worktree) { throw '目标 worktree 路径已存在' }
            $model = $models[$spec.tier]
            if (!$model.model -or !$model.effort) { throw "缺少 $($spec.tier) 模型配置" }
            $worktree = $state.worktree
            $taskPath = Join-Path (Join-Path $root 'running') $card.Name
            $mail = Join-Path (Join-Path $root 'running') "$id.inbox.md"
            $prompt = @"
任务 $id。请用中文。先完整读取任务卡 $taskPath 和卡中规定的规则。按照卡内 scope 和验收命令工作。
每完成一步，若 $mail 存在，读取其最新消息；等待核对时也查此文件。消息只补充本卡的核对结果。
核对完成条件后，只暂存允许路径，提交并开以 main 为目标的 PR，在 worktree 根写 RECEIPT.json。卡住也写回执 status=blocked。
回执结构见 $DispatchSchema 。日志与 RECEIPT.json 是本地证据。回执请先写临时文件，再重命名为 RECEIPT.json。
收到 CI 或回执退回后，从同一工作区继续，按要求更新回执和 PR。
"@
            # 编码启动参数避免 Windows Terminal 或 shell 再次解析任务提示。
            $launch = "& codex -m '$($model.model.Replace("'", "''"))' -c 'model_reasoning_effort=$($model.effort.Replace("'", "''"))' '$($prompt.Replace("'", "''"))'"
            $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($launch))
            if ($DryRun) {
                Write-Host "[试跑] git -C `"$Repository`" worktree add -b $($spec.branch) `"$worktree`" origin/main（$base）"
                Write-Host "[试跑] wt -w agents new-tab --title $id -d `"$worktree`" → codex $($model.model) / $($model.effort)；任务卡 $taskPath"
                $runningCount++
                $worktrees++
                continue
            }

            $null = Get-Command wt.exe, pwsh, codex
            [IO.Directory]::CreateDirectory($WorktreeRoot) | Out-Null
            Invoke-Native git @('-C', $Repository, 'worktree', 'add', '--quiet', '-b', $spec.branch, $worktree, $base) | Out-Null
            $worktrees++
            $state.base_sha = $base
            $state.card_hash = (Get-FileHash -LiteralPath $card.FullName).Hash
            $state.started_at = [DateTimeOffset]::UtcNow.ToString('o')
            $state.deadline = [DateTimeOffset]::UtcNow.AddMinutes($spec.timeout_minutes).ToString('o')
            Set-CardState $root $card $state 'running' '已创建 worktree，启动终端'
            $card = Get-Item -LiteralPath $taskPath
            Invoke-Native wt.exe @('-w', 'agents', 'new-tab', '--title', $id, '-d', $worktree, 'pwsh', '-NoLogo', '-NoProfile', '-EncodedCommand', $encoded) | Out-Null
            Set-CardState $root $card $state 'running' 'Windows Terminal 已接收启动命令，等待会话 UUID'
            Update-Thread $root $card $state
            $runningCount++
        } catch {
            if ($DryRun) { Write-Warning "[试跑] $id 将转 blocked。$($_.Exception.Message)" }
            else { Set-CardState $root $card $state 'blocked' $_.Exception.Message }
        }
    }
    if ($worktrees -ge 30) { Write-Warning '当前仓库达到 30 个 worktree，剩余卡留在 inbox' }
} finally {
    if ($lock) { $lock.Dispose() }
}
