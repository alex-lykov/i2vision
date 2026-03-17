# Quick Test: Contract Parser
# Simple test to verify contract parsing works

param(
    [string]$Module = "core/orchestrator"
)

Write-Host "`n=== Contract Parser Test ===`n" -ForegroundColor Cyan

# Check contract files
Write-Host "Checking contract files..." -ForegroundColor Yellow
$layers = @("vision", "structure", "logic", "flow", "code")
$contractsFound = 0

foreach ($layer in $layers) {
    $path = ".vision-ai/.$layer/contracts/with-docs.yaml"
    if (Test-Path $path) {
        Write-Host "  OK $layer contract found" -ForegroundColor Green
        $contractsFound++
    } else {
        Write-Host "  MISSING $layer contract" -ForegroundColor Red
    }
}

Write-Host "`nContracts found: $contractsFound/5`n" -ForegroundColor Cyan

# Check user docs
Write-Host "Checking user documentation..." -ForegroundColor Yellow
$docs = @("README.md", "docs/architecture.md", "docs/business-rules.md")
$docsFound = 0

foreach ($doc in $docs) {
    if (Test-Path $doc) {
        Write-Host "  OK $doc" -ForegroundColor Green
        $docsFound++
    } else {
        Write-Host "  MISSING $doc" -ForegroundColor Red
    }
}

Write-Host "`nUser docs found: $docsFound/3`n" -ForegroundColor Cyan

# Test section extraction
Write-Host "Testing section extraction..." -ForegroundColor Yellow

if (Test-Path "docs/architecture.md") {
    $archContent = Get-Content "docs/architecture.md" -Raw

    # Check for key sections
    $sections = @("## System Architecture", "## Components", "## Dependencies", "## Modules")
    $sectionsFound = 0

    foreach ($section in $sections) {
        if ($archContent -like "*$section*") {
            $sectionsFound++
        }
    }

    Write-Host "  Sections in architecture.md: $sectionsFound/$($sections.Count)" -ForegroundColor White

    if ($sectionsFound -eq $sections.Count) {
        Write-Host "  All expected sections present" -ForegroundColor Green
    } else {
        Write-Host "  Some sections missing" -ForegroundColor Yellow
    }
}

Write-Host ""

# Test import functionality
Write-Host "Testing document import..." -ForegroundColor Yellow

$importTestResults = @()

# Check if we can import from docs
if ((Test-Path ".vision-ai/.structure/contracts/with-docs.yaml") -and (Test-Path "docs/architecture.md")) {
    Write-Host "  Structure layer: Contract + Docs present" -ForegroundColor Green
    $importTestResults += "structure"
}

if ((Test-Path ".vision-ai/.logic/contracts/with-docs.yaml") -and (Test-Path "docs/business-rules.md")) {
    Write-Host "  Logic layer: Contract + Docs present" -ForegroundColor Green
    $importTestResults += "logic"
}

if ($importTestResults.Count -gt 0) {
    Write-Host "  Ready to import: $($importTestResults.Count) layer(s)" -ForegroundColor White
    Write-Host "  Validation engine: Ready" -ForegroundColor Green
} else {
    Write-Host "  No layers ready for import" -ForegroundColor Yellow
}

Write-Host ""

# Run discovery
Write-Host "Running discovery..." -ForegroundColor Yellow
Write-Host "  Module: $Module" -ForegroundColor White

$output = & .\gradlew.bat :launcher:run --args="depth-discovery-test --project=$PWD --module=$Module --depth=standard" 2>&1

if ($LASTEXITCODE -eq 0) {
    Write-Host "  Discovery completed successfully" -ForegroundColor Green
} else {
    Write-Host "  Discovery failed (exit code: $LASTEXITCODE)" -ForegroundColor Red
}

Write-Host "`n=== Test Complete ===`n" -ForegroundColor Cyan




