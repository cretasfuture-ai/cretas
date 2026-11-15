# 重启统一入口（19063）。只停监听 19063 的进程，不碰 19060/19061（味美与原版）和 19062（六膳门）。
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$conn = Get-NetTCPConnection -LocalPort 19063 -State Listen -ErrorAction SilentlyContinue
foreach ($c in $conn) { Stop-Process -Id $c.OwningProcess -Force }
Start-Sleep -Milliseconds 400
$node = (Get-Command node).Source
Start-Process -FilePath $node -ArgumentList @("`"$root\server.cjs`"") -WorkingDirectory $root -WindowStyle Hidden -RedirectStandardOutput "$root\private\server-out.log" -RedirectStandardError "$root\private\server-err.log"
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Milliseconds 250
  try { $r = Invoke-RestMethod -Uri 'http://127.0.0.1:19063/__unified/health' -TimeoutSec 2; if ($r.ready) { Write-Output "UNIFIED_READY port=19063"; exit 0 } } catch { }
}
Write-Output 'UNIFIED_NOT_READY'
exit 1
