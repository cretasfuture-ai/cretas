#requires -Version 7.4
[CmdletBinding()]
param([switch]$DryRun)

. "$PSScriptRoot/common.ps1"

function Confirm-Receipt($Card, $Spec, $State, $Receipt) {
    $worktree = $State.worktree
    $actualRoot = Invoke-Native git @('-C', $worktree, 'rev-parse', '--show-toplevel')
    if ([IO.Path]::GetFullPath($actualRoot) -ne $worktree) { throw '实际 Git 根目录与派工 worktree 不一致' }
    $branch = Invoke-Native git @('-C', $worktree, 'branch', '--show-current')
    $head = Invoke-Native git @('-C', $worktree, 'rev-parse', 'HEAD')
    if ($branch -cne $Spec.branch -or $Receipt.branch -cne $branch -or $Receipt.commit -cne $head) {
        throw '回执分支或提交与 worktree HEAD 不一致'
    }
    if ($State.ci_failed_head -eq $head) { throw 'CI 失败后需要提交修复并提交新回执' }

    $diff = Invoke-Native git @('-C', $worktree, 'diff', '--name-only', '--no-renames', '-z', 'origin/main...HEAD', '--')
    $changed = @(($diff -join "`n") -split [char]0 | Where-Object { $_ })
    foreach ($path in $changed) {
        $denied = $Spec.deny | Where-Object { Test-PathGlob $path $_ }
        $allowed = $Spec.allow | Where-Object { Test-PathGlob $path $_ }
        if ($denied -or !$allowed) { throw "越界改动 $path" }
    }
    if (!$changed.Count -or (Compare-Object $changed $Receipt.changed_files -CaseSensitive)) {
        throw '回执改动文件与实际 Git diff 不一致或为空'
    }

    foreach ($test in $Receipt.tests) {
        $logPath = [IO.Path]::GetFullPath($test.log_path, $worktree)
        if (!(Test-Path -LiteralPath $logPath -PathType Leaf)) { throw "验收日志不存在。$($test.log_path)" }
    }
    $pr = Get-PullRequest $State $Receipt.pr
    if ($pr.state -cne 'OPEN' -or $pr.isCrossRepository -or $pr.baseRefName -cne 'main' -or
        $pr.headRefName -cne $branch -or $pr.headRefOid -cne $head) {
        throw "PR #$($Receipt.pr) 的开放状态、源仓库、分支或提交不匹配"
    }
    $pr
}

$root = Get-QueueRoot
$lock = if (!$DryRun) { Enter-Queue $root }
$fetched = @{}
$unmeasured = 0
try {
    $cards = @(Get-Cards $root 'running')
    Write-Host "检查 $($cards.Count) 张执行中的任务卡。队列 $root"
    foreach ($card in $cards) {
        if ($DryRun) { Write-Host "[试跑] 核对 $($card.BaseName) 的回执、PR、路径、测试和时限"; continue }
        $state = Read-State $card
        Update-Thread $root $card $state
        $path = Join-Path $state.worktree 'RECEIPT.json'
        $raw = if (Test-Path -LiteralPath $path -PathType Leaf) { [IO.File]::ReadAllText($path) }
        $hash = if ($null -ne $raw) {
            [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($raw)))
        }
        if (!$hash -or $hash -eq $state.last_receipt_hash) {
            if ([DateTimeOffset]::UtcNow -ge [DateTimeOffset]$state.deadline) {
                Set-CardState $root $card $state 'blocked' 'timeout'
            }
            continue
        }
        if (!$fetched.ContainsKey($state.repository)) {
            Invoke-Native git @('-C', $state.repository, 'fetch', '--quiet', 'origin', 'refs/heads/main:refs/remotes/origin/main') | Out-Null
            $fetched[$state.repository] = $true
        }
        try {
            if ((Get-FileHash -LiteralPath $card.FullName).Hash -cne $state.card_hash) { throw '任务卡内容自派工后已变化' }
            $spec = Read-Card $card
            if (!(Test-Json -Json $raw -SchemaFile $DispatchSchema -ErrorAction SilentlyContinue)) {
                throw '回执格式不符合 receipt.schema.json，核对必填项、退出码和测试执行数'
            }
            $receipt = ConvertFrom-Json -InputObject $raw -AsHashtable
            if ($receipt.id -cne $card.BaseName) { throw '回执 ID 与任务卡不一致' }
            if ($receipt.status -eq 'blocked') {
                Set-CardState $root $card $state 'blocked' $receipt.reason
                continue
            }
            $pr = Confirm-Receipt $card $spec $state $receipt
        } catch [System.Net.Http.HttpRequestException] {
            Write-Warning "暂不能核对 $($card.BaseName)。$($_.Exception.Message)"
            $unmeasured++
            continue
        } catch {
            $reason = $_.Exception.Message
            $state.returns++
            $next = if ($state.returns -ge 2) { 'blocked' } else { 'running' }
            $message = "回执未通过。$reason"
            if ($next -eq 'blocked') { $message += ' 已达到两次退回，停止执行并由协调者接手。' }
            Send-Feedback $root $card $state $message
            $state.last_receipt_hash = $hash
            Set-CardState $root $card $state $next "回执退回第 $($state.returns) 次。$reason"
            continue
        }
        $state.pr = $pr.number
        $state.pr_url = $pr.url
        $state.head = $pr.headRefOid
        $state.last_receipt_hash = $hash
        $state.ci = '未检查'
        Set-CardState $root $card $state 'done' "回执核对通过，PR #$($pr.number)"
    }
    if ($unmeasured) { throw "$unmeasured 张任务暂不能核对 PR，下一轮重试" }
} finally {
    if ($lock) { $lock.Dispose() }
}
