#requires -Version 7.4
[CmdletBinding()]
param([switch]$DryRun)

. "$PSScriptRoot/common.ps1"
$root = Get-QueueRoot
$lock = if (!$DryRun) { Enter-Queue $root }
try {
    $cards = @(Get-Cards $root 'done')
    Write-Host "检查 $($cards.Count) 张已验回执的任务卡。队列 $root"
    foreach ($card in $cards) {
        if ($DryRun) { Write-Host "[试跑] 查询 $($card.BaseName) 的 PR 检查"; continue }
        $state = Read-State $card
        $repo = Get-GitHubRepo $state.repository
        $pr = Get-PullRequest $state $state.pr
        if ($pr.state -ne 'OPEN') {
            if ($state.ci -ne $pr.state) {
                $state.ci = $pr.state
                Set-CardState $root $card $state 'done' "PR #$($state.pr) 状态 $($pr.state)"
            }
            continue
        }
        if ($pr.headRefOid -cne $state.head -or $pr.baseRefName -cne 'main') {
            Send-Feedback $root $card $state 'PR 在核对后发生变更，请为当前 HEAD 提交新回执。'
            $state.ci = '待重新核对'
            $state.deadline = [DateTimeOffset]::UtcNow.AddMinutes((Read-Card $card).timeout_minutes).ToString('o')
            Set-CardState $root $card $state 'running' 'PR HEAD 或目标分支发生变更'
            continue
        }

        $checks = @(Get-PullRequestChecks $repo $state.pr)
        $current = Get-PullRequest $state $state.pr
        if ($current.headRefOid -cne $state.head -or $current.state -cne 'OPEN' -or $current.baseRefName -cne 'main') {
            Write-Warning "PR #$($state.pr) 在读取 CI 期间发生变化，下轮重新核对"
            continue
        }
        $failed = @($checks | Where-Object { $_.bucket -in @('fail', 'cancel') })
        if ($failed.Count) {
            $spec = Read-Card $card
            $details = ($failed | ForEach-Object { "- $($_.name) $($_.link)" }) -join "`n"
            Send-Feedback $root $card $state "PR #$($state.pr) 的 CI 失败，请核对以下作业并按任务卡修复。`n$details"
            $state.ci = '失败'
            $state.ci_failed_head = $state.head
            $state.deadline = [DateTimeOffset]::UtcNow.AddMinutes($spec.timeout_minutes).ToString('o')
            Set-CardState $root $card $state 'running' "CI 失败。$($failed.name -join '、')"
            continue
        }
        $green = $checks.Count -gt 0 -and @($checks | Where-Object { $_.bucket -eq 'pass' }).Count -gt 0 -and
            !($checks | Where-Object { $_.bucket -notin @('pass', 'skipping') })
        $nextCI = if ($green) { 'ci_green' } elseif ($checks.Count) { '等待检查' } else { '尚无检查' }
        if ($state.ci -ne $nextCI) {
            $state.ci = $nextCI
            Set-CardState $root $card $state 'done' $nextCI
        }
    }
} finally {
    if ($lock) { $lock.Dispose() }
}
