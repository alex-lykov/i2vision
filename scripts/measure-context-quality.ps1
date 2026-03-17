<#
.SYNOPSIS
Measure instant context quality metrics from semantic cache artifacts.

.DESCRIPTION
Analyzes the .semantic-cache directory to measure context quality across VSLFC layers:
- Flow quality (meaningful vs noise)
- Vision quality (requirements vs test specs)
- Entry point accuracy
- Context verbosity
- Layer linking/traceability

.PARAMETER Baseline
Create a baseline measurement for future comparison.

.PARAMETER Compare
Compare current state against a baseline measurement file.

.PARAMETER Cluster
Analyze a specific cluster. If empty, analyzes all clusters.

.PARAMETER OutputFormat
Output format: text (default), json, or csv.

.EXAMPLE
# Create baseline before improvements
.\scripts\measure-context-quality.ps1 -Baseline

# Compare after improvements
.\scripts\measure-context-quality.ps1 -Compare baseline-20260406.json

# Analyze specific cluster
.\scripts\measure-context-quality.ps1 -Cluster core/orchestrator
#>

param(
    [switch]$Baseline,
    [string]$Compare = '',
    [string]$Cluster = '',
    [string]$OutputFormat = 'text'
)

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (Resolve-Path (Join-Path $scriptDir '..')).Path
$semanticCache = Join-Path $repoRoot '.semantic-cache'

if (-not (Test-Path $semanticCache)) {
    Write-Error ".semantic-cache not found. Run discovery first: .\gradlew.bat :launcher:run --args=\"cli.StrategyRunner --strategyFile=.vision-ai/config/strategies/vlsfc-adaptive-depth.yaml --depth=standard --project=.\""
    exit 1
}

# Metric Collection Functions

function Measure-FlowQuality {
    param([string]$ClusterPath)

    $flowDir = Join-Path $ClusterPath 'flow'
    if (-not (Test-Path $flowDir)) {
        return @{ files = 0; meaningful = 0; noisy = 0; quality_score = 0 }
    }

    $sdFiles = Get-ChildItem $flowDir -Filter '*.sd' -ErrorAction SilentlyContinue
    if (-not $sdFiles) {
        return @{ files = 0; meaningful = 0; noisy = 0; quality_score = 0 }
    }

    $total = $sdFiles.Count
    $meaningful = 0
    $noisy = 0

    foreach ($file in $sdFiles) {
        $content = Get-Content $file.FullName -Raw

        # Check for noise indicators
        $hasControlFlowParticipants = $content -match 'participant\s+"(if|when|for|while|else)"'
        $hasGetterCalls = $content -match '->(get\w+)\('
        $hasBusinessLogic = $content -match '(Service|Repository|Agent|Controller|Handler)'
        $hasMultipleParticipants = ($content -split 'participant').Count -gt 5

        if ($hasControlFlowParticipants -or $hasGetterCalls -or -not $hasMultipleParticipants) {
            $noisy++
        } elseif ($hasBusinessLogic) {
            $meaningful++
        }
    }

    $qualityScore = if ($total -gt 0) { [math]::Round(($meaningful / $total) * 100, 2) } else { 0 }

    return @{
        files = $total
        meaningful = $meaningful
        noisy = $noisy
        quality_score = $qualityScore
    }
}

function Measure-VisionQuality {
    param([string]$ClusterPath)

    $visionDir = Join-Path $ClusterPath 'vision'
    $reqFile = Join-Path $visionDir 'requirements.yaml'

    if (-not (Test-Path $reqFile)) {
        return @{ total = 0; business = 0; tests = 0; quality_score = 0 }
    }

    $content = Get-Content $reqFile -Raw
    $requirements = ($content | Select-String -Pattern '- description:' -AllMatches).Matches.Count

    $testSpecs = ($content | Select-String -Pattern "description:\s*['\"]?Test:" -AllMatches).Matches.Count
    $business = $requirements - $testSpecs

    $qualityScore = if ($requirements -gt 0) {
        [math]::Round(($business / $requirements) * 100, 2)
    } else { 0 }

    return @{
        total = $requirements
        business = $business
        tests = $testSpecs
        quality_score = $qualityScore
    }
}

function Measure-EntryPointAccuracy {
    param([string]$ClusterPath)

    $codeDir = Join-Path $ClusterPath 'code'
    $symbolsFile = Join-Path $codeDir 'symbols.yaml'

    if (-not (Test-Path $symbolsFile)) {
        return @{ total_symbols = 0; entry_points = 0; hardcoded_patterns = 0; accuracy_score = 0 }
    }

    $content = Get-Content $symbolsFile -Raw

    # Count symbols
    $totalSymbols = ($content | Select-String -Pattern '^- name:' -AllMatches).Matches.Count

    # Detect hardcoded patterns (Orchestrator, Agent suffixes)
    $orchestratorPattern = ($content | Select-String -Pattern "name:\s+\w*Orchestrator" -AllMatches).Matches.Count
    $agentPattern = ($content | Select-String -Pattern "name:\s+\w*Agent\b" -AllMatches).Matches.Count
    $controllerPattern = ($content | Select-String -Pattern "name:\s+\w*Controller" -AllMatches).Matches.Count

    $hardcoded = $orchestratorPattern + $agentPattern + $controllerPattern

    # Estimate entry points (main, @Service, etc.)
    $entryPoints = ($content | Select-String -Pattern 'kind:\s+(class|object)' -AllMatches).Matches.Count

    # If hardcoded patterns dominate, accuracy is lower
    $accuracyScore = if ($entryPoints -gt 0) {
        $hardcodedRatio = $hardcoded / $entryPoints
        [math]::Round((1 - [math]::Min($hardcodedRatio, 0.5)) * 100, 2)
    } else { 0 }

    return @{
        total_symbols = $totalSymbols
        entry_points = $entryPoints
        hardcoded_patterns = $hardcoded
        accuracy_score = $accuracyScore
    }
}

function Measure-ContextVerbosity {
    param([string]$ClusterPath)

    $allFiles = Get-ChildItem $ClusterPath -Recurse -File -Include '*.yaml','*.sd' -ErrorAction SilentlyContinue
    if (-not $allFiles) {
        return @{ total_chars = 0; total_lines = 0; avg_line_length = 0; verbosity_score = 0 }
    }

    $totalChars = 0
    $totalLines = 0

    foreach ($file in $allFiles) {
        $content = Get-Content $file.FullName -Raw
        $totalChars += $content.Length
        $totalLines += ($content -split "`n").Count
    }

    $avgLineLength = if ($totalLines -gt 0) { [math]::Round($totalChars / $totalLines, 2) } else { 0 }

    # Verbosity score: lower is better (target: 50-80 chars/line)
    # 100 = optimal (50-80), 0 = very verbose (>200)
    $verbosityScore = if ($avgLineLength -le 80) { 100 }
        elseif ($avgLineLength -le 120) { 75 }
        elseif ($avgLineLength -le 160) { 50 }
        elseif ($avgLineLength -le 200) { 25 }
        else { 0 }

    return @{
        total_chars = $totalChars
        total_lines = $totalLines
        avg_line_length = $avgLineLength
        verbosity_score = $verbosityScore
    }
}

function Measure-LayerLinking {
    param([string]$ClusterPath)

    # Check for traceability between layers
    $hasTraceability = Test-Path (Join-Path $ClusterPath 'traceability.yaml')
    $linksFile = Join-Path $repoRoot '.semantic-cache' 'links.yaml'
    $hasLinks = Test-Path $linksFile

    $linkCount = 0
    if ($hasLinks) {
        $linkContent = Get-Content $linksFile -Raw
        $linkCount = ($linkContent | Select-String -Pattern '^\s+-\s+from:' -AllMatches).Matches.Count
    }

    # Layer presence
    $layers = @('code', 'flow', 'logic', 'structure', 'vision')
    $presentLayers = $layers | Where-Object { Test-Path (Join-Path $ClusterPath $_) } | Measure-Object | Select-Object -ExpandProperty Count

    # Linking score: based on links and layer coverage
    $layerCoverage = ($presentLayers / $layers.Count) * 100
    $linkingScore = if ($hasTraceability) { 100 }
        elseif ($linkCount -gt 50) { 60 }
        elseif ($linkCount -gt 20) { 40 }
        elseif ($linkCount -gt 0) { 20 }
        else { 0 }

    return @{
        has_traceability = $hasTraceability
        link_count = $linkCount
        present_layers = $presentLayers
        layer_coverage = [math]::Round($layerCoverage, 2)
        linking_score = $linkingScore
    }
}

function Measure-ClusterQuality {
    param([string]$ClusterPath, [string]$ClusterName)

    $flow = Measure-FlowQuality -ClusterPath $ClusterPath
    $vision = Measure-VisionQuality -ClusterPath $ClusterPath
    $entryPoints = Measure-EntryPointAccuracy -ClusterPath $ClusterPath
    $verbosity = Measure-ContextVerbosity -ClusterPath $ClusterPath
    $linking = Measure-LayerLinking -ClusterPath $ClusterPath

    # Overall quality score (weighted average)
    $overallScore = [math]::Round((
        $flow.quality_score * 0.2 +
        $vision.quality_score * 0.25 +
        $entryPoints.accuracy_score * 0.2 +
        $verbosity.verbosity_score * 0.15 +
        $linking.linking_score * 0.2
    ), 2)

    return @{
        cluster = $ClusterName
        overall_score = $overallScore
        flow = $flow
        vision = $vision
        entry_points = $entryPoints
        verbosity = $verbosity
        linking = $linking
    }
}

# Main Execution

Write-Host "Measuring Context Quality..." -ForegroundColor Cyan

# Determine clusters to analyze
$clustersToAnalyze = @()
if ($Cluster) {
    $clusterPath = Join-Path $semanticCache $Cluster
    if (Test-Path $clusterPath) {
        $clustersToAnalyze += @{ Name = $Cluster; Path = $clusterPath }
    } else {
        Write-Error "Cluster not found: $Cluster"
        exit 1
    }
} else {
    # Analyze all clusters
    Get-ChildItem $semanticCache -Directory | ForEach-Object {
        if ($_.Name -ne '.tools') {
            $clustersToAnalyze += @{ Name = $_.Name; Path = $_.FullName }
        }
        # Check for nested clusters (core/*)
        if ($_.Name -eq 'core') {
            Get-ChildItem $_.FullName -Directory | ForEach-Object {
                $clustersToAnalyze += @{ Name = "core/$($_.Name)"; Path = $_.FullName }
            }
        }
    }
}

Write-Host "Analyzing $($clustersToAnalyze.Count) cluster(s)..." -ForegroundColor Yellow

$results = @()
foreach ($clusterInfo in $clustersToAnalyze) {
    $result = Measure-ClusterQuality -ClusterPath $clusterInfo.Path -ClusterName $clusterInfo.Name
    $results += $result
}

# Calculate Aggregate Metrics

$aggregateMetrics = @{
    total_clusters = $results.Count
    avg_overall_score = [math]::Round(($results | ForEach-Object { $_.overall_score } | Measure-Object -Average).Average, 2)
    avg_flow_quality = [math]::Round(($results | ForEach-Object { $_.flow.quality_score } | Measure-Object -Average).Average, 2)
    avg_vision_quality = [math]::Round(($results | ForEach-Object { $_.vision.quality_score } | Measure-Object -Average).Average, 2)
    avg_entry_point_accuracy = [math]::Round(($results | ForEach-Object { $_.entry_points.accuracy_score } | Measure-Object -Average).Average, 2)
    avg_verbosity_score = [math]::Round(($results | ForEach-Object { $_.verbosity.verbosity_score } | Measure-Object -Average).Average, 2)
    avg_linking_score = [math]::Round(($results | ForEach-Object { $_.linking.linking_score } | Measure-Object -Average).Average, 2)
    total_flows = ($results | ForEach-Object { $_.flow.files } | Measure-Object -Sum).Sum
    meaningful_flows = ($results | ForEach-Object { $_.flow.meaningful } | Measure-Object -Sum).Sum
    total_requirements = ($results | ForEach-Object { $_.vision.total } | Measure-Object -Sum).Sum
    business_requirements = ($results | ForEach-Object { $_.vision.business } | Measure-Object -Sum).Sum
    test_specs = ($results | ForEach-Object { $_.vision.tests } | Measure-Object -Sum).Sum
    timestamp = (Get-Date).ToString("yyyy-MM-dd HH:mm:ss")
}

# Output Results

if ($OutputFormat -eq 'json') {
    $output = @{
        aggregate = $aggregateMetrics
        clusters = $results
    } | ConvertTo-Json -Depth 10
    Write-Output $output

    if ($Baseline) {
        $baselineFile = Join-Path $repoRoot "baseline-$(Get-Date -Format 'yyyyMMdd-HHmmss').json"
        $output | Out-File $baselineFile -Encoding UTF8
        Write-Host "`nBaseline saved: $baselineFile" -ForegroundColor Green
    }
} elseif ($OutputFormat -eq 'csv') {
    $results | ForEach-Object {
        [PSCustomObject]@{
            Cluster = $_.cluster
            OverallScore = $_.overall_score
            FlowQuality = $_.flow.quality_score
            VisionQuality = $_.vision.quality_score
            EntryPointAccuracy = $_.entry_points.accuracy_score
            VerbosityScore = $_.verbosity.verbosity_score
            LinkingScore = $_.linking.linking_score
        }
    } | Export-Csv -Path "context-quality.csv" -NoTypeInformation
    Write-Host "`nCSV saved: context-quality.csv" -ForegroundColor Green
} else {
    # Text output
    $separator = "-" * 70
    Write-Host ""
    Write-Host $separator -ForegroundColor Cyan
    Write-Host "INSTANT CONTEXT QUALITY REPORT" -ForegroundColor Cyan
    Write-Host $separator -ForegroundColor Cyan
    Write-Host "Timestamp: $($aggregateMetrics.timestamp)"
    Write-Host "Clusters Analyzed: $($aggregateMetrics.total_clusters)"
    Write-Host ""

    Write-Host 'AGGREGATE SCORES (0-100 scale higher is better)' -ForegroundColor Yellow
    Write-Host "  Overall Quality:        $($aggregateMetrics.avg_overall_score)%" -ForegroundColor $(if ($aggregateMetrics.avg_overall_score -ge 70) { "Green" } elseif ($aggregateMetrics.avg_overall_score -ge 40) { "Yellow" } else { "Red" })
    Write-Host "  Flow Quality:           $($aggregateMetrics.avg_flow_quality)%" -ForegroundColor $(if ($aggregateMetrics.avg_flow_quality -ge 60) { "Green" } elseif ($aggregateMetrics.avg_flow_quality -ge 30) { "Yellow" } else { "Red" })
    Write-Host "  Vision Quality:         $($aggregateMetrics.avg_vision_quality)%" -ForegroundColor $(if ($aggregateMetrics.avg_vision_quality -ge 70) { "Green" } elseif ($aggregateMetrics.avg_vision_quality -ge 40) { "Yellow" } else { "Red" })
    Write-Host "  Entry Point Accuracy:   $($aggregateMetrics.avg_entry_point_accuracy)%" -ForegroundColor $(if ($aggregateMetrics.avg_entry_point_accuracy -ge 80) { "Green" } elseif ($aggregateMetrics.avg_entry_point_accuracy -ge 50) { "Yellow" } else { "Red" })
    Write-Host "  Verbosity:              $($aggregateMetrics.avg_verbosity_score)%" -ForegroundColor $(if ($aggregateMetrics.avg_verbosity_score -ge 70) { "Green" } elseif ($aggregateMetrics.avg_verbosity_score -ge 40) { "Yellow" } else { "Red" })
    Write-Host "  Layer Linking:          $($aggregateMetrics.avg_linking_score)%" -ForegroundColor $(if ($aggregateMetrics.avg_linking_score -ge 70) { "Green" } elseif ($aggregateMetrics.avg_linking_score -ge 40) { "Yellow" } else { "Red" })
    Write-Host ""

    Write-Host "DETAILED METRICS" -ForegroundColor Yellow
    Write-Host "  Flows:                  $($aggregateMetrics.total_flows) total, $($aggregateMetrics.meaningful_flows) meaningful"
    Write-Host "  Requirements:           $($aggregateMetrics.total_requirements) total, $($aggregateMetrics.business_requirements) business, $($aggregateMetrics.test_specs) test specs"
    Write-Host ""

    Write-Host "TOP 5 CLUSTERS BY OVERALL QUALITY" -ForegroundColor Yellow
    $results | Sort-Object -Property overall_score -Descending | Select-Object -First 5 | ForEach-Object {
        Write-Host "  $($_.cluster): $($_.overall_score)%" -ForegroundColor $(if ($_.overall_score -ge 70) { "Green" } elseif ($_.overall_score -ge 40) { "Yellow" } else { "Red" })
    }
    Write-Host ""

    Write-Host "BOTTOM 5 CLUSTERS (Need Improvement)" -ForegroundColor Yellow
    $results | Sort-Object -Property overall_score | Select-Object -First 5 | ForEach-Object {
        Write-Host "  $($_.cluster): $($_.overall_score)% - Issues:" -ForegroundColor Red
        if ($_.flow.quality_score -lt 30) { Write-Host "    - Low flow quality $($_.flow.quality_score)%" }
        if ($_.vision.quality_score -lt 40) { Write-Host "    - Low vision quality $($_.vision.quality_score)%" }
        if ($_.entry_points.accuracy_score -lt 50) { Write-Host "    - Low entry point accuracy $($_.entry_points.accuracy_score)%" }
        if ($_.linking.linking_score -lt 30) { Write-Host "    - Missing layer linking $($_.linking.linking_score)%" }
    }
    Write-Host ""

    if ($Baseline) {
        $baselineFile = Join-Path $repoRoot "baseline-$(Get-Date -Format 'yyyyMMdd-HHmmss').json"
        @{
            aggregate = $aggregateMetrics
            clusters = $results
        } | ConvertTo-Json -Depth 10 | Out-File $baselineFile -Encoding UTF8
        Write-Host "[OK] Baseline saved: $baselineFile" -ForegroundColor Green
    }

    if ($Compare) {
        $baselinePath = if (Test-Path $Compare) { $Compare } else { Join-Path $repoRoot $Compare }
        if (Test-Path $baselinePath) {
            $baseline = Get-Content $baselinePath | ConvertFrom-Json
            Write-Host "`nCOMPARISON TO BASELINE" -ForegroundColor Yellow
            $overallDiff = $aggregateMetrics.avg_overall_score - $baseline.aggregate.avg_overall_score
            $flowDiff = $aggregateMetrics.avg_flow_quality - $baseline.aggregate.avg_flow_quality
            $visionDiff = $aggregateMetrics.avg_vision_quality - $baseline.aggregate.avg_vision_quality
            $epDiff = $aggregateMetrics.avg_entry_point_accuracy - $baseline.aggregate.avg_entry_point_accuracy
            $verbosityDiff = $aggregateMetrics.avg_verbosity_score - $baseline.aggregate.avg_verbosity_score
            $linkingDiff = $aggregateMetrics.avg_linking_score - $baseline.aggregate.avg_linking_score

            Write-Host "  Overall Quality:        $($aggregateMetrics.avg_overall_score)% ($overallDiff)"
            Write-Host "  Flow Quality:           $($aggregateMetrics.avg_flow_quality)% ($flowDiff)"
            Write-Host "  Vision Quality:         $($aggregateMetrics.avg_vision_quality)% ($visionDiff)"
            Write-Host "  Entry Point Accuracy:   $($aggregateMetrics.avg_entry_point_accuracy)% ($epDiff)"
            Write-Host "  Verbosity:              $($aggregateMetrics.avg_verbosity_score)% ($verbosityDiff)"
            Write-Host "  Layer Linking:          $($aggregateMetrics.avg_linking_score)% ($linkingDiff)"
        } else {
            Write-Warning "Baseline file not found: $baselinePath"
        }
    }
}

$separator = "-" * 70
Write-Host ''
Write-Host $separator -ForegroundColor Cyan
Write-Host 'Context quality measurement complete' -ForegroundColor Green
Write-Host $separator -ForegroundColor Cyan












