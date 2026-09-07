param(
    [string]$Python = "B:\anaconda3\python.exe",
    [string]$Config = "D:\CretasVisionLab\config.json",
    [string]$RepoRoot = "C:\Users\Steve\my-prototype-logistics",
    [ValidateSet("cycle", "status", "scan-queues")]
    [string]$Command = "cycle",
    [switch]$RequireMergedMain
)

$ErrorActionPreference = "Stop"
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw "VisionLab runs on the local Windows host only. Mac dispatch is disabled."
}
$scriptPath = Join-Path $RepoRoot "tools\vision-lab\vision_lab.py"
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) {
    throw "Python not found: $Python"
}
if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) {
    throw "Vision Lab config not found: $Config"
}
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw "Vision Lab entrypoint not found: $scriptPath"
}

if ($RequireMergedMain) {
    & git -C $RepoRoot fetch --no-tags origin main
    if ($LASTEXITCODE -ne 0) {
        throw "Cannot verify current main; local scheduled execution is stopped."
    }
    $dirty = & git -C $RepoRoot status --porcelain
    if ($LASTEXITCODE -ne 0 -or $dirty) {
        throw "Local scheduled execution requires a clean checkout."
    }
    $head = & git -C $RepoRoot rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { throw "Cannot read local HEAD." }
    $main = & git -C $RepoRoot rev-parse refs/remotes/origin/main
    if ($LASTEXITCODE -ne 0 -or $head -ne $main) {
        throw "Local scheduled execution requires HEAD == current origin/main."
    }
}

$env:CRETAS_REPO_ROOT = $RepoRoot
if (-not $env:PROCESSOR_ARCHITECTURE) {
    $env:PROCESSOR_ARCHITECTURE = "AMD64"
}
& $Python $scriptPath --config $Config $Command
$result = $LASTEXITCODE
# The CLI uses 20 to distinguish a healthy human-attention pause from a
# completed autonomous cycle.  Task Scheduler should still report that run as
# healthy; the durable MARK file carries the attention state.
if ($result -eq 20) {
    exit 0
}
exit $result
