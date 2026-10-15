param(
    [string]$Database = "",
    [switch]$KeepDatabase
)

$ErrorActionPreference = 'Stop'
$env:PGCLIENTENCODING = 'UTF8'
$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$flyway = Join-Path $repo 'backend/java/cretas-api/src/main/resources/db/flyway'
$pg = 'C:\Program Files\PostgreSQL\17\bin\psql.exe'
$port = 55447
$user = 'assembly_executor'
$sourceRepo = 'C:\Users\Steve\asm-workflow-ui-20261012'
$sourceFlyway = Join-Path $sourceRepo 'backend/java/cretas-manufacturing/src/main/resources/db/flyway'
$evidence = 'C:\Users\Steve\Documents\assembly-integration-assessment-20261015\migrations'
New-Item -ItemType Directory -Force $evidence | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $evidence "platform-assembly-migrations-$stamp.log"

function Invoke-Psql([string]$Db, [string[]]$PsqlArgs) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $pg -h 127.0.0.1 -p $port -U $user -d $Db @PsqlArgs 2>&1
        if ($LASTEXITCODE -ne 0) { $output | Add-Content $log; throw "psql failed for $Db (rc=$LASTEXITCODE)" }
    } finally { $ErrorActionPreference = $previous }
}

if ([string]::IsNullOrWhiteSpace($Database)) { $Database = "assembly_migrations_probe_$([guid]::NewGuid().ToString('N').Substring(0,12))" }
if ($Database -notmatch '^assembly_migrations_probe_[a-z0-9]{12}$') { throw "database must use the generated assembly_migrations_probe_ prefix" }

"repo=$repo" | Set-Content $log
"source_commit=$((git -C $sourceRepo rev-parse HEAD).Trim())" | Add-Content $log
"target_commit=$((git -C $repo rev-parse HEAD).Trim())" | Add-Content $log

$sourceCommit = (git -C $sourceRepo rev-parse HEAD).Trim()
$sourceRef = (git -C $sourceRepo rev-parse assembly/main).Trim()
if ($sourceRef -ne 'cf184bfd29a048793ff2cce23308618080615ff6') { throw "source assembly/main ref drifted: $sourceRef" }

$prod = Get-Content (Join-Path $repo 'backend/java/cretas-api/src/main/resources/application-pg-prod.properties') -Raw
if ($prod -notmatch '(?m)^spring\.flyway\.validate-on-migrate=true\s*$') { throw 'validate-on-migrate=true missing' }
if ($prod -notmatch '(?m)^spring\.flyway\.out-of-order=false\s*$') { throw 'out-of-order=false missing' }

$expected = @(
 'V20261103_01__assembly_blueprint_draft_baseline.sql',
 'V20261101_58__bom_units_catalog_authority.sql',
 'V20261103_41__master_data_draft_promotions.sql','V20261103_42__bom_draft_promotion_capability.sql',
 'V20261103_43__typed_factory_assembly_actors.sql','V20261103_44__typed_draft_actors.sql',
 'V20261103_45__typed_activation_separation.sql','V20261103_46__typed_module_configuration_apply.sql',
 'V20261103_47__factory_assembly_approvals.sql','V20261103_48__typed_formal_bom_activation.sql',
 'V20261103_49__typed_canonical_blueprint_actors.sql',
 'V20261103_53__critical_control_temperature_snapshot.sql','V20261103_59__material_draft_update_promotion.sql',
 'V20261103_60__factory_assembly_conversation_sessions.sql','V20261103_61__assembly_catalog_and_snapshot_dependencies.sql',
 'V20261103_62__assembly_plan_result_snapshot.sql','V20261103_63__assembly_binding_unapplied_version.sql'
)
foreach ($name in $expected) { if (-not (Test-Path (Join-Path $flyway $name))) { throw "missing migration: $name" } }
$v42 = 'V20261103_42__bom_draft_promotion_capability.sql'
$srcHash = (Get-FileHash (Join-Path $sourceFlyway $v42) -Algorithm SHA256).Hash
$dstHash = (Get-FileHash (Join-Path $flyway $v42) -Algorithm SHA256).Hash
if ($srcHash -ne $dstHash) { throw "V42 checksum mismatch: source=$srcHash target=$dstHash" }
"V42_sha256=$dstHash" | Add-Content $log

$dml = Get-ChildItem $flyway -File | Where-Object { $expected -contains $_.Name } |
    Select-String -Pattern '(?im)^\s*(INSERT|UPDATE|DELETE|TRUNCATE)\b'
$unexpectedDml = $dml | Where-Object {
    $_.Line -notmatch '(?i)^\s*UPDATE\s+public\.factory_type_blueprints\s*$' -and $_.Line -notmatch '(?i)^\s*INSERT INTO public\.factory_capability_definitions\s*$'
}
if ($unexpectedDml) { throw "assembly migration contains unbounded business DML: $($unexpectedDml | Out-String)" }

Invoke-Psql 'postgres' @('-v','ON_ERROR_STOP=1','-c',"CREATE DATABASE `"$Database`"")
$created = $true
try {
    Invoke-Psql $Database @('-v','ON_ERROR_STOP=1','-c','DROP SCHEMA public CASCADE; CREATE SCHEMA vector_ext; CREATE EXTENSION vector SCHEMA vector_ext; CREATE SCHEMA public; ALTER EXTENSION vector SET SCHEMA public;')
    $baselineCopy = Join-Path $env:TEMP "platform-assembly-prod-baseline-$Database.sql"
    Get-Content -Encoding UTF8 (Join-Path $repo 'ops/db-baseline/prod-schema-baseline.sql') |
        Where-Object { $_ -notmatch '^CREATE SCHEMA public;' } |
        Set-Content -LiteralPath $baselineCopy -Encoding utf8
    Invoke-Psql $Database @('-v','ON_ERROR_STOP=1','-f',$baselineCopy)
    Invoke-Psql $Database @('-v','ON_ERROR_STOP=1','-c','DROP TABLE IF EXISTS public.flyway_schema_history;')
    Invoke-Psql $Database @('-v','ON_ERROR_STOP=1','-c',"insert into factory_type_blueprints (id,name,description,industry_type,default_config,form_templates,rule_templates,product_type_templates,department_templates,is_active,version,created_at,updated_at,deleted_at) values ('assembly-gate-sentinel','Assembly gate sentinel','migration control row','TEST','{}'::json,'{}'::json,'{}'::json,'{}'::json,'{}'::json,true,1,current_timestamp,current_timestamp,null);")
    $beforeRows = (& $pg -h 127.0.0.1 -p $port -U $user -d $Database -Atc "select (select count(*) from product_types) || ':' || (select count(*) from raw_material_types) || ':' || (select count(*) from factory_configurations) || ':' || (select count(*) from factory_type_blueprints)").Trim()
    $beforeSentinel = (& $pg -h 127.0.0.1 -p $port -U $user -d $Database -Atc "select md5(row(id,name,description,industry_type,default_config,form_templates,rule_templates,product_type_templates,department_templates,is_active,version)::text) from factory_type_blueprints where id='assembly-gate-sentinel'").Trim()
    $cp = 'C:\Users\Steve\Documents\assembly-integration-assessment-20261015\migrations\flyway-classpath.txt'
    if (-not (Test-Path $cp)) {
        $cpDir = Split-Path $cp -Parent
        New-Item -ItemType Directory -Force $cpDir | Out-Null
        & mvn -q -f (Join-Path $repo 'backend/java/cretas-api/pom.xml') dependency:build-classpath "-Dmdep.outputFile=$cp" '-Dmdep.includeScope=test'
        if ($LASTEXITCODE -ne 0) { throw "Maven classpath resolution failed" }
    }
    $runner = Join-Path $PSScriptRoot 'PlatformAssemblyFlywayRunner.java'
    $classes = Join-Path $env:TEMP 'platform-assembly-flyway-runner'
    New-Item -ItemType Directory -Force $classes | Out-Null
    & javac -cp (Get-Content -Raw $cp) -d $classes $runner
    if ($LASTEXITCODE -ne 0) { throw 'javac Flyway runner failed' }
    $url = "jdbc:postgresql://127.0.0.1:$port/$Database"
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $flywayOutput = & java -cp "$classes;$(Get-Content -Raw -Encoding UTF8 $cp)" PlatformAssemblyFlywayRunner $url $user 'local-trust' "filesystem:$flyway" '20261101.37' 2>&1
        $flywayOutput | Add-Content -Encoding UTF8 $log
        if ($LASTEXITCODE -ne 0) { throw "Flyway migrate failed (rc=$LASTEXITCODE)" }
    } finally { $ErrorActionPreference = $previous }
    $check = & $pg -h 127.0.0.1 -p $port -U $user -d $Database -Atc "select (select count(*) from information_schema.tables where table_schema='public' and table_name in ('factory_type_blueprints','product_type_drafts','emergency_defense_plan_drafts','material_product_conversions','factory_assembly_conversation_sessions')) || ':' || (select count(*) from factory_assembly_conversation_sessions) || ':' || (select count(*) from pg_indexes where schemaname='public' and tablename='factory_assembly_conversation_sessions' and indexname in ('idx_assembly_cs_factory_user','idx_assembly_cs_factory_actor','idx_assembly_cs_status','idx_assembly_cs_created_at'));" 2>&1
    if ($LASTEXITCODE -ne 0 -or $check.Trim() -ne '5:0:4') { throw "bootstrap assertions failed: $check" }
    $afterRows = (& $pg -h 127.0.0.1 -p $port -U $user -d $Database -Atc "select (select count(*) from product_types) || ':' || (select count(*) from raw_material_types) || ':' || (select count(*) from factory_configurations) || ':' || (select count(*) from factory_type_blueprints)").Trim()
    if ($beforeRows -ne $afterRows) { throw "existing business row counts changed: before=$beforeRows after=$afterRows" }
    $afterSentinel = (& $pg -h 127.0.0.1 -p $port -U $user -d $Database -Atc "select md5(row(id,name,description,industry_type,default_config,form_templates,rule_templates,product_type_templates,department_templates,is_active,version)::text) from factory_type_blueprints where id='assembly-gate-sentinel'").Trim()
    if ($beforeSentinel -ne $afterSentinel) { throw "sentinel business row changed: before=$beforeSentinel after=$afterSentinel" }
    $history = (& $pg -h 127.0.0.1 -p $port -U $user -d $Database -Atc "select max(version::text) || ':' || count(*) from flyway_schema_history where success").Trim()
    if ($history -ne '20261103.63:38') { throw "Flyway history assertion failed: $history" }
    "PASS true-Flyway baseline+upgrade: history=$history selected_tables=5 conversation_rows=0 business_rows_before_after=$beforeRows sentinel_hash=$beforeSentinel" | Add-Content $log
} finally {
    if ($created -and -not $KeepDatabase) { Invoke-Psql 'postgres' @('-v','ON_ERROR_STOP=1','-c',"DROP DATABASE `"$Database`"") }
}

"PASS static Flyway gate: validate-on-migrate=true, out-of-order=false, V42 exact source hash" | Add-Content $log
Write-Output "PASS evidence=$log"
