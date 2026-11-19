#requires -Version 7.4

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$DispatchSchema = Join-Path $PSScriptRoot 'receipt.schema.json'
$DispatchStates = @('inbox', 'running', 'done', 'blocked')

function Get-QueueRoot {
    $root = if ($env:AGENT_QUEUE) { $env:AGENT_QUEUE } else { 'C:\Users\Steve\agent-queue' }
    [IO.Path]::GetFullPath($root)
}

function Get-Cards($Root, $Status) {
    $directory = Join-Path $Root $Status
    if (Test-Path -LiteralPath $directory -PathType Container) {
        Get-ChildItem -LiteralPath $directory -File -Filter '*.md' |
            Where-Object { $_.BaseName -cmatch '^[A-Z0-9][A-Z0-9-]{0,63}$' } |
            Sort-Object LastWriteTimeUtc, Name
    }
}

function Enter-Queue($Root) {
    foreach ($status in $DispatchStates) {
        [IO.Directory]::CreateDirectory((Join-Path $Root $status)) | Out-Null
    }
    # 所有写脚本共用这把文件锁，保留锁文件以便下次打开同一把锁。
    [IO.File]::Open((Join-Path $Root '.queue.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
}

function Invoke-Native([string]$Program, [string[]]$Arguments, [int[]]$Accept = @(0)) {
    $output = & $Program @Arguments 2>&1
    $code = $LASTEXITCODE
    if ($code -notin $Accept) { throw "$Program 退出码 $code。$($output -join ' ')" }
    $output | Where-Object { $_ -is [string] }
}

function Write-JsonFile($Path, $Data) {
    $temp = "$Path.$PID.tmp"
    [IO.File]::WriteAllText($temp, (ConvertTo-Json -InputObject $Data -Depth 12))
    [IO.File]::Move($temp, $Path, $true)
}

function Read-State($Card) {
    $path = Join-Path $Card.DirectoryName "$($Card.BaseName).state.json"
    Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -AsHashtable
}

function Write-Ledger($Root, $Id, $Old, $New, $Reason) {
    $event = [ordered]@{
        time = [DateTimeOffset]::UtcNow.ToString('o')
        id = $Id
        old_status = $Old
        new_status = $New
        reason = $Reason
    }
    $line = ConvertTo-Json -InputObject $event -Compress
    [IO.File]::AppendAllText((Join-Path $Root 'ledger.jsonl'), "$line`n")
}

function Set-CardState($Root, $Card, $State, $Next, $Reason) {
    $old = $Card.Directory.Name
    $target = Join-Path $Root $Next
    $State.status = $Next
    $State.reason = $Reason
    $stateName = "$($Card.BaseName).state.json"
    $statePath = Join-Path $target $stateName
    if ($old -ne $Next -and (Test-Path -LiteralPath (Join-Path $target $Card.Name))) {
        throw "目标目录已存在同名任务 $($Card.BaseName)"
    }
    Write-JsonFile $statePath $State
    if ($old -ne $Next) {
        [IO.File]::Move($Card.FullName, (Join-Path $target $Card.Name))
        foreach ($suffix in @('state.json', 'inbox.md')) {
            $sidecar = Join-Path $Card.DirectoryName "$($Card.BaseName).$suffix"
            if (Test-Path -LiteralPath $sidecar) {
                if ($suffix -eq 'state.json') { [IO.File]::Delete($sidecar) }
                else { [IO.File]::Move($sidecar, (Join-Path $target "$($Card.BaseName).$suffix"), $true) }
            }
        }
    }
    Write-Ledger $Root $Card.BaseName $old $Next $Reason
    Write-Host "$($Card.BaseName) $old → $Next，$Reason"
}

function Read-Card($Card) {
    $body = Get-Content -LiteralPath $Card.FullName -Raw
    $json = [regex]::Match($body, '(?ms)^```json\s*\r?\n(.*?)^```[ \t]*\r?$')
    if (!$json.Success) { throw '任务卡缺首个 JSON 代码块' }
    $spec = ConvertFrom-Json -InputObject $json.Groups[1].Value -AsHashtable
    if ($spec.id -cne $Card.BaseName -or $spec.owner -isnot [string] -or
        [string]::IsNullOrWhiteSpace($spec.owner) -or $spec.base_sha -cnotmatch '^[0-9a-f]{7,40}$' -or
        $spec.branch -isnot [string] -or [string]::IsNullOrWhiteSpace($spec.branch) -or $spec.branch -ieq 'main' -or
        $spec.tier -cnotin @('routine', 'redline') -or $spec.timeout_minutes -isnot [long] -or
        $spec.timeout_minutes -lt 1 -or
        $spec.allow -isnot [array] -or !$spec.allow.Count -or $spec.deny -isnot [array]) {
        throw '任务卡元数据不符合模板'
    }
    foreach ($glob in ($spec.allow + $spec.deny)) {
        if ($glob -isnot [string] -or $glob -notmatch '^[^/\\:]+(?:/[^/\\:]+)*$' -or
            $glob -match '(^|/)\.\.?(/|$)|\p{C}|^!') { throw "无效的路径 glob $glob" }
    }
    $spec
}

function Test-PathGlob([string]$Path, [string]$Glob) {
    $pattern = [regex]::Escape($Glob).Replace('\*\*/', '(?:.*/)?').Replace('\*\*', '.*')
    $pattern = $pattern.Replace('\*', '[^/]*').Replace('\?', '[^/]')
    [regex]::IsMatch($Path, "\A$pattern\z", 'CultureInvariant')
}

function Get-CodexHome {
    if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $HOME '.codex' }
}

function Find-Thread($State) {
    $created = [DateTimeOffset]$State.started_at
    $sessions = Join-Path (Get-CodexHome) 'sessions'
    $matches = foreach ($day in @($created.LocalDateTime.Date, [DateTime]::Today) | Select-Object -Unique) {
        $folder = Join-Path $sessions ($day.ToString('yyyy/MM/dd'))
        if (!(Test-Path -LiteralPath $folder)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $folder -Filter 'rollout-*.jsonl' -File) {
            if ($file.LastWriteTimeUtc -lt $created.UtcDateTime) { continue }
            try {
                $header = Get-Content -LiteralPath $file.FullName -TotalCount 1 | ConvertFrom-Json
                if ($header.type -eq 'session_meta' -and $header.payload.originator -eq 'codex-tui' -and
                    ([DateTimeOffset]$header.payload.timestamp) -ge $created -and
                    $header.payload.cwd -eq $State.worktree -and $header.payload.id -match '^[a-f0-9-]{36}$') {
                    $header.payload.id
                }
            } catch { continue } # 会话文件可能刚创建，还没有完整的首行。
        }
    }
    if (@($matches).Count -eq 1) { $matches }
}

function Set-ThreadName($Id, $Name) {
    $start = [Diagnostics.ProcessStartInfo]::new((Get-Command pwsh).Source)
    foreach ($arg in @('-NoProfile', '-Command', 'codex app-server --stdio')) { $start.ArgumentList.Add($arg) }
    $start.UseShellExecute = $false
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.CreateNoWindow = $true
    $process = [Diagnostics.Process]::Start($start)
    try {
        $null = $process.StandardError.ReadToEndAsync()
        $requests = @(
            @{ id = 1; method = 'initialize'; params = @{ clientInfo = @{ name = 'agent_dispatch'; version = '1' } } },
            @{ method = 'initialized' },
            @{ id = 2; method = 'thread/name/set'; params = @{ threadId = $Id; name = $Name } }
        )
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds(8)
        foreach ($request in $requests) {
            $process.StandardInput.WriteLine((ConvertTo-Json -InputObject $request -Depth 6 -Compress))
            $process.StandardInput.Flush()
            if (!$request.id) { continue }
            do {
                $remaining = $deadline - [DateTimeOffset]::UtcNow
                if ($remaining.TotalMilliseconds -le 0) { throw '会话命名接口超时' }
                $line = $process.StandardOutput.ReadLineAsync().WaitAsync($remaining).GetAwaiter().GetResult()
                if ($null -eq $line) { throw '会话命名接口提前关闭' }
                $response = ConvertFrom-Json -InputObject $line -AsHashtable
            } until ($response.id -eq $request.id)
            if ($response.error) { throw $response.error.message }
        }
    } finally {
        $process.StandardInput.Close()
        if (!$process.WaitForExit(1000)) { $process.Kill($true); $process.WaitForExit() }
        $process.Dispose()
    }
}

function Update-Thread($Root, $Card, $State) {
    if (!$State.thread_id) { $State.thread_id = Find-Thread $State }
    if (!$State.thread_id -or $State.thread_named) { return }
    try {
        Set-ThreadName $State.thread_id $State.thread_name
        $State.thread_named = $true
        Set-CardState $Root $Card $State $Card.Directory.Name "已绑定会话 $($State.thread_name) / $($State.thread_id)"
    } catch {
        if (!$State.thread_name_error) {
            $State.thread_name_error = $_.Exception.Message
            Set-CardState $Root $Card $State $Card.Directory.Name "已绑定 UUID，会话改名失败，将重试。$($State.thread_name_error)"
        }
    }
}

function Get-GitHubRepo($Repository) {
    $remote = Invoke-Native git @('-C', $Repository, 'remote', 'get-url', 'origin')
    if ($remote -notmatch '^https://github\.com/([\w.-]+/[\w.-]+?)(?:\.git)?/?$') {
        throw 'origin 必须为 GitHub HTTPS 仓库'
    }
    $Matches[1]
}

function Get-PullRequest($State, $Number) {
    $repo = Get-GitHubRepo $State.repository
    try {
        $json = Invoke-Native gh @('pr', 'view', "$Number", '--repo', $repo, '--json',
            'number,headRefName,headRefOid,baseRefName,isCrossRepository,state,url')
        ConvertFrom-Json -InputObject ($json -join "`n") -AsHashtable
    } catch {
        if ($_.Exception.Message -match 'Could not resolve to a PullRequest') { throw "PR #$Number 不存在" }
        throw [System.Net.Http.HttpRequestException]::new("无法查询 PR #$Number。$($_.Exception.Message)")
    }
}

function Get-PullRequestChecks($Repo, $Number) {
    $output = & gh pr checks "$Number" --repo "$Repo" --json name,bucket,link 2>&1
    $code = $LASTEXITCODE
    $json = @($output | Where-Object { $_ -is [string] }) -join "`n"
    if ($json -and $code -in @(0, 1, 8)) { return (ConvertFrom-Json -InputObject $json) }
    if ($code -eq 1 -and ($output -join ' ') -match '^no checks reported on the ') { return }
    throw "gh pr checks 退出码 $code。$($output -join ' ')"
}

function Send-Feedback($Root, $Card, $State, $Message) {
    $path = Join-Path $Card.DirectoryName "$($Card.BaseName).inbox.md"
    $previous = if (Test-Path -LiteralPath $path) { [IO.File]::ReadAllText($path) } else { '' }
    $temp = "$path.$PID.tmp"
    [IO.File]::WriteAllText($temp, "$previous## $([DateTimeOffset]::UtcNow.ToString('o'))`n`n$Message`n`n")
    [IO.File]::Move($temp, $path, $true)
    if (!$State.thread_id) { $State.thread_id = Find-Thread $State }
    $transport = '文件信箱，尚未取得会话 UUID'
    if ($State.thread_id) {
        try {
            Invoke-Native codex @('queue', '--thread', $State.thread_id, '--message', $Message) | Out-Null
            $transport = '文件信箱，Codex queue 已入队'
        } catch { $transport = "文件信箱，Codex queue 投递失败。$($_.Exception.Message)" }
    }
    Write-Ledger $Root $Card.BaseName $Card.Directory.Name $Card.Directory.Name $transport
}
