$ErrorActionPreference = 'Stop'
$blacklakeRepo = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$blacklakePrivate = Join-Path $env:LOCALAPPDATA 'Cretas\Blacklake\validation'
New-Item -ItemType Directory -Force -Path $blacklakePrivate | Out-Null
$blacklakePython = (Get-Command python -CommandType Application | Select-Object -First 1).Source
$blacklakeNode = (Get-Command node -CommandType Application | Select-Object -First 1).Source
$env:VITE_BACKEND_URL = 'http://127.0.0.1:11010'
$env:VITE_PYTHON_URL = 'http://127.0.0.1:8093'
$env:VITE_BLACKLAKE_SMARTBI = 'true'
$blacklakeHealth = $null
try { $blacklakeHealth = Invoke-RestMethod 'http://127.0.0.1:8093/health' -TimeoutSec 3 } catch { }
if (-not $blacklakeHealth) {
    Start-Process -FilePath $blacklakePython -ArgumentList @('-m','blacklake.smartbi_app') -WorkingDirectory (Join-Path $blacklakeRepo 'backend/python') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $blacklakePrivate 'smartbi-api.log') -RedirectStandardError (Join-Path $blacklakePrivate 'smartbi-api.err') | Out-Null
} elseif ($blacklakeHealth.environment -ne 'test' -or $blacklakeHealth.source -ne 'blacklake') { throw '8093 is occupied by another service' }
$blacklakeWeb = $null
try { $blacklakeWeb = Invoke-WebRequest 'http://127.0.0.1:5178/blacklake-smartbi.html' -TimeoutSec 3 } catch { }
if (-not $blacklakeWeb) {
    Start-Process -FilePath $blacklakeNode -ArgumentList @('node_modules/vite/bin/vite.js','--mode','blacklake','--port','5178','--strictPort','--host','127.0.0.1') -WorkingDirectory (Join-Path $blacklakeRepo 'web-admin') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $blacklakePrivate 'smartbi-vite.log') -RedirectStandardError (Join-Path $blacklakePrivate 'smartbi-vite.err') | Out-Null
} elseif ($blacklakeWeb.Content -notmatch 'blacklake-main.ts') { throw '5178 is occupied by another application' }
Start-Process -FilePath $blacklakeNode -ArgumentList @('scripts/blacklake-review.mjs') -WorkingDirectory (Join-Path $blacklakeRepo 'web-admin') -WindowStyle Hidden -RedirectStandardOutput (Join-Path $blacklakePrivate 'smartbi-review.log') -RedirectStandardError (Join-Path $blacklakePrivate 'smartbi-review.err') | Out-Null
Write-Output 'Opening the authenticated local SmartBI review window.'
