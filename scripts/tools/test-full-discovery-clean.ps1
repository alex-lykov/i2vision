<#
Minimal Full Project Discovery Test (clean)

This script is a safe, minimal replacement intended for CI / local runs.
#>

[CmdletBinding()]
param(
	[string]$ProjectPath = '.',
	[string]$DependencyType = 'uses'
)

$ErrorActionPreference = 'Stop'

# Resolve repo root
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
if ($ProjectPath -and $ProjectPath -ne '.') { $repoRoot = (Resolve-Path $ProjectPath).Path } else { $repoRoot = (Resolve-Path (Join-Path $scriptDir '..\..')).Path }
Write-Host "Repository root: $repoRoot" -ForegroundColor Gray

$gradlePath = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path $gradlePath)) { Write-Error "gradlew.bat not found in repo root: $repoRoot"; exit 2 }

$semanticCacheRoot = Join-Path $repoRoot '.semantic-cache'

function Run-Gradle($args) {
	$cmd = "`"$gradlePath`" $args --no-daemon --console=plain -q"
	Write-Host "Running: $cmd" -ForegroundColor DarkGray
	return cmd /c $cmd 2>&1
}

if (Test-Path $semanticCacheRoot) { Write-Host "Purging .semantic-cache/ ..." -ForegroundColor Red; Remove-Item -Path (Join-Path $semanticCacheRoot '*') -Recurse -Force -ErrorAction SilentlyContinue }

Write-Host "Running project-wide discovery (ai_discover) ..." -ForegroundColor Cyan
$discoverArgs = ":configurable-agent:run --args=`"discover --module project --project `"$repoRoot`" --mode full`""
$discoverOut = Run-Gradle $discoverArgs
Write-Host $discoverOut -ForegroundColor Gray

Write-Host "Running validate-links (project_wide) ..." -ForegroundColor Cyan
$validateArgs = ":configurable-agent:run --args=`"validate-links --module project --project `"$repoRoot`" --mode project_wide`""
$validateOut = Run-Gradle $validateArgs

$linksPath = Join-Path $semanticCacheRoot 'links.yaml'
if (Test-Path $linksPath) { Write-Host "`nGenerated links registry: $linksPath" -ForegroundColor Green; Get-Content $linksPath -TotalCount 120 | ForEach-Object { Write-Host $_ } } else { Write-Host "No links.yaml produced." -ForegroundColor Red }

Write-Host "Full discovery completed." -ForegroundColor Cyan
exit 0


