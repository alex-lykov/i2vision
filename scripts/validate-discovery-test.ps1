# Enhanced Discovery Test Validation Script
# Usage: .\scripts\validate-discovery-test.ps1

param(
    [string]$ProjectRoot = "."
)

Write-Host "=== DISCOVERY TEST VALIDATION ===" -ForegroundColor Cyan
Write-Host ""
Write-Host "Project: $ProjectRoot" -ForegroundColor Yellow
Write-Host ""

# 1. Validate clusters
Write-Host "1. Cluster Discovery:" -ForegroundColor White
$cacheDir = Join-Path $ProjectRoot ".semantic-cache"
if (Test-Path $cacheDir) {
    $clusters = Get-ChildItem $cacheDir -Directory | Where-Object { $_.Name -ne ".tools" }
    Write-Host "   Clusters found: $($clusters.Count)" -ForegroundColor $(if ($clusters.Count -gt 0) { "Green" } else { "Yellow" })

    foreach ($cluster in $clusters) {
        Write-Host ""
        Write-Host "   📦 Cluster: $($cluster.Name)" -ForegroundColor Cyan

        # Check VSLFC layers
        $layers = @("code", "flow", "logic", "structure", "vision")
        foreach ($layer in $layers) {
            $layerPath = Join-Path $cluster.FullName $layer
            if (Test-Path $layerPath) {
                $fileCount = (Get-ChildItem $layerPath -File -Recurse).Count
                $icon = if ($fileCount -gt 0) { "✅" } else { "⚠️" }
                Write-Host "      $icon $layer`: $fileCount files" -ForegroundColor $(if ($fileCount -gt 0) { "Green" } else { "Gray" })
            } else {
                Write-Host "      ⚠️  $layer`: not created" -ForegroundColor Gray
            }
        }
    }
} else {
    Write-Host "   ❌ No .semantic-cache directory found" -ForegroundColor Red
}

# 2. Validate links
Write-Host ""
Write-Host "2. Cross-Linking:" -ForegroundColor White
$linksFile = Join-Path $cacheDir "links.yaml"
if (Test-Path $linksFile) {
    $linkCount = (Select-String -Path $linksFile -Pattern "^- source:" | Measure-Object).Count
    Write-Host "   ✅ links.yaml exists" -ForegroundColor Green
    Write-Host "   Links created: $linkCount" -ForegroundColor $(if ($linkCount -gt 0) { "Green" } else { "Yellow" })

    if ($linkCount -gt 0) {
        Write-Host ""
        Write-Host "   Sample links:" -ForegroundColor Gray
        Get-Content $linksFile | Select-Object -First 10 | ForEach-Object {
            Write-Host "   $_" -ForegroundColor DarkGray
        }
    }
} else {
    Write-Host "   ❌ links.yaml NOT created" -ForegroundColor Red
    Write-Host "      Likely cause: cross_link phase missing mode='project_wide' parameter" -ForegroundColor Yellow
}

# 3. Validate tool outputs
Write-Host ""
Write-Host "3. Tool Outputs:" -ForegroundColor White
$toolsDir = Join-Path $cacheDir ".tools"
if (Test-Path $toolsDir) {
    $expectedOutputs = @{
        "file-inventory.json" = "File scanner"
        "language-stats.yaml" = "Language detection"
        "call-graph.json" = "Call graph analyzer"
    }

    foreach ($file in $expectedOutputs.Keys) {
        $filePath = Join-Path $toolsDir $file
        if (Test-Path $filePath) {
            $size = (Get-Item $filePath).Length
            Write-Host "   ✅ $file ($size bytes)" -ForegroundColor Green
        } else {
            Write-Host "   ❌ $file NOT found" -ForegroundColor Red
        }
    }
} else {
    Write-Host "   ❌ No .tools directory found" -ForegroundColor Red
}

# 4. Validate execution logs
Write-Host ""
Write-Host "4. Execution Logs:" -ForegroundColor White
$logsDir = Join-Path $toolsDir "logs"
if (Test-Path $logsDir) {
    $latestLog = Join-Path $logsDir "strategy-runner-latest.json"
    if (Test-Path $latestLog) {
        $log = Get-Content $latestLog | ConvertFrom-Json

        Write-Host "   ✅ Execution log found" -ForegroundColor Green
        Write-Host "   Timestamp: $($log.timestamp)" -ForegroundColor Gray
        Write-Host "   Depth: $($log.depth)" -ForegroundColor Gray
        Write-Host ""
        Write-Host "   Phase Results:" -ForegroundColor White

        $successCount = 0
        $failCount = 0
        $skipCount = 0

        foreach ($entry in $log.entries) {
            $icon = switch ($entry.status) {
                "ok" { "✅"; $successCount++ }
                "failed" { "❌"; $failCount++ }
                "skipped" { "⏭️"; $skipCount++ }
                default { "❓" }
            }

            $color = switch ($entry.status) {
                "ok" { "Green" }
                "failed" { "Red" }
                "skipped" { "Yellow" }
                default { "Gray" }
            }

            Write-Host "   $icon $($entry.name): $($entry.status)" -ForegroundColor $color
            if ($entry.error) {
                Write-Host "      Error: $($entry.error)" -ForegroundColor Red
            }
        }

        Write-Host ""
        Write-Host "   Summary:" -ForegroundColor White
        Write-Host "   ✅ Success: $successCount" -ForegroundColor Green
        Write-Host "   ❌ Failed: $failCount" -ForegroundColor $(if ($failCount -gt 0) { "Red" } else { "Gray" })
        Write-Host "   ⏭️  Skipped: $skipCount" -ForegroundColor $(if ($skipCount -gt 0) { "Yellow" } else { "Gray" })

        $successRate = [math]::Round(($successCount / ($successCount + $failCount + $skipCount)) * 100, 0)
        Write-Host "   Success rate: $successRate%" -ForegroundColor $(if ($successRate -ge 80) { "Green" } elseif ($successRate -ge 60) { "Yellow" } else { "Red" })
    } else {
        Write-Host "   ❌ No latest execution log found" -ForegroundColor Red
    }
} else {
    Write-Host "   ❌ No logs directory found" -ForegroundColor Red
}

# 5. Calculate health score
Write-Host ""
Write-Host "5. Health Assessment:" -ForegroundColor White

$healthScore = 0
$maxScore = 100

# Re-count clusters for health calculation
$healthClusters = if (Test-Path $cacheDir) {
    (Get-ChildItem $cacheDir -Directory | Where-Object { $_.Name -ne ".tools" }).Count
} else { 0 }

# Clusters discovered (30 points)
$clusterScore = [math]::Min($healthClusters * 10, 30)
$healthScore += $clusterScore
Write-Host "   Clusters: $healthClusters → ${clusterScore}/30 points" -ForegroundColor Gray

# Artifacts created (40 points)
$totalArtifacts = 0
if (Test-Path $cacheDir) {
    $healthClusterDirs = Get-ChildItem $cacheDir -Directory | Where-Object { $_.Name -ne ".tools" }
    foreach ($cluster in $healthClusterDirs) {
        $totalArtifacts += (Get-ChildItem $cluster.FullName -File -Recurse -ErrorAction SilentlyContinue).Count
    }
}
$artifactScore = [math]::Min($totalArtifacts, 40)
$healthScore += $artifactScore
Write-Host "   Artifacts: $totalArtifacts → ${artifactScore}/40 points" -ForegroundColor Gray

# Links created (20 points)
$healthLinkCount = 0
$linkScore = if (Test-Path $linksFile) {
    $healthLinkCount = (Select-String -Path $linksFile -Pattern "^- source:" -ErrorAction SilentlyContinue | Measure-Object).Count
    [math]::Min($healthLinkCount * 2, 20)
} else { 0 }
$healthScore += $linkScore
Write-Host "   Links: $healthLinkCount → ${linkScore}/20 points" -ForegroundColor Gray

# Tool success (10 points)
$healthSuccessRate = if (Test-Path (Join-Path $logsDir "strategy-runner-latest.json")) {
    $log = Get-Content (Join-Path $logsDir "strategy-runner-latest.json") | ConvertFrom-Json
    $sc = ($log.entries | Where-Object { $_.status -eq "ok" }).Count
    $total = $log.entries.Count
    if ($total -gt 0) { [math]::Round(($sc / $total) * 100, 0) } else { 0 }
} else { 0 }

$toolScore = if ($healthSuccessRate -ge 80) { 10 } elseif ($healthSuccessRate -ge 60) { 5 } else { 0 }
$healthScore += $toolScore
Write-Host "   Tool success: ${healthSuccessRate}% → ${toolScore}/10 points" -ForegroundColor Gray

Write-Host ""
Write-Host "   Overall Health Score: $healthScore/100" -ForegroundColor $(
    if ($healthScore -ge 80) { "Green" }
    elseif ($healthScore -ge 60) { "Yellow" }
    else { "Red" }
)

$grade = if ($healthScore -ge 90) { "A" }
    elseif ($healthScore -ge 80) { "B" }
    elseif ($healthScore -ge 70) { "C" }
    elseif ($healthScore -ge 60) { "D" }
    else { "F" }

Write-Host "   Grade: $grade" -ForegroundColor $(
    if ($grade -in @("A", "B")) { "Green" }
    elseif ($grade -eq "C") { "Yellow" }
    else { "Red" }
)

# 6. Recommendations
Write-Host ""
Write-Host "6. Recommendations:" -ForegroundColor White

if ($clusters.Count -eq 0) {
    Write-Host "   ⚠️  No clusters discovered - test project may be too small" -ForegroundColor Yellow
    Write-Host "      → Try testing on a larger project with multiple modules" -ForegroundColor Gray
}

if (-not (Test-Path $linksFile)) {
    Write-Host "   ⚠️  links.yaml not created - cross_link phase may have failed" -ForegroundColor Yellow
    Write-Host "      → Check strategy YAML has: cross_link.params.mode = 'project_wide'" -ForegroundColor Gray
}

if ($totalArtifacts -lt 10) {
    Write-Host "   ⚠️  Very few artifacts generated" -ForegroundColor Yellow
    Write-Host "      → Test project may not have enough source code" -ForegroundColor Gray
    Write-Host "      → Consider testing on a real application (e.g., ktor-samples)" -ForegroundColor Gray
}

if ($failCount -gt 0) {
    Write-Host "   ❌ Some phases failed - check error logs for details" -ForegroundColor Red
    Write-Host "      → View: cat .semantic-cache/.tools/logs/errors.jsonl" -ForegroundColor Gray
}

Write-Host ""
Write-Host "=== VALIDATION COMPLETE ===" -ForegroundColor Cyan
Write-Host ""

# Return exit code based on health score
if ($healthScore -ge 60) {
    exit 0
} else {
    exit 1
}


