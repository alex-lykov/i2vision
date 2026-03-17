# Documentation Quality Validator
# Parses imported artifacts and validates against expected results

param(
    [string]$ProjectRoot = $PSScriptRoot
)

$ErrorActionPreference = "Stop"

function Parse-YamlArtifact {
    param([string]$FilePath)

    if (!(Test-Path $FilePath)) {
        return $null
    }

    $content = Get-Content -Path $FilePath -Raw

    # Simple YAML parser for our use case
    $result = @{
        Items = @()
        Generated = $null
        Source = $null
        TotalImported = 0
    }

    # Extract metadata
    if ($content -match 'generated:\s*(.+)') {
        $result.Generated = $matches[1].Trim()
    }
    if ($content -match 'source:\s*(.+)') {
        $result.Source = $matches[1].Trim()
    }
    if ($content -match 'total_imported:\s*(\d+)') {
        $result.TotalImported = [int]$matches[1]
    }

    # Extract items (simple parsing - look for title/confidence pairs)
    $itemMatches = [regex]::Matches($content, '- title:\s*"([^"]+)"\s+.*?confidence:\s*([\d.]+)', [System.Text.RegularExpressions.RegexOptions]::Singleline)

    foreach ($match in $itemMatches) {
        $result.Items += @{
            Title = $match.Groups[1].Value
            Confidence = [double]$match.Groups[2].Value
        }
    }

    return $result
}

function Count-CodeEvidence {
    param([string]$Component, [string]$ProjectRoot)

    # Search for the component in source files
    $searchPath = Join-Path $ProjectRoot "core\orchestrator\src\main\kotlin"

    if (!(Test-Path $searchPath)) {
        return 0
    }

    $count = 0
    $files = Get-ChildItem -Path $searchPath -Filter "*.kt" -Recurse

    foreach ($file in $files) {
        $content = Get-Content -Path $file.FullName -Raw

        # Count occurrences
        if ($content -match "class\s+$Component") { $count++ }
        if ($content -match "object\s+$Component") { $count++ }
        if ($content -match "interface\s+$Component") { $count++ }
        if ($content -match "fun\s+$Component") { $count++ }
        if ($content -match "val\s+$Component") { $count++ }
    }

    return $count
}

function Validate-Confidence {
    param(
        [string]$Component,
        [double]$ActualConfidence,
        [double]$ExpectedConfidence,
        [double]$Tolerance = 0.15
    )

    $diff = [Math]::Abs($ActualConfidence - $ExpectedConfidence)
    $isValid = $diff -le $Tolerance

    return @{
        Component = $Component
        Expected = $ExpectedConfidence
        Actual = $ActualConfidence
        Difference = $diff
        Valid = $isValid
        Status = if ($isValid) { "✅ PASS" } else { "❌ FAIL" }
    }
}

function Generate-DetailedReport {
    param([hashtable]$Results)

    $report = @"

┌─────────────────────────────────────────────────────────────────┐
│          DETAILED VALIDATION RESULTS                             │
├─────────────────────────────────────────────────────────────────┤

"@

    # Artifact Import Status
    $report += "`n│  ARTIFACT IMPORT STATUS`n│`n"

    foreach ($layer in $Results.Layers.Keys) {
        $layerResult = $Results.Layers[$layer]
        if ($layerResult.Imported) {
            $report += "│  ✅ $layer`: $($layerResult.ItemCount) items imported`n"
        } else {
            $report += "│  ⚠️  $layer`: No artifacts found`n"
        }
    }

    # Confidence Validation
    $report += "`n│`n│  CONFIDENCE VALIDATION`n│`n"

    foreach ($check in $Results.ConfidenceChecks) {
        $report += "│  $($check.Status) $($check.Component)`n"
        $report += "│     Expected: $($check.Expected), Actual: $($check.Actual), Diff: $([Math]::Round($check.Difference, 2))`n"
    }

    # Evidence Analysis
    $report += "`n│`n│  CODE EVIDENCE ANALYSIS`n│`n"

    foreach ($evidence in $Results.Evidence) {
        $report += "│  $($evidence.Component): $($evidence.Count) match(es)`n"
    }

    # Overall Metrics
    $report += "`n│`n│  QUALITY METRICS`n│`n"
    $report += "│  Average Confidence: $([Math]::Round($Results.Metrics.AvgConfidence, 2))`n"
    $report += "│  Total Validations: $($Results.Metrics.TotalChecks)`n"
    $report += "│  Passed: $($Results.Metrics.Passed)`n"
    $report += "│  Failed: $($Results.Metrics.Failed)`n"
    $report += "│  Success Rate: $([Math]::Round($Results.Metrics.SuccessRate * 100, 1))%`n"

    $report += "`n└─────────────────────────────────────────────────────────────────┘`n"

    return $report
}

# Main validation logic
try {
    Write-Host ""
    Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "   DOCUMENTATION QUALITY VALIDATION" -ForegroundColor Cyan
    Write-Host "═══════════════════════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host ""

    $results = @{
        Layers = @{}
        ConfidenceChecks = @()
        Evidence = @()
        Metrics = @{
            AvgConfidence = 0
            TotalChecks = 0
            Passed = 0
            Failed = 0
            SuccessRate = 0
        }
    }

    # Define expected results
    $expectedResults = @{
        DiscoveryPipeline = @{ confidence = 1.0; minEvidence = 3 }
        LinkService = @{ confidence = 0.85; minEvidence = 2 }
        DocContractYamlParser = @{ confidence = 0.9; minEvidence = 2 }
        FutureComponent = @{ confidence = 0.4; minEvidence = 0 }
    }

    # Check layers
    Write-Host "→ Checking imported artifacts..." -ForegroundColor Yellow

    $layerPaths = @{
        VISION = "src/vision"
        STRUCTURE = "src/structure"
        LOGIC = "src/logic"
    }

    foreach ($layer in $layerPaths.Keys) {
        $layerPath = Join-Path $ProjectRoot $layerPaths[$layer]
        $importedFile = Join-Path $layerPath "imported-$($layer.ToLower()).yaml"

        if (Test-Path $importedFile) {
            $artifact = Parse-YamlArtifact -FilePath $importedFile
            $results.Layers[$layer] = @{
                Imported = $true
                ItemCount = $artifact.Items.Count
                Artifact = $artifact
            }
            Write-Host "  ✅ $layer`: Found $($artifact.Items.Count) items" -ForegroundColor Green
        } else {
            $results.Layers[$layer] = @{
                Imported = $false
                ItemCount = 0
            }
            Write-Host "  ⚠️  $layer`: No imported artifacts" -ForegroundColor Yellow
        }
    }

    # Validate confidence scores
    Write-Host "`n→ Validating confidence scores..." -ForegroundColor Yellow

    $confidences = @()

    # Check STRUCTURE layer (has our test components)
    if ($results.Layers.STRUCTURE.Imported) {
        $structureArtifact = $results.Layers.STRUCTURE.Artifact

        foreach ($component in $expectedResults.Keys) {
            $item = $structureArtifact.Items | Where-Object { $_.Title -eq $component } | Select-Object -First 1

            if ($item) {
                $validation = Validate-Confidence -Component $component `
                    -ActualConfidence $item.Confidence `
                    -ExpectedConfidence $expectedResults[$component].confidence

                $results.ConfidenceChecks += $validation
                $confidences += $item.Confidence

                Write-Host "  $($validation.Status) $component (Expected: $($validation.Expected), Actual: $($validation.Actual))" -ForegroundColor $(if ($validation.Valid) { "Green" } else { "Red" })

                if ($validation.Valid) {
                    $results.Metrics.Passed++
                } else {
                    $results.Metrics.Failed++
                }
                $results.Metrics.TotalChecks++
            }
        }
    }

    # Count code evidence
    Write-Host "`n→ Analyzing code evidence..." -ForegroundColor Yellow

    foreach ($component in $expectedResults.Keys) {
        $evidenceCount = Count-CodeEvidence -Component $component -ProjectRoot $ProjectRoot
        $results.Evidence += @{
            Component = $component
            Count = $evidenceCount
            Expected = $expectedResults[$component].minEvidence
            Status = if ($evidenceCount -ge $expectedResults[$component].minEvidence) { "✅" } else { "⚠️" }
        }

        Write-Host "  $($results.Evidence[-1].Status) $component`: $evidenceCount match(es)" -ForegroundColor $(if ($evidenceCount -ge $expectedResults[$component].minEvidence) { "Green" } else { "Yellow" })
    }

    # Calculate metrics
    if ($confidences.Count -gt 0) {
        $results.Metrics.AvgConfidence = ($confidences | Measure-Object -Average).Average
    }
    if ($results.Metrics.TotalChecks -gt 0) {
        $results.Metrics.SuccessRate = $results.Metrics.Passed / $results.Metrics.TotalChecks
    }

    # Generate detailed report
    $detailedReport = Generate-DetailedReport -Results $results
    Write-Host $detailedReport

    # Summary
    Write-Host ""
    if ($results.Metrics.SuccessRate -ge 0.8) {
        Write-Host "✅ VALIDATION PASSED" -ForegroundColor Green
        Write-Host "Documentation quality meets expectations!" -ForegroundColor Green
    } elseif ($results.Metrics.SuccessRate -ge 0.6) {
        Write-Host "⚠️  VALIDATION PASSED WITH WARNINGS" -ForegroundColor Yellow
        Write-Host "Some quality issues detected, review recommended." -ForegroundColor Yellow
    } else {
        Write-Host "❌ VALIDATION FAILED" -ForegroundColor Red
        Write-Host "Significant quality issues detected!" -ForegroundColor Red
    }
    Write-Host ""

    # Exit code based on success rate
    if ($results.Metrics.SuccessRate -lt 0.6) {
        exit 1
    }

} catch {
    Write-Host ""
    Write-Host "❌ Validation failed with error: $_" -ForegroundColor Red
    Write-Host $_.ScriptStackTrace -ForegroundColor Red
    exit 1
}

