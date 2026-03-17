# Test Documentation Contracts Discovery
# Tests contract-based discovery with orchestrator or launcher module

param(
    [string]$Module = "core/orchestrator",
    [ValidateSet("all", "vision", "structure", "logic", "flow", "code")]
    [string]$Layer = "all",
    [switch]$Purge,
    [switch]$Verbose
)

$ErrorActionPreference = "Continue"

# Colors
function Write-Header { param($Text) Write-Host "`n=== $Text ===" -ForegroundColor Cyan }
function Write-Success { param($Text) Write-Host "✓ $Text" -ForegroundColor Green }
function Write-Warning { param($Text) Write-Host "⚠ $Text" -ForegroundColor Yellow }
function Write-Error { param($Text) Write-Host "✗ $Text" -ForegroundColor Red }
function Write-Info { param($Text) Write-Host "  $Text" -ForegroundColor White }

Write-Header "Documentation Contracts Discovery Test"
Write-Info "Module: $Module"
Write-Info "Layer: $Layer"
Write-Info "Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host ""

# Step 1: Purge if requested
if ($Purge) {
    Write-Header "Purging Semantic Cache"
    if (Test-Path ".semantic-cache") {
        $fileCount = (Get-ChildItem ".semantic-cache" -Recurse -File).Count
        Remove-Item ".semantic-cache" -Recurse -Force -ErrorAction SilentlyContinue
        Write-Success "Purged $fileCount files from .semantic-cache"
    }
    else {
        Write-Info "No semantic cache to purge"
    }
}

# Step 2: Check contract files
Write-Header "Checking Contract Files"
$layers = @("vision", "structure", "logic", "flow", "code")
$contractsPresent = @{}

foreach ($l in $layers) {
    $contractPath = ".vision-ai/.$l/contracts/with-docs.yaml"
    if (Test-Path $contractPath) {
        Write-Success "$l contract present: $contractPath"
        $contractsPresent[$l] = $true
    }
    else {
        Write-Warning "$l contract missing: $contractPath"
        $contractsPresent[$l] = $false
    }
}

$totalContracts = ($contractsPresent.Values | Where-Object { $_ -eq $true }).Count
Write-Info "Contract coverage: $totalContracts/5 layers"

# Step 3: Check documentation files
Write-Header "Checking Documentation Files"
$docsExpected = @{
    "vision" = @("README.md", "docs/INDEX.md")
    "structure" = @("docs/architecture.md", "docs/PROJECT_STRUCTURE.md")
    "logic" = @("docs/ORCHESTRATOR.md", "docs/STRATEGIES.md")
    "flow" = @("docs/ORCHESTRATOR.md", "docs/VSLFC_LAYERS.md")
    "code" = @("docs/API_REFERENCE.md")
}

Write-Info "User-written documentation (docs/):"
$docsPresent = @{}
foreach ($l in $layers) {
    $found = 0
    $total = $docsExpected[$l].Count
    foreach ($doc in $docsExpected[$l]) {
        if (Test-Path $doc) {
            $found++
        }
    }
    $docsPresent[$l] = $found -gt 0

    if ($found -gt 0) {
        Write-Success "$l docs: $found/$total files present"
    }
    else {
        Write-Warning "$l docs: No user documentation found (will use code discovery)"
    }
}

Write-Info ""
Write-Info "i2vision-generated documentation (.semantic-cache/<cluster>/docs/):"
Write-Info "  (These will be created after discovery runs)"

# Step 4: Run discovery
Write-Header "Running Contract-Based Discovery"
Write-Info "Executing: gradlew :launcher:run --args='depth-discovery-test --project=$PWD --module=$Module --depth=standard'"

$discoveryOutput = & .\gradlew.bat :launcher:run --args="depth-discovery-test --project=$PWD --module=$Module --depth=standard" 2>&1

if ($Verbose) {
    Write-Host "`n--- Discovery Output ---" -ForegroundColor DarkGray
    $discoveryOutput | Write-Host -ForegroundColor DarkGray
    Write-Host "--- End Output ---`n" -ForegroundColor DarkGray
}

# Check if discovery succeeded
if ($LASTEXITCODE -eq 0) {
    Write-Success "Discovery completed successfully"
}
else {
    Write-Error "Discovery failed with exit code: $LASTEXITCODE"
    Write-Info "Run with -Verbose to see full output"
}

# Step 5: Analyze contract status
Write-Header "Contract Status Analysis"

$contractStatusFile = ".semantic-cache/contract-status.yaml"
if (Test-Path $contractStatusFile) {
    Write-Success "Contract status report found"
    Get-Content $contractStatusFile | Write-Host -ForegroundColor White
}
else {
    Write-Warning "Contract status report not generated"
    Write-Info "File expected: $contractStatusFile"
}

# Step 6: Analyze validation results
Write-Header "Validation Report"

$validationReportFile = ".semantic-cache/validation-report.yaml"
if (Test-Path $validationReportFile) {
    $report = Get-Content $validationReportFile -Raw
    Write-Success "Validation report found"
    Write-Host $report -ForegroundColor White

    # Parse and summarize
    $issueCount = ([regex]::Matches($report, "severity: (ERROR|WARNING|INFO)")).Count
    $errorCount = ([regex]::Matches($report, "severity: ERROR")).Count
    $warningCount = ([regex]::Matches($report, "severity: WARNING")).Count

    Write-Info ""
    Write-Info "Issues found: $issueCount"
    if ($errorCount -gt 0) { Write-Error "  Errors: $errorCount" }
    if ($warningCount -gt 0) { Write-Warning "  Warnings: $warningCount" }
}
else {
    Write-Warning "Validation report not generated"
    Write-Info "File expected: $validationReportFile"
}

# Step 7: Compare doc vs code discoveries
Write-Header "Doc vs Code Discovery Comparison"

$layersToCheck = if ($Layer -eq "all") { $layers } else { @($Layer) }

foreach ($l in $layersToCheck) {
    $artifactsFile = ".semantic-cache/$Module/$l/artifacts.yaml"

    if (Test-Path $artifactsFile) {
        Write-Success "$l layer artifacts found"

        $content = Get-Content $artifactsFile -Raw

        # Count sources
        $docCount = ([regex]::Matches($content, 'source: "documentation"')).Count
        $codeCount = ([regex]::Matches($content, 'source: "code"')).Count
        $llmCount = ([regex]::Matches($content, 'source: "llm_inference"')).Count

        Write-Info "  From documentation: $docCount"
        Write-Info "  From code: $codeCount"
        Write-Info "  From LLM inference: $llmCount"

        # Average confidence
        $confidenceMatches = [regex]::Matches($content, 'confidence: ([0-9.]+)')
        if ($confidenceMatches.Count -gt 0) {
            $avgConfidence = ($confidenceMatches | ForEach-Object { [double]$_.Groups[1].Value } | Measure-Object -Average).Average
            Write-Info "  Average confidence: $([math]::Round($avgConfidence, 2))"
        }

        # Show sample
        if ($Verbose) {
            Write-Host "`n--- Sample Artifacts ($l) ---" -ForegroundColor DarkGray
            Get-Content $artifactsFile | Select-Object -First 20 | Write-Host -ForegroundColor DarkGray
            Write-Host "--- End Sample ---`n" -ForegroundColor DarkGray
        }
    }
    else {
        Write-Warning "$l layer artifacts not found: $artifactsFile"
    }
}

# Step 8: Check for contradictions
Write-Header "Contradiction Detection"

$contradictionsFile = ".semantic-cache/contradictions.yaml"
if (Test-Path $contradictionsFile) {
    $contradictions = Get-Content $contradictionsFile -Raw
    $contradictionCount = ([regex]::Matches($contradictions, "- layer:")).Count

    if ($contradictionCount -gt 0) {
        Write-Warning "Found $contradictionCount contradictions between docs and code"
        Write-Host $contradictions -ForegroundColor Yellow
    }
    else {
        Write-Success "No contradictions found"
    }
}
else {
    Write-Info "No contradictions file generated (this is normal)"
}

# Step 8.5: Check generated documentation
Write-Header "Generated Documentation (i2vision)"

Write-Info "Checking for auto-generated docs in .semantic-cache/$Module/docs/"
$generatedDocsPath = ".semantic-cache/$Module/docs"

if (Test-Path $generatedDocsPath) {
    $generatedDocs = Get-ChildItem $generatedDocsPath -Filter "generated-*.md"
    if ($generatedDocs.Count -gt 0) {
        Write-Success "Found $($generatedDocs.Count) generated documentation file(s):"
        foreach ($doc in $generatedDocs) {
            $sizeKB = [math]::Round($doc.Length / 1024, 1)
            Write-Info "  - $($doc.Name) ($sizeKB" + " KB)"
        }
    }
    else {
        Write-Warning "No generated docs found (will be created when export is implemented)"
    }
}
else {
    Write-Info "Generated docs folder not created yet (normal - created on export)"
}

# Step 9: Freshness check
Write-Header "Documentation Freshness"

foreach ($l in $layersToCheck) {
    $primaryDoc = $docsExpected[$l][0]

    if (Test-Path $primaryDoc) {
        $lastModified = (Get-Item $primaryDoc).LastWriteTime
        $daysOld = (New-TimeSpan -Start $lastModified -End (Get-Date)).Days

        if ($daysOld -lt 30) {
            Write-Success "$l docs: Fresh - $daysOld days old"
        }
        elseif ($daysOld -lt 90) {
            Write-Info "$l docs: Acceptable - $daysOld days old"
        }
        elseif ($daysOld -lt 180) {
            Write-Warning "$l docs: Stale - $daysOld days old - should review"
        }
        else {
            Write-Error "$l docs: Very stale - $daysOld days old - needs update"
        }
    }
    else {
        Write-Warning "$l docs: Not found"
    }
}

# Step 10: Summary
Write-Header "Summary"

$totalIssues = 0
if (Test-Path $validationReportFile) {
    $report = Get-Content $validationReportFile -Raw
    $totalIssues = ([regex]::Matches($report, "severity:")).Count
}

Write-Host ""
Write-Info "Module: $Module"
Write-Info "Layers tested: $Layer"
Write-Info "Contracts present: $totalContracts/5"
Write-Info "Documentation present: $(($docsPresent.Values | Where-Object { $_ -eq $true }).Count)/5"
Write-Info "Validation issues: $totalIssues"
Write-Host ""

if ($totalIssues -eq 0 -and $totalContracts -eq 5) {
    Write-Success "All contracts validated successfully! ✨"
}
elseif ($totalIssues -lt 5) {
    Write-Warning "Minor issues found - review validation report"
}
else {
    Write-Error "Multiple validation issues - review reports in .semantic-cache/"
}

Write-Host ""
Write-Info "Detailed results:"
Write-Info "  Contract status:   .semantic-cache/contract-status.yaml"
Write-Info "  Validation report: .semantic-cache/validation-report.yaml"
Write-Info "  Layer artifacts:   .semantic-cache/$Module/<layer>/artifacts.yaml"
Write-Host ""

# Step 11: Generate quick report file
Write-Header "Generating Test Report"

$reportFile = "test-contracts-report.txt"
$reportContent = @"
Documentation Contracts Discovery Test Report
==============================================
Date: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
Module: $Module
Layer: $Layer

Contract Coverage: $totalContracts/5
Documentation Coverage: $(($docsPresent.Values | Where-Object { $_ -eq $true }).Count)/5
Validation Issues: $totalIssues

Layers Tested:
$(foreach ($l in $layersToCheck) { "  - $l" })

Contract Status:
$(foreach ($l in $layers) {
    $status = if ($contractsPresent[$l]) { "✓" } else { "✗" }
    "  $status $l"
})

Documentation Status:
$(foreach ($l in $layers) {
    $status = if ($docsPresent[$l]) { "✓" } else { "✗" }
    "  $status $l"
})

Files Generated:
  - .semantic-cache/contract-status.yaml
  - .semantic-cache/validation-report.yaml
  - .semantic-cache/$Module/<layer>/artifacts.yaml

Test completed at: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')
"@

Set-Content -Path $reportFile -Value $reportContent
Write-Success "Test report saved to: $reportFile"

Write-Host ""
Write-Host "Test completed!" -ForegroundColor Green






