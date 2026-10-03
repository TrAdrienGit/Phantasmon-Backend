<#
.SYNOPSIS
  Proves a backup is restorable - CAD Partie 3 §I, TODO-5. Never touches the real database.

.DESCRIPTION
  Starts a throwaway "postgres:18" container (no published port, removed at the end), restores the dump into it
  with pg_restore, then checks the result: Flyway history present and successful, core tables present, row counts
  printed. Works for dumps of both the native and the Docker database. Requires Docker.

.PARAMETER DumpFile
  Default: the newest dump in <repo>\backups\daily.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\test-restore.ps1
#>
param(
	[string]$DumpFile,
	[string]$BackupDir
)

. (Join-Path $PSScriptRoot 'db-common.ps1')

if (-not $BackupDir) { $BackupDir = Join-Path $RepoRoot 'backups' }
if (-not $DumpFile) {
	$latest = Get-ChildItem (Join-Path $BackupDir 'daily') -Filter 'phantasmon_*.dump' -ErrorAction SilentlyContinue |
		Sort-Object LastWriteTime -Descending | Select-Object -First 1
	if (-not $latest) { Write-Error "No dump found in $BackupDir\daily - run backup-database.ps1 first."; exit 1 }
	$DumpFile = $latest.FullName
}
if (-not (Test-Path $DumpFile)) { Write-Error "Dump not found: $DumpFile"; exit 1 }

$name = 'phantasmon-restore-test-' + (Get-Date -Format 'yyyyMMddHHmmss')
$database = 'restore_test'
$failed = $false

Write-Host "==> Restoring $DumpFile into throwaway container '$name'..."
try {
	# Random password: the container is not reachable from outside (no -p) and is deleted at the end anyway.
	$password = [guid]::NewGuid().ToString('N')
	Invoke-Checked 'docker run' {
		docker run -d --rm --name $name -e "POSTGRES_PASSWORD=$password" -e "POSTGRES_DB=$database" $PostgresImage | Out-Null
	}

	$ready = $false
	for ($i = 0; $i -lt 60; $i++) {
		docker exec $name pg_isready -U postgres -d $database 2>$null | Out-Null
		# The image restarts the server once after initialisation: wait for a real query to succeed.
		if ($LASTEXITCODE -eq 0) {
			docker exec $name psql -U postgres -d $database -tAc 'select 1' 2>$null | Out-Null
			if ($LASTEXITCODE -eq 0) { $ready = $true; break }
		}
		Start-Sleep -Seconds 1
	}
	if (-not $ready) { throw 'Throwaway PostgreSQL did not become ready in 60 s.' }

	Invoke-Checked 'docker cp' { docker cp $DumpFile "${name}:/tmp/restore.dump" }
	Invoke-Checked 'pg_restore' {
		docker exec $name pg_restore -U postgres -d $database --no-owner --no-privileges --exit-on-error /tmp/restore.dump
	}

	function Query([string]$sql) {
		$out = docker exec $name psql -U postgres -d $database -tA -v ON_ERROR_STOP=1 -c $sql
		if ($LASTEXITCODE -ne 0) { throw "Query failed: $sql" }
		return ($out | Out-String).Trim()
	}

	$flyway = Query "select count(*) || ' migrations, last V' || max(version::int) || ', failed: ' || count(*) filter (where not success) from flyway_schema_history where version is not null"
	Write-Host "    Flyway: $flyway"
	if ($flyway -notmatch 'failed: 0$') { throw 'Flyway history reports a failed migration.' }

	$expected = 'players', 'pokemon', 'trades', 'battle_sessions'
	$present = (Query "select string_agg(table_name, ',') from information_schema.tables where table_schema = 'public'") -split ','
	foreach ($table in $expected) {
		if ($present -notcontains $table) { throw "Table '$table' missing from the restored database." }
		Write-Host ("    {0,-16} {1} row(s)" -f $table, (Query "select count(*) from $table"))
	}
	Write-Host '==> RESTORE TEST PASSED'
} catch {
	$failed = $true
	Write-Error "RESTORE TEST FAILED: $($_.Exception.Message)" -ErrorAction Continue
} finally {
	docker rm -f $name 2>$null | Out-Null
}
if ($failed) { exit 1 }
exit 0
