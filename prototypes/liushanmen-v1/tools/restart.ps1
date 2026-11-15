$ErrorActionPreference='Stop'
$prototypeRoot=Split-Path -Parent $PSScriptRoot
$s=Get-Content -LiteralPath (Join-Path $prototypeRoot 'private/server-state.json') -Raw|ConvertFrom-Json
$p=Get-CimInstance Win32_Process -Filter ('ProcessId='+$s.pid)
if($p -and $p.CommandLine -notlike '*liushanmen-v1*server.cjs*'){throw 'Process ownership mismatch'}
if($p){Stop-Process -Id $s.pid}
& (Join-Path $PSScriptRoot 'launch.ps1') -NoOpen
