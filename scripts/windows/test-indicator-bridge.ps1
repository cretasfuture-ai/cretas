param(
    [string]$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path,
    [string]$Python = 'B:\anaconda3\python.exe',
    [string]$Maven = 'C:\Users\Steve\tools\apache-maven-3.9.9\bin\mvn.cmd',
    [string]$EvidenceDir = (Join-Path $env:TEMP 'cretas-indicator-bridge-acceptance'),
    [string]$PgUser = 'trace_acceptance'
)

$ErrorActionPreference = 'Stop'
$databaseName = 'cretas_workflow_verify_indicator_bridge_20261015'
$RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
$pythonRoot = Join-Path $RepoRoot 'backend\python'
$javaRoot = Join-Path $RepoRoot 'backend\java\cretas-api'
$fixtureRoot = Join-Path $pythonRoot 'tests\fixtures'
foreach ($executable in @($Python, $Maven)) {
    if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) { throw "Missing executable: $executable" }
}
foreach ($port in @(10059, 18085)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) {
        throw "Loopback acceptance port $port is already owned; no existing process will be stopped"
    }
}
New-Item -ItemType Directory -Force -Path $EvidenceDir | Out-Null
$EvidenceDir = (Resolve-Path -LiteralPath $EvidenceDir).Path
# The caller supplies the dedicated loopback PG. This entry never creates,
# drops or migrates a database; both fixtures independently check the target.
$fixtureEnvironment = @{
    CRETAS_INDICATOR_BRIDGE_PG_VERIFY = 'true'
    CRETAS_WORKFLOW_PG_URL = "jdbc:postgresql://127.0.0.1:55458/$databaseName"
    CRETAS_WORKFLOW_PG_USER = $PgUser
    CRETAS_INDICATOR_BRIDGE_EVIDENCE_DIR = $EvidenceDir
    POSTGRES_HOST = '127.0.0.1'
    POSTGRES_PORT = '55458'
    POSTGRES_DB = $databaseName
    POSTGRES_USER = $PgUser
    POSTGRES_PASSWORD = ''
    POSTGRES_ENABLED = 'true'
    FOOD_KB_POSTGRES_HOST = '127.0.0.1'
    FOOD_KB_POSTGRES_PORT = '55458'
    FOOD_KB_POSTGRES_DB = $databaseName
    FOOD_KB_POSTGRES_USER = $PgUser
    FOOD_KB_POSTGRES_PASSWORD = ''
    INTERNAL_API_SECRET = 'local-indicator-acceptance-only-secret'
    PYTHONPATH = "$pythonRoot;$pythonRoot\smartbi"
}
$savedEnvironment = @{}
$pythonProcess = $null
$originalDirectory = (Get-Location).Path
try {
    foreach ($key in $fixtureEnvironment.Keys) {
        $savedEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $fixtureEnvironment[$key], 'Process')
    }
    $pythonProcess = Start-Process -FilePath $Python -ArgumentList @(
        '-m', 'uvicorn', 'indicator_bridge_app:app', '--app-dir', ('"' + $fixtureRoot + '"'),
        '--host', '127.0.0.1', '--port', '18085'
    ) -WorkingDirectory $pythonRoot -WindowStyle Hidden -PassThru `
      -RedirectStandardOutput (Join-Path $EvidenceDir 'python-server.log') `
      -RedirectStandardError (Join-Path $EvidenceDir 'python-server-error.log')
    $deadline = [DateTime]::UtcNow.AddSeconds(45)
    do {
        if ($pythonProcess.HasExited) { throw 'Python fixture exited before readiness; see its log' }
        $listening = Get-NetTCPConnection -State Listen -LocalPort 18085 -ErrorAction SilentlyContinue
        if ($listening) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    if (-not $listening) { throw 'Python fixture readiness timed out' }
    if (@($listening | Where-Object { $_.OwningProcess -ne $pythonProcess.Id }).Count -gt 0) {
        throw 'Fixture port ownership differs from the process started by this entry'
    }
    $path = 'http://127.0.0.1:18085/api/smartbi/indicator/restaurant-dish-margin'
    $anonymous = Invoke-WebRequest -Uri $path -SkipHttpErrorCheck
    if ($anonymous.StatusCode -ne 401) { throw 'Anonymous negative control was not rejected' }
    $foreign = Invoke-WebRequest -Uri "$path`?factory_id=IND-BRIDGE-B" -SkipHttpErrorCheck -Headers @{
        'X-Internal-Secret' = $fixtureEnvironment.INTERNAL_API_SECRET
        'X-Factory-Id' = 'IND-BRIDGE-A'
    }
    if ($foreign.StatusCode -ne 403) { throw 'Cross-factory negative control was not rejected' }
    Set-Location -LiteralPath $javaRoot
    & $Maven test '-Dtest=IndicatorPythonBridgePostgresAcceptanceTest' *> (Join-Path $EvidenceDir 'java-python-pg-green.log')
    $testExit = $LASTEXITCODE
    if ($testExit -ne 0) { throw "Cross-service acceptance failed (Maven=$testExit); see java-python-pg-green.log" }
    [ordered]@{
        status = 'pass'; database = $databaseName; address = '127.0.0.1:55458'
        java_port = 10059; python_port = 18085; anonymous_status = 401; cross_factory_status = 403
        maven_exit = $testExit; production_writes = 0
        source_head = (& git -C $RepoRoot rev-parse HEAD)
        scope = 'real Java Controller/Service/client and Python middleware/router/Gold function with private PostgreSQL; Java production auth excluded'
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $EvidenceDir 'cross-service-receipt.json') -Encoding utf8
    Write-Output 'INDICATOR_BRIDGE_ACCEPTANCE=pass'
} finally {
    Set-Location -LiteralPath $originalDirectory
    if ($null -ne $pythonProcess -and -not $pythonProcess.HasExited) {
        $ownedProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($pythonProcess.Id)"
        if ($ownedProcess.CommandLine -notmatch 'indicator_bridge_app:app') {
            throw 'Fixture PID ownership changed; refusing to stop it'
        }
        Stop-Process -Id $pythonProcess.Id -ErrorAction Stop
        $pythonProcess.WaitForExit(5000) | Out-Null
    }
    foreach ($key in $savedEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($key, $savedEnvironment[$key], 'Process')
    }
}
