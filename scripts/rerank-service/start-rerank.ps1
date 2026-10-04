# Intent rerank service + FRP STCP tunnel to 47. Started at logon by scheduled task CretasIntentRerank.
# Keep this file ASCII-only: Windows PowerShell 5.1 reads BOM-less UTF-8 as the ANSI code page,
# and a mis-decoded non-ASCII comment once swallowed the next line ($here ended up null).
# Idempotent: skips whatever is already running. Logs: service.log / frpc.log in this folder.
$here = $PSScriptRoot
Set-Location $here

$listening = Get-NetTCPConnection -LocalAddress 127.0.0.1 -LocalPort 18190 -State Listen -ErrorAction SilentlyContinue
if (-not $listening) {
    Start-Process -FilePath "D:\jev-eval\env\python.exe" `
        -ArgumentList "-m", "uvicorn", "app:app", "--host", "127.0.0.1", "--port", "18190" `
        -WorkingDirectory $here -WindowStyle Hidden `
        -RedirectStandardOutput "$here\service.log" -RedirectStandardError "$here\service.err.log"
}

$frpc = Get-Process -Name frpc -ErrorAction SilentlyContinue | Where-Object { $_.Path -eq "$here\frpc.exe" }
if (-not $frpc) {
    Start-Process -FilePath "$here\frpc.exe" -ArgumentList "-c", "$here\frpc.toml" `
        -WorkingDirectory $here -WindowStyle Hidden `
        -RedirectStandardOutput "$here\frpc.log" -RedirectStandardError "$here\frpc.err.log"
}
