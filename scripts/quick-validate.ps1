# Quick Discovery Test Validation
# Usage: .\scripts\quick-validate.ps1

Write-Host "`n=== DISCOVERY TEST QUICK VALIDATION ===`n" -ForegroundColor Cyan

$cacheDir = ".semantic-cache"

# 1. Check clusters
Write-Host "1. Clusters Discovered:" -ForegroundColor Yellow
if (Test-Path $cacheDir) {
    $clusters = Get-ChildItem $cacheDir -Directory | Where-Object { $_.Name -ne ".tools" }
    Write-Host "   Count: $($clusters.Count)" -ForegroundColor White
    $clusters | ForEach-Object { Write-Host "   - $($_.Name)" -ForegroundColor Gray }
} else {
    Write-Host "   ERROR: No .semantic-cache found" -ForegroundColor Red
}

# 2. Check links
Write-Host "`n2. Cross-Links:" -ForegroundColor Yellow
$linksFile = "$cacheDir\links.yaml"
if (Test-Path $linksFile) {
    $linkCount = (Select-String -Path $linksFile -Pattern "^- source:" | Measure-Object).Count
    Write-Host "   links.yaml: EXISTS ($linkCount links)" -ForegroundColor Green
} else {
    Write-Host "   links.yaml: NOT FOUND" -ForegroundColor Red
    Write-Host "   Fix: Add mode='project_wide' to cross_link phase in strategy YAML" -ForegroundColor Yellow
}

# 3. Check tool outputs
Write-Host "`n3. Tool Outputs:" -ForegroundColor Yellow
@("file-inventory.json", "language-stats.yaml", "call-graph.json") | ForEach-Object {
    $file = "$cacheDir\.tools\$_"
    if (Test-Path $file) {
        $size = (Get-Item $file).Length
        Write-Host "   $_ : EXISTS (${size} bytes)" -ForegroundColor Green
    } else {
        Write-Host "   $_ : NOT FOUND" -ForegroundColor Red
    }
}

# 4. Check execution log
Write-Host "`n4. Last Execution:" -ForegroundColor Yellow
$logFile = "$cacheDir\.tools\logs\strategy-runner-latest.json"
if (Test-Path $logFile) {
    $log = Get-Content $logFile | ConvertFrom-Json
    Write-Host "   Timestamp: $($log.timestamp)" -ForegroundColor White
    Write-Host "   Depth: $($log.depth)" -ForegroundColor White

    $ok = ($log.entries | Where-Object { $_.status -eq "ok" }).Count
    $fail = ($log.entries | Where-Object { $_.status -eq "failed" }).Count
    $skip = ($log.entries | Where-Object { $_.status -eq "skipped" }).Count

    Write-Host "   Success: $ok | Failed: $fail | Skipped: $skip" -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Yellow" })
} else {
    Write-Host "   ERROR: No execution log found" -ForegroundColor Red
}

# 5. Simple health score
Write-Host "`n5. Health Score:" -ForegroundColor Yellow
$score = 0
$score += $clusters.Count * 10
$score += if (Test-Path $linksFile) { 20 } else { 0 }
$score += $ok * 5

Write-Host "   Score: $score/100" -ForegroundColor $(
    if ($score -ge 80) { "Green" }
    elseif ($score -ge 50) { "Yellow" }
    else { "Red" }
)

Write-Host "`n=== VALIDATION COMPLETE ===`n" -ForegroundColor Cyan

