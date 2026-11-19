#requires -Version 7.4
[CmdletBinding()]
param([switch]$DryRun)

. "$PSScriptRoot/common.ps1"
$root = Get-QueueRoot
$last = @{}
$ledger = Join-Path $root 'ledger.jsonl'
if (Test-Path -LiteralPath $ledger) {
    foreach ($line in [IO.File]::ReadLines($ledger)) {
        $event = ConvertFrom-Json -InputObject $line -AsHashtable
        $last[$event.id] = $event
    }
}
$mode = if ($DryRun) { '试跑，只读' } else { '只读' }
Write-Host "执行线 $mode。队列 $root"
foreach ($status in $DispatchStates) {
    $cards = @(Get-Cards $root $status)
    Write-Host "`n$status  $($cards.Count) 张"
    $rows = foreach ($card in $cards) {
        $id = $card.BaseName
        $state = if ($status -ne 'inbox') { Read-State $card } else { @{} }
        [pscustomobject]@{
            任务 = $id
            会话 = $state.thread_name ?? '—'
            PR = $state.pr ?? '—'
            CI = $state.ci ?? '—'
            最后记录 = if ($last[$id]) { ([DateTimeOffset]$last[$id].time).ToLocalTime().ToString('MM-dd HH:mm:ss') } else { '—' }
            原因 = $state.reason ?? '—'
        }
    }
    if ($rows) { $rows | Format-Table -AutoSize | Out-Host }
}
