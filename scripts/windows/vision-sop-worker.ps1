param(
    [string]$Python = "B:\anaconda3\python.exe",
    [Parameter(Mandatory = $true)][string]$Registration,
    [ValidateSet("collect", "dataset", "train", "evaluate")][string]$Stage,
    [switch]$Once
)

$ErrorActionPreference = "Stop"
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw "Vision SOP worker runs on the local Windows host only."
}
$scriptPath = Join-Path $PSScriptRoot "..\..\tools\vision-lab\sop_worker.py"
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) { throw "Python not found: $Python" }
if (-not (Test-Path -LiteralPath $Registration -PathType Leaf)) { throw "Worker registration not found: $Registration" }
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) { throw "Worker entrypoint not found: $scriptPath" }
if (-not $Once -and -not $Stage) { throw "Specify -Once or one of -Stage collect/dataset/train/evaluate." }

if ($Once) {
    & $Python $scriptPath --registration $Registration --once
} else {
    & $Python $scriptPath --registration $Registration --stage $Stage
}
exit $LASTEXITCODE
