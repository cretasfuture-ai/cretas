param(
    [string]$Python = "B:\anaconda3\python.exe",
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
)

$ErrorActionPreference = "Stop"
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw "YOLO tests run on the local Windows host only. No remote fallback."
}
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) {
    throw "Local Python not found: $Python"
}
$previousPythonPath = $env:PYTHONPATH
Push-Location (Join-Path $RepoRoot "backend\python")
try {
    $env:PYTHONPATH = (Get-Location).Path
    & $Python -m pytest tests/test_label_qc_v8_pipeline.py tests/test_label_qc_screening.py ../../scripts/tests/test_vision_local_execution.py -q
    $result = $LASTEXITCODE
} finally {
    $env:PYTHONPATH = $previousPythonPath
    Pop-Location
}
exit $result
