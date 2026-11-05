param(
    [string]$Dist,
    [ValidatePattern('^/preview/[a-f0-9]{32}/$')][string]$PreviewPath,
    [string]$SessionConfig = (Join-Path $env:LOCALAPPDATA 'Cretas/Blacklake/smartbi-test.dpapi'),
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path,
    [string]$StateRoot = 'D:\CretasSmartBILab\remote-preview-runtime',
    [switch]$Install,
    [switch]$Stop,
    [switch]$Uninstall
)
$ErrorActionPreference='Stop'
function Stop-PreviewProcess($Record) {
    if ($null -eq $Record) { return }
    $taskProcess=Get-Process -Id $Record.id -ErrorAction SilentlyContinue
    if ($null -eq $taskProcess) { return }
    $taskCommand=(Get-CimInstance Win32_Process -Filter "ProcessId=$($Record.id)").CommandLine
    if ($taskProcess.StartTime.ToUniversalTime().Ticks -ne [long]$Record.startedTicks -or -not $taskCommand.Contains($Record.signature)) {
        throw 'Preview process identity changed; refusing to stop another process'
    }
    Stop-Process -Id $Record.id -Force
}
function Preview-ProcessRecord($Process,[string]$Signature) {
    return [ordered]@{id=$Process.Id;startedTicks=$Process.StartTime.ToUniversalTime().Ticks;signature=$Signature}
}
if ($Stop -or $Uninstall) {
    $taskScheduled=Get-ScheduledTask -TaskName 'Cretas-Blacklake-Remote-Preview' -ErrorAction SilentlyContinue
    if($taskScheduled){Disable-ScheduledTask -InputObject $taskScheduled | Out-Null}
    & scp.exe -q (Join-Path $RepoRoot 'scripts/deploy/disable-blacklake-preview.sh') 'root@139.196.165.140:/tmp/cretas-disable-blacklake-preview.sh'
    if($LASTEXITCODE -ne 0){throw 'Preview disable script upload failed'}
    # Revoke the HTTPS entry first, even if a stale process identity needs investigation.
    & ssh.exe -o BatchMode=yes 'root@139.196.165.140' 'bash /tmp/cretas-disable-blacklake-preview.sh --close-only'
    if($LASTEXITCODE -ne 0){throw 'Public preview entry could not be disabled'}
    [IO.File]::WriteAllText((Join-Path $StateRoot 'stop.request'),'stop')
    $taskStatePath=Join-Path $StateRoot 'processes.json'
    # The watcher sees this request within one second, then runs its own cleanup.
    # Wait for that handshake before using the identity-checked forced fallback.
    Start-Sleep -Seconds 2
    if(Test-Path -LiteralPath $taskStatePath){
        $taskInitialState=Get-Content -LiteralPath $taskStatePath -Raw | ConvertFrom-Json
        for($taskAttempt=0;$taskAttempt -lt 15;$taskAttempt++){
            if(-not $taskInitialState.watcher -or -not(Get-Process -Id $taskInitialState.watcher.id -ErrorAction SilentlyContinue)){break}
            Start-Sleep -Seconds 1
        }
    }
    if(Test-Path -LiteralPath $taskStatePath){
        $taskState=Get-Content -LiteralPath $taskStatePath -Raw | ConvertFrom-Json
        Stop-PreviewProcess $taskState.watcher
        Stop-PreviewProcess $taskState.gateway
        Stop-PreviewProcess $taskState.tunnel
    }
    if($Uninstall -and $taskScheduled){Unregister-ScheduledTask -TaskName 'Cretas-Blacklake-Remote-Preview' -Confirm:$false}
    & ssh.exe -o BatchMode=yes 'root@139.196.165.140' 'bash /tmp/cretas-disable-blacklake-preview.sh'
    if($LASTEXITCODE -ne 0){throw 'Remote preview closure verification failed'}
    if(Get-NetTCPConnection -LocalPort 5188 -State Listen -ErrorAction SilentlyContinue){throw 'Local preview port still listening'}
    Write-Output 'Preview closed: local5188 and remote19378 released; HTTPS returns410.'
    exit
}
if(-not $Dist -or -not $PreviewPath){throw 'Dist and PreviewPath are required to run the preview'}
$Dist=(Resolve-Path -LiteralPath $Dist).Path
if (-not (Test-Path -LiteralPath (Join-Path $Dist 'blacklake-smartbi.html'))) { throw 'Missing public preview build' }
New-Item -ItemType Directory -Force -Path $StateRoot | Out-Null
if ($Install) {
    # Desktop packaging may redirect LocalAppData. Pin the actual file path;
    # the scheduled task must read the same encrypted file outside that package.
    $taskResolver=(Get-Command python.exe).Source
    $SessionConfig=(& $taskResolver -c 'from pathlib import Path; import sys; print(Path(sys.argv[1]).resolve(strict=True))' $SessionConfig).Trim()
    if($LASTEXITCODE -ne 0){throw 'Encrypted session config could not be resolved'}
    if(Test-Path -LiteralPath (Join-Path $StateRoot 'stop.request')){Remove-Item -LiteralPath (Join-Path $StateRoot 'stop.request')}
    $taskArgs='-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "'+$PSCommandPath+'" -Dist "'+$Dist+'" -PreviewPath "'+$PreviewPath+'" -SessionConfig "'+$SessionConfig+'" -RepoRoot "'+$RepoRoot+'" -StateRoot "'+$StateRoot+'"'
    $taskAction=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $taskArgs
    $taskUser=[Security.Principal.WindowsIdentity]::GetCurrent().Name
    $taskTrigger=New-ScheduledTaskTrigger -AtLogOn -User $taskUser
    $taskSettings=New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $taskPrincipal=New-ScheduledTaskPrincipal -UserId $taskUser -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName 'Cretas-Blacklake-Remote-Preview' -Action $taskAction -Trigger $taskTrigger -Settings $taskSettings -Principal $taskPrincipal -Force | Out-Null
    Start-ScheduledTask -TaskName 'Cretas-Blacklake-Remote-Preview'
    Write-Output 'Installed and started the independent public preview task.'
    exit
}
$taskMutex=[Threading.Mutex]::new($false,'Local\CretasBlacklakePublicPreview')
if (-not $taskMutex.WaitOne(0)) { exit }
try {
    $env:BLACKLAKE_PREVIEW_DIST=$Dist
    $env:BLACKLAKE_PREVIEW_PATH=$PreviewPath
    $env:BLACKLAKE_PREVIEW_SESSION_CONFIG=$SessionConfig
    $taskPython=(Get-Command python.exe).Source
    $taskGateway=$null
    $taskTunnel=$null
    function Save-PreviewState {
        $taskProcesses=[ordered]@{watcher=(Preview-ProcessRecord (Get-Process -Id $PID) 'blacklake-remote-preview.ps1');gateway=$(if($taskGateway){Preview-ProcessRecord $taskGateway 'blacklake.public_preview'}else{$null});tunnel=$(if($taskTunnel){Preview-ProcessRecord $taskTunnel '127.0.0.1:19378:127.0.0.1:5188'}else{$null})}
        [IO.File]::WriteAllText((Join-Path $StateRoot 'processes.tmp.json'),($taskProcesses | ConvertTo-Json),[Text.UTF8Encoding]::new($false))
        Move-Item -LiteralPath (Join-Path $StateRoot 'processes.tmp.json') -Destination (Join-Path $StateRoot 'processes.json') -Force
    }
    Save-PreviewState
    while ($true) {
        if(Test-Path -LiteralPath (Join-Path $StateRoot 'stop.request')){break}
        if ($null -eq $taskGateway -or $taskGateway.HasExited) {
            if(Get-NetTCPConnection -LocalPort 5188 -State Listen -ErrorAction SilentlyContinue){throw 'Dedicated preview port already in use; refusing to expose another service'}
            $taskGateway=Start-Process -FilePath $taskPython -ArgumentList @('-m','blacklake.public_preview') -WorkingDirectory (Join-Path $RepoRoot 'backend/python') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $StateRoot 'gateway.out.log') -RedirectStandardError (Join-Path $StateRoot 'gateway.err.log')
            Save-PreviewState
        }
        if ($null -eq $taskTunnel -or $taskTunnel.HasExited) {
            $taskReady=$false
            for($taskProbe=0;$taskProbe -lt 20;$taskProbe++){
                try{$taskHealth=Invoke-RestMethod -Uri ('http://127.0.0.1:5188'+$PreviewPath+'health') -TimeoutSec 2;if($taskHealth.success -eq $true -and $taskHealth.preview -eq 'blacklake-readonly'){$taskReady=$true;break}}catch{}
                Start-Sleep -Seconds 1
            }
            if(-not $taskReady){throw 'Preview service identity could not be verified; tunnel not opened'}
            # Remote end is loopback only. Only the dedicated HTTPS test vhost reaches it.
            $taskTunnel=Start-Process -FilePath 'ssh.exe' -ArgumentList @('-N','-T','-o','BatchMode=yes','-o','ExitOnForwardFailure=yes','-o','ServerAliveInterval=30','-o','ServerAliveCountMax=3','-R','127.0.0.1:19378:127.0.0.1:5188','root@139.196.165.140') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $StateRoot 'tunnel.out.log') -RedirectStandardError (Join-Path $StateRoot 'tunnel.err.log')
            Save-PreviewState
        }
        Start-Sleep -Seconds 1
    }
} finally {
    if($taskGateway -and -not $taskGateway.HasExited){Stop-PreviewProcess (Preview-ProcessRecord $taskGateway 'blacklake.public_preview')}
    if($taskTunnel -and -not $taskTunnel.HasExited){Stop-PreviewProcess (Preview-ProcessRecord $taskTunnel '127.0.0.1:19378:127.0.0.1:5188')}
    $taskMutex.ReleaseMutex(); $taskMutex.Dispose()
}
