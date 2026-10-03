# Shared helpers for backup-database.ps1 and test-restore.ps1 (dot-sourced, not run on its own).
# Windows PowerShell 5.1 compatible.

$ErrorActionPreference = 'Stop'

$RepoRoot = Split-Path -Parent $PSScriptRoot
$PostgresContainer = 'phantasmon-postgres'
$PostgresImage = 'postgres:18'

# BDD_* settings: process/system environment variables first (production, see guides/deployment.md), then .env.
# Values are never printed - BDD_PASSWORD is a real secret.
function Get-DbSettings {
	$dotEnv = @{}
	$envFile = Join-Path $RepoRoot '.env'
	if (Test-Path $envFile) {
		foreach ($line in Get-Content $envFile) {
			if ($line -match '^\s*([A-Z_][A-Z0-9_]*)\s*=\s*(.*?)\s*$') {
				$dotEnv[$Matches[1]] = $Matches[2].Trim('"').Trim("'")
			}
		}
	}
	$settings = @{}
	foreach ($key in 'BDD_USER', 'BDD_PASSWORD', 'BDD_NAME', 'BDD_HOST', 'BDD_PORT') {
		$value = [Environment]::GetEnvironmentVariable($key)
		if ([string]::IsNullOrEmpty($value)) { $value = $dotEnv[$key] }
		$settings[$key] = $value
	}
	if ([string]::IsNullOrEmpty($settings.BDD_NAME)) { $settings.BDD_NAME = 'db_phantasmon' }
	if ([string]::IsNullOrEmpty($settings.BDD_HOST)) { $settings.BDD_HOST = 'localhost' }
	if ([string]::IsNullOrEmpty($settings.BDD_PORT)) { $settings.BDD_PORT = '5432' }
	return $settings
}

function Test-ContainerRunning([string]$name) {
	if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { return $false }
	$running = & docker ps --filter "name=^/$name$" --format '{{.Names}}' 2>$null
	return ($LASTEXITCODE -eq 0) -and ($running -eq $name)
}

# Native PostgreSQL client tool: PATH first, then the newest C:\Program Files\PostgreSQL\<n>\bin.
function Find-PgTool([string]$tool) {
	$onPath = Get-Command "$tool.exe" -ErrorAction SilentlyContinue
	if ($onPath) { return $onPath.Source }
	$roots = Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
		Sort-Object { [int]($_.Name -replace '\D', '0') } -Descending
	foreach ($root in $roots) {
		$candidate = Join-Path $root.FullName "bin\$tool.exe"
		if (Test-Path $candidate) { return $candidate }
	}
	throw "$tool.exe not found (neither on PATH nor under C:\Program Files\PostgreSQL\<version>\bin)."
}

# Runs a native command and fails loudly on a non-zero exit code (PowerShell 5.1 does not do it by itself).
function Invoke-Checked([string]$what, [scriptblock]$command) {
	& $command
	if ($LASTEXITCODE -ne 0) { throw "$what failed (exit code $LASTEXITCODE)." }
}
