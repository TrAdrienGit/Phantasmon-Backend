<#
.SYNOPSIS
  Backs up the Phantasmon PostgreSQL database (pg_dump custom format) with rotation - CAD Partie 3 §I, TODO-5.

.DESCRIPTION
  Source, picked automatically:
    - Docker: when the "phantasmon-postgres" container (docker-compose.yml) is running, pg_dump runs inside it;
    - native: otherwise, the local pg_dump.exe against BDD_HOST:BDD_PORT with BDD_USER / BDD_PASSWORD.
  Settings come from environment variables, else from the repo's .env. Nothing secret is printed.

  Rotation (CAD Partie 3 §I):
    <BackupDir>\daily\   one dump per run, kept 14 days;
    <BackupDir>\weekly\  Sunday's dump copied here, kept 92 days (about 3 months).

  The dumps contain real player data: never commit them (backups/ and *.dump are gitignored).

.PARAMETER BackupDir
  Default: <repo>\backups. Prefer a folder on another disk (or synced elsewhere) in production.

.PARAMETER Source
  auto (default), docker or native.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\backup-database.ps1
#>
param(
	[string]$BackupDir,
	[ValidateSet('auto', 'docker', 'native')][string]$Source = 'auto',
	[string]$Container,
	[int]$DailyRetentionDays = 14,
	[int]$WeeklyRetentionDays = 92
)

. (Join-Path $PSScriptRoot 'db-common.ps1')

if (-not $BackupDir) { $BackupDir = Join-Path $RepoRoot 'backups' }
if (-not $Container) { $Container = $PostgresContainer }
$dailyDir = Join-Path $BackupDir 'daily'
$weeklyDir = Join-Path $BackupDir 'weekly'
New-Item -ItemType Directory -Force -Path $dailyDir, $weeklyDir | Out-Null

if ($Source -eq 'auto') {
	$Source = if (Test-ContainerRunning $Container) { 'docker' } else { 'native' }
}

$now = Get-Date
$fileName = 'phantasmon_{0}.dump' -f $now.ToString('yyyy-MM-dd_HH-mm-ss')
$target = Join-Path $dailyDir $fileName
$partial = "$target.partial"

try {
	if ($Source -eq 'docker') {
		if (-not (Test-ContainerRunning $Container)) { throw "Container '$Container' is not running." }
		Write-Host "==> Backing up from Docker container '$Container'..."
		# Dumped to a file inside the container then copied out: piping binary output through PowerShell 5.1
		# would corrupt it. The official image trusts local socket connections: no password needed.
		$inContainer = "/tmp/$fileName"
		Invoke-Checked 'pg_dump (docker)' {
			docker exec $Container sh -c "pg_dump -U `"`$POSTGRES_USER`" -d `"`$POSTGRES_DB`" -Fc -f '$inContainer'"
		}
		try {
			Invoke-Checked 'docker cp' { docker cp "${Container}:$inContainer" $partial }
		} finally {
			docker exec $Container rm -f $inContainer | Out-Null
		}
	} else {
		$db = Get-DbSettings
		if ([string]::IsNullOrEmpty($db.BDD_USER)) { throw 'BDD_USER is missing (environment or .env).' }
		$pgDump = Find-PgTool 'pg_dump'
		Write-Host ("==> Backing up native PostgreSQL {0}:{1}/{2}..." -f $db.BDD_HOST, $db.BDD_PORT, $db.BDD_NAME)
		$previousPassword = $env:PGPASSWORD
		$env:PGPASSWORD = $db.BDD_PASSWORD
		try {
			Invoke-Checked 'pg_dump (native)' {
				& $pgDump -h $db.BDD_HOST -p $db.BDD_PORT -U $db.BDD_USER -d $db.BDD_NAME -Fc -f $partial --no-password
			}
		} finally {
			$env:PGPASSWORD = $previousPassword
		}
	}

	if (-not (Test-Path $partial) -or (Get-Item $partial).Length -eq 0) { throw 'pg_dump produced no data.' }
	Move-Item -Force $partial $target
} catch {
	Remove-Item -Force -ErrorAction SilentlyContinue $partial
	Write-Error "Backup FAILED: $($_.Exception.Message)"
	exit 1
}

$size = '{0:N1} KiB' -f ((Get-Item $target).Length / 1KB)
Write-Host "==> Backup written: $target ($size)"

if ($now.DayOfWeek -eq [DayOfWeek]::Sunday) {
	Copy-Item $target (Join-Path $weeklyDir $fileName)
	Write-Host '==> Sunday: copied to weekly\'
}

$removed = 0
foreach ($rule in @(@($dailyDir, $DailyRetentionDays), @($weeklyDir, $WeeklyRetentionDays))) {
	$limit = $now.AddDays(-$rule[1])
	Get-ChildItem $rule[0] -Filter 'phantasmon_*.dump' | Where-Object { $_.LastWriteTime -lt $limit } | ForEach-Object {
		Remove-Item -Force $_.FullName
		$removed++
	}
}
if ($removed -gt 0) { Write-Host "==> Rotation: $removed old dump(s) deleted" }
exit 0
