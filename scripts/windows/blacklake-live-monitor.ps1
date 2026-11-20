param(
    [Parameter(Mandatory=$true)][string]$Config,
    [Parameter(Mandatory=$true)][string]$Python,
    [Parameter(Mandatory=$true)][string]$LocalData,
    [switch]$Install,
    [string]$AdapterLauncher
)
$ErrorActionPreference='Stop'
$taskRepo=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$taskConfig=(Resolve-Path -LiteralPath $Config).Path
$taskPython=(Resolve-Path -LiteralPath $Python).Path
$taskData=(Resolve-Path -LiteralPath $LocalData).Path
$taskData=& $taskPython -c "from pathlib import Path;import sys;print((Path(sys.argv[1])/'Cretas/Blacklake/account.dpapi').resolve().parents[2])" $taskData
if($LASTEXITCODE -ne 0){throw 'Local data path resolution failed'}
if($AdapterLauncher){
    $AdapterLauncher=& $taskPython -c 'from pathlib import Path;import sys;print(Path(sys.argv[1]).resolve())' $AdapterLauncher
    if($LASTEXITCODE -ne 0){throw 'Adapter path resolution failed'}
}
$env:LOCALAPPDATA=$taskData
$env:PYTHONPATH=Join-Path $taskRepo 'backend/python'
if($Install){
    $taskArguments='-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "'+$PSCommandPath+'" -Config "'+$taskConfig+'" -Python "'+$taskPython+'" -LocalData "'+$taskData+'"'
    if($AdapterLauncher){$taskArguments+=' -AdapterLauncher "'+(Resolve-Path -LiteralPath $AdapterLauncher).Path+'"'}
    $taskName=if($AdapterLauncher){'Cretas-Blacklake-Live-Adapter'}else{'Cretas-Blacklake-Live-Collection'}
    $taskAction=New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $taskArguments -WorkingDirectory $taskRepo
    $taskTrigger=New-ScheduledTaskTrigger -AtLogOn -User ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
    $taskSettings=New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $taskPrincipal=New-ScheduledTaskPrincipal -UserId ([Security.Principal.WindowsIdentity]::GetCurrent().Name) -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName $taskName -Action $taskAction -Trigger $taskTrigger -Settings $taskSettings -Principal $taskPrincipal -Force | Out-Null
    Start-ScheduledTask -TaskName $taskName
    Write-Output "Started: $taskName"
    exit
}
$taskRunStamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$taskLogRoot=Split-Path $taskConfig
if($AdapterLauncher){
    # Existing owned adapter can finish its durable AI job without interruption.
    # Supervise it, and start the pinned launcher only if its port is released.
    while($taskListener=Get-NetTCPConnection -LocalPort 8095 -State Listen -ErrorAction SilentlyContinue){
        $taskExisting=Get-CimInstance Win32_Process -Filter "ProcessId=$($taskListener.OwningProcess)"
        if(-not $taskExisting -or -not $taskExisting.CommandLine.Replace('\','/').Contains('SmartBITeacher/experiment-20261104/profile6_adapter.py')){throw 'Port8095 has a different owner; no process stopped'}
        Start-Sleep -Seconds 30
    }
    $taskArgs='"'+(Resolve-Path -LiteralPath $AdapterLauncher).Path+'"'
    $taskLabel='adapter'
}else{
    $taskArgs='-m blacklake.live_monitor --config "'+$taskConfig+'"'
    $taskLabel='collection'
}
$taskProcess=Start-Process -FilePath $taskPython -ArgumentList $taskArgs -WorkingDirectory $env:PYTHONPATH -WindowStyle Hidden -PassThru -Wait -RedirectStandardOutput (Join-Path $taskLogRoot "$taskLabel-$taskRunStamp.stdout.log") -RedirectStandardError (Join-Path $taskLogRoot "$taskLabel-$taskRunStamp.stderr.log")
exit $taskProcess.ExitCode
