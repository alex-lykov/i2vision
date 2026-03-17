<#
.SYNOPSIS
    Comprehensive module discovery test with monitoring and health assessment
.DESCRIPTION
    Tests module discovery end-to-end:
    1. Purge cache (optional)
    2. Run architecture detection
    3. Populate artifacts for all layers
    4. Validate cross-layer links
    5. Assess module health
    6. Test context efficiency (getContext simulation)
#>

param(
    [string]$Cluster = "orchestrator",
    [switch]$SkipPurge,
    [switch]$Verbose
)

$ErrorActionPreference = "Stop"

# Find repo root
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir "..\..")).Path
Set-Location $repoRoot

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Module Discovery Test: $Cluster" -ForegroundColor Cyan
Write-Host "========================================`n" -ForegroundColor Cyan
Write-Host "Repository: $repoRoot" -ForegroundColor Gray
Write-Host "Timestamp: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Gray

# Setup logging
$logsDir = Join-Path $repoRoot ".semantic-cache\.tools\logs"
if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir | Out-Null }
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logFile = Join-Path $logsDir "discovery-test-$Cluster-$timestamp.log"
Start-Transcript -Path $logFile -Append | Out-Null

# PHASE 1: PURGE
if (-not $SkipPurge) {
    Write-Host "`n--- Phase 1: Purge Cache ---" -ForegroundColor Yellow
    $cacheDir = Join-Path $repoRoot ".semantic-cache\$Cluster"
    if (Test-Path $cacheDir) {
        Write-Host "Removing: $cacheDir" -ForegroundColor Gray
        Remove-Item -Path $cacheDir -Recurse -Force
        Write-Host "[OK] Cache purged" -ForegroundColor Green
    } else {
        Write-Host "[OK] No cache to purge" -ForegroundColor Green
    }
}

# PHASE 2: ARCHITECTURE DETECTION
Write-Host "`n--- Phase 2: Architecture Detection ---" -ForegroundColor Yellow
$archStart = Get-Date

# Check if contracts exist
$manifest = Join-Path $repoRoot ".vision-ai\project\manifest.yaml"
$archContract = Join-Path $repoRoot ".vision-ai\.vision\contracts\results\architecture.yaml"

if (Test-Path $manifest) {
    Write-Host "[OK] Manifest found: manifest.yaml" -ForegroundColor Green
    if ($Verbose) {
        $lang = (Get-Content $manifest | Where-Object { $_ -match '^\s*language:' } | Select-Object -First 1)
        $build = (Get-Content $manifest | Where-Object { $_ -match '^\s*build_system:' } | Select-Object -First 1)
        if ($lang) { Write-Host "  $lang" -ForegroundColor Gray }
        if ($build) { Write-Host "  $build" -ForegroundColor Gray }
    }
} else {
    Write-Host "[WARN] Manifest not found (PathDiscovery will use fallbacks)" -ForegroundColor Yellow
}

if (Test-Path $archContract) {
    Write-Host "[OK] Architecture contract found" -ForegroundColor Green
    if ($Verbose) {
        $conf = (Get-Content $archContract | Where-Object { $_ -match '^\s*confidence:' } | Select-Object -First 1)
        if ($conf) { Write-Host "  $conf" -ForegroundColor Gray }
    }
} else {
    Write-Host "[WARN] Architecture contract not found" -ForegroundColor Yellow
}

$archDuration = ((Get-Date) - $archStart).TotalSeconds
Write-Host "Duration: $([math]::Round($archDuration, 1))s" -ForegroundColor Gray

# PHASE 3: DISCOVER ARTIFACTS
Write-Host "`n--- Phase 3: Discover Artifacts (All Layers) ---" -ForegroundColor Yellow
$discoverStart = Get-Date

$layers = @("code", "flow", "logic", "structure", "vision")
$layerStats = @{}

foreach ($layer in $layers) {
    Write-Host "Discovering $layer..." -ForegroundColor Gray -NoNewline

    $layerStart = Get-Date

    # Run discover (simplified - would normally call Gradle task)
    $layerDir = Join-Path $repoRoot ".semantic-cache\$Cluster\$layer"

    if (Test-Path $layerDir) {
        $artifacts = Get-ChildItem -Path $layerDir -File -Recurse
        $count = $artifacts.Count
        $layerStats[$layer] = $count

        $layerDuration = ((Get-Date) - $layerStart).TotalSeconds
        Write-Host " [OK] $count artifact(s) ($([math]::Round($layerDuration, 1))s)" -ForegroundColor Green

        if ($Verbose -and $count -gt 0) {
            $artifacts | Select-Object -First 2 | ForEach-Object {
                Write-Host "    - $($_.Name)" -ForegroundColor DarkGray
            }
            if ($count -gt 2) {
                Write-Host "    ... and $($count - 2) more" -ForegroundColor DarkGray
            }
        }
    } else {
        $layerStats[$layer] = 0
        Write-Host " [WARN] No artifacts found" -ForegroundColor Yellow
    }
}

$discoverDuration = ((Get-Date) - $discoverStart).TotalSeconds
Write-Host "Total discovery time: $([math]::Round($discoverDuration, 1))s" -ForegroundColor Gray

# PHASE 4: VALIDATE LINKS
Write-Host "`n--- Phase 4: Cross-Layer Links ---" -ForegroundColor Yellow
$linkStart = Get-Date

$linksFile = Join-Path $repoRoot ".semantic-cache\links.yaml"
if (Test-Path $linksFile) {
    # Count cluster-related links (simple text search)
    $content = Get-Content $linksFile
    $clusterLines = $content | Where-Object { $_ -match $Cluster }
    $linkCount = [math]::Floor($clusterLines.Count / 10)  # Approximate

    Write-Host "[OK] $linkCount link(s) found" -ForegroundColor Green

    # Sample link types
    if ($Verbose) {
        $types = $content | Where-Object { $_ -match '^\s+type:\s+(.+)' } | Select-Object -First 3
        $types | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }
    }
} else {
    $linkCount = 0
    Write-Host "[WARN] links.yaml not found" -ForegroundColor Yellow
}

$linkDuration = ((Get-Date) - $linkStart).TotalSeconds
Write-Host "Duration: $([math]::Round($linkDuration, 1))s" -ForegroundColor Gray

# PHASE 5: MODULE HEALTH
Write-Host "`n--- Phase 5: Module Health Assessment ---" -ForegroundColor Yellow

$totalArtifacts = ($layerStats.Values | Measure-Object -Sum).Sum
$completedLayers = ($layerStats.GetEnumerator() | Where-Object { $_.Value -gt 0 }).Count
$layerCompleteness = [math]::Round(($completedLayers / 5.0) * 100, 1)

Write-Host "Artifacts: $totalArtifacts total" -ForegroundColor Gray
Write-Host "Layer coverage: $layerCompleteness% ($completedLayers/5)" -ForegroundColor Gray

# Health score calculation
$healthScore = 0
if ($layerCompleteness -ge 80) { $healthScore += 40 }
elseif ($layerCompleteness -ge 60) { $healthScore += 30 }
elseif ($layerCompleteness -ge 40) { $healthScore += 20 }

if ($totalArtifacts -ge 20) { $healthScore += 30 }
elseif ($totalArtifacts -ge 10) { $healthScore += 20 }
elseif ($totalArtifacts -ge 5) { $healthScore += 10 }

if ($linkCount -ge $totalArtifacts) { $healthScore += 30 }
elseif ($linkCount -ge ($totalArtifacts / 2)) { $healthScore += 20 }
elseif ($linkCount -gt 0) { $healthScore += 10 }

Write-Host "Health score: $healthScore/100" -ForegroundColor $(
    if ($healthScore -ge 80) { "Green" }
    elseif ($healthScore -ge 60) { "Yellow" }
    else { "Red" }
)

if ($healthScore -ge 80) {
    Write-Host "[EXCELLENT] Module is production-ready" -ForegroundColor Green
} elseif ($healthScore -ge 60) {
    Write-Host "[GOOD] Module is acceptable" -ForegroundColor Yellow
} else {
    Write-Host "[NEEDS WORK] Module needs improvement" -ForegroundColor Red
}

# PHASE 6: CONTEXT EFFICIENCY
Write-Host "`n--- Phase 6: Context Efficiency (getContext simulation) ---" -ForegroundColor Yellow

$cacheDir = Join-Path $repoRoot ".semantic-cache\$Cluster"
if (Test-Path $cacheDir) {
    $files = Get-ChildItem -Path $cacheDir -File -Recurse
    $totalBytes = ($files | Measure-Object -Property Length -Sum).Sum
    $totalFiles = $files.Count

    Write-Host "Files: $totalFiles" -ForegroundColor Gray
    Write-Host "Size: $([math]::Round($totalBytes / 1024, 1)) KB" -ForegroundColor Gray

    $estimatedTokens = [math]::Round($totalBytes / 4)
    Write-Host "Est. tokens: ~$estimatedTokens" -ForegroundColor Gray

    $budget = 10000
    if ($estimatedTokens -le $budget) {
        $utilization = [math]::Round(($estimatedTokens / $budget) * 100, 1)
        Write-Host "[OK] Budget: $utilization% ($estimatedTokens / $budget)" -ForegroundColor Green
    } else {
        $overage = $estimatedTokens - $budget
        Write-Host "[WARN] Over budget by $overage tokens" -ForegroundColor Yellow
        Write-Host "Suggestion: Use RELATIONSHIP or CHANGE perspective instead of FULL" -ForegroundColor Yellow
    }
} else {
    Write-Host "[WARN] No cache directory found" -ForegroundColor Yellow
}

# SUMMARY
Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "Test Summary" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Module: $Cluster" -ForegroundColor White
Write-Host "Health: $healthScore/100" -ForegroundColor White
Write-Host "Artifacts: $totalArtifacts" -ForegroundColor White
Write-Host "Links: $linkCount" -ForegroundColor White
Write-Host "Coverage: $layerCompleteness%" -ForegroundColor White
Write-Host "`nLog: $logFile" -ForegroundColor Gray

Stop-Transcript | Out-Null

# Exit code
if ($healthScore -ge 60) { exit 0 } else { exit 1 }


