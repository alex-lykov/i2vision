<#
.SYNOPSIS
Load a target project and discover its architecture via orchestrator + contract.

.DESCRIPTION
1. Validates target project exists.
2. Runs PS1 heuristics pre-scan (fast, free).
3. Invokes the orchestrator harness (all MCP/strategy phases) against the target.
4. Reads the Kotlin contract result written to .vision-ai/contracts/results/architecture.yaml.
5. Falls back to PS1 heuristics if contract result is not available.
6. Prints a final ARCHITECTURE DETECTION report.

Usage:
  .\scripts\tools\test-orchestrator-box-tox.ps1 [-TargetProjectPath <path>] [-PurgeBeforeTest] [-DryRun]
#>

[CmdletBinding()]
param(
    [string]$TargetProjectPath = ".",
    [switch]$PurgeBeforeTest,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

$targetProject = $TargetProjectPath
$scriptDir     = Split-Path -Parent $MyInvocation.MyCommand.Path
$harness       = Join-Path $scriptDir 'test-module-discovery.ps1'

# --- Pre-flight ---------------------------------------------------------------

if (-not (Test-Path $targetProject)) {
    Write-Host "ERROR: Target project not found: $targetProject" -ForegroundColor Red
    exit 2
}
 $targetProject = (Resolve-Path $targetProject).Path
if (-not (Test-Path $harness)) {
    Write-Host "ERROR: Orchestrator harness not found: $harness" -ForegroundColor Red
    exit 3
}

# --- PS1 Architecture Detection -----------------------------------------------
# Mirrors VisionStructureContract.analyzeFromCode() but runs entirely in PowerShell.
# Used for pre-scan output AND as fallback when the Kotlin contract result is absent.

function Invoke-PsArchitectureDetection {
    param([string]$ProjectPath)

    $evidence   = [System.Collections.Generic.List[string]]::new()
    $frameworks = [System.Collections.Generic.List[string]]::new()

    # -- Count source files (skip build/cache dirs) ---------------------------
    $files = Get-ChildItem -Path $ProjectPath -Recurse -File -ErrorAction SilentlyContinue `
             | Where-Object { $_.FullName -notmatch '[\\/](\.git|build|\.gradle|node_modules|\.idea)[\\/]' } `
             | Select-Object -First 3000

    $langCounts = @{ kotlin=0; java=0; typescript=0; javascript=0; go=0; rust=0; python=0; swift=0 }
    foreach ($f in $files) {
        switch ($f.Extension) {
            '.kt'  { $langCounts['kotlin']++ }
            '.java'{ $langCounts['java']++ }
            '.ts'  { $langCounts['typescript']++ }
            '.tsx' { $langCounts['typescript']++ }
            '.js'  { $langCounts['javascript']++ }
            '.jsx' { $langCounts['javascript']++ }
            '.go'  { $langCounts['go']++ }
            '.rs'  { $langCounts['rust']++ }
            '.py'  { $langCounts['python']++ }
            '.swift'{ $langCounts['swift']++ }
        }
    }

    $sortedLangs  = $langCounts.GetEnumerator() | Where-Object { $_.Value -gt 0 } | Sort-Object Value -Descending
    $primaryEntry = $sortedLangs | Select-Object -First 1
    $primaryLang  = if ($primaryEntry) { $primaryEntry.Name } else { 'unknown' }
    $primaryCount = if ($primaryEntry) { $primaryEntry.Value } else { 0 }

    if ($primaryLang -ne 'unknown') { [void]$evidence.Add("$primaryLang source ($primaryCount files)") }
    $sortedLangs | Select-Object -Skip 1 -First 3 | ForEach-Object {
        [void]$evidence.Add("$($_.Name) source ($($_.Value) files)")
    }

    # -- Build system ----------------------------------------------------------
    $buildSystem = 'unknown'
    if ((Test-Path (Join-Path $ProjectPath 'build.gradle.kts')) -or
        (Test-Path (Join-Path $ProjectPath 'settings.gradle.kts'))) {
        $buildSystem = 'gradle'; [void]$evidence.Add('gradle_kts')
    } elseif (Test-Path (Join-Path $ProjectPath 'build.gradle')) {
        $buildSystem = 'gradle'; [void]$evidence.Add('gradle')
    } elseif (Test-Path (Join-Path $ProjectPath 'pom.xml')) {
        $buildSystem = 'maven'; [void]$evidence.Add('maven')
    } elseif (Test-Path (Join-Path $ProjectPath 'package.json')) {
        $buildSystem = 'npm'; [void]$evidence.Add('npm')
    } elseif (Test-Path (Join-Path $ProjectPath 'Cargo.toml')) {
        $buildSystem = 'cargo'; [void]$evidence.Add('cargo')
    } elseif (Test-Path (Join-Path $ProjectPath 'go.mod')) {
        $buildSystem = 'go'; [void]$evidence.Add('go_mod')
    }

    # -- Architecture style ----------------------------------------------------
    $hasAndroid   = ($files | Where-Object Name -eq 'AndroidManifest.xml' | Select-Object -First 1) -ne $null
    $isMultiMod   = (Test-Path (Join-Path $ProjectPath 'settings.gradle.kts')) -or
                    (Test-Path (Join-Path $ProjectPath 'settings.gradle'))

    $allPaths     = $files | ForEach-Object { $_.FullName }
    $hasDomain    = ($allPaths | Where-Object { $_ -match '[\\/]domain[\\/]' }    | Select-Object -First 1) -ne $null
    $hasAppLayer  = ($allPaths | Where-Object { $_ -match '[\\/]application[\\/]' }| Select-Object -First 1) -ne $null
    $hasInfra     = ($allPaths | Where-Object { $_ -match '[\\/]infrastructure[\\/]' }| Select-Object -First 1) -ne $null
    $hasController= ($allPaths | Where-Object { $_ -match '[\\/]controller[\\/]' } | Select-Object -First 1) -ne $null
    $hasService   = ($allPaths | Where-Object { $_ -match '[\\/]service[\\/]' }   | Select-Object -First 1) -ne $null
    $hasRepository= ($allPaths | Where-Object { $_ -match '[\\/]repository[\\/]' }| Select-Object -First 1) -ne $null

    $style = 'unknown'
    if ($hasAndroid)   { $style = 'android';  [void]$evidence.Add('android_manifest') }
    elseif ($hasDomain -and $hasAppLayer -and $hasInfra) { $style = 'clean'; [void]$evidence.Add('clean_architecture') }
    elseif ($hasController -and $hasService -and $hasRepository) { $style = 'layered'; [void]$evidence.Add('layered_architecture') }
    elseif ($isMultiMod) { $style = 'modular'; [void]$evidence.Add('multi_module') }

    # -- Frameworks ------------------------------------------------------------
    if ($hasAndroid) { [void]$frameworks.Add('android') }
    if (Test-Path (Join-Path $ProjectPath 'tsconfig.json')) { [void]$frameworks.Add('typescript'); [void]$evidence.Add('tsconfig_json') }

    # -- Template suggestion ---------------------------------------------------
    $suggestedTemplate = switch ($true) {
        ($style -eq 'android')                       { 'android-app'; break }
        ($primaryLang -eq 'kotlin' -and $style -eq 'modular') { 'vision-ai-project'; break }
        ($primaryLang -eq 'kotlin')                  { 'kotlin-backend'; break }
        ($primaryLang -eq 'typescript')              { 'realtime-dashboard-fullstack'; break }
        default                                      { 'default' }
    }

    # -- Confidence: more indicators = higher confidence -----------------------
    $confidence = [math]::Min(0.95, 0.35 + ($evidence.Count * 0.12))

    return [PSCustomObject]@{
        PrimaryLanguage    = $primaryLang
        PrimaryCount       = $primaryCount
        BuildSystem        = $buildSystem
        Style              = $style
        Frameworks         = ($frameworks -join ', ')
        SuggestedTemplate  = $suggestedTemplate
        Confidence         = $confidence
        Evidence           = $evidence.ToArray()
        Source             = 'ps1-heuristics'
    }
}

function Show-ArchitectureReport {
    param([PSCustomObject]$Result, [string]$ContractPath = '')

    Write-Host ''
    Write-Host '============================================================' -ForegroundColor Cyan
    Write-Host '  ARCHITECTURE DETECTION  -  FINAL RESULT' -ForegroundColor Cyan
    Write-Host '============================================================' -ForegroundColor Cyan

    $src = if ($ContractPath -and (Test-Path $ContractPath)) { "contract result  ($ContractPath)" } else { $Result.Source }
    Write-Host ("  Source:           {0}" -f $src)              -ForegroundColor Gray
    Write-Host ("  Style:            {0}" -f $Result.Style)     -ForegroundColor Yellow
    Write-Host ("  Primary language: {0} ({1} files)" -f $Result.PrimaryLanguage, $Result.PrimaryCount) -ForegroundColor Yellow
    Write-Host ("  Build system:     {0}" -f $Result.BuildSystem) -ForegroundColor Yellow
    Write-Host ("  Frameworks:       {0}" -f $(if ($Result.Frameworks) { $Result.Frameworks } else { 'none' })) -ForegroundColor Yellow
    Write-Host ("  Suggested template: {0}" -f $Result.SuggestedTemplate) -ForegroundColor Cyan
    Write-Host ("  Confidence:       {0:F2}" -f $Result.Confidence) -ForegroundColor $(if ($Result.Confidence -ge 0.8) { 'Green' } elseif ($Result.Confidence -ge 0.5) { 'Yellow' } else { 'Red' })
    Write-Host '  Evidence:'         -ForegroundColor Gray
    $Result.Evidence | ForEach-Object { Write-Host "    - $_" -ForegroundColor DarkGray }
    Write-Host '============================================================' -ForegroundColor Cyan
}

# --- Step 1: Pre-scan (always runs, fast) ------------------------------------

Write-Host ''
Write-Host '[Box-Tox] Target project: ' -NoNewline -ForegroundColor Cyan
Write-Host $targetProject -ForegroundColor White
Write-Host '[Box-Tox] Running PS1 pre-scan...' -ForegroundColor DarkCyan

$preScan = Invoke-PsArchitectureDetection -ProjectPath $targetProject

Write-Host ("  Detected: {0}/{1}  build={2}  confidence={3:F2}" -f `
    $preScan.Style, $preScan.PrimaryLanguage, $preScan.BuildSystem, $preScan.Confidence) -ForegroundColor DarkGray

# --- Step 2: Harness (skip in DryRun) ----------------------------------------

if ($DryRun) {
    Write-Host '[Box-Tox] DryRun: skipping harness invocation.' -ForegroundColor Yellow
    Show-ArchitectureReport -Result $preScan
    exit 0
}

Write-Host '[Box-Tox] Invoking orchestrator harness...' -ForegroundColor Green

$argList  = @('-ProjectPath', $targetProject, '-UseImprovedStrategy')
if ($PurgeBeforeTest) { $argList += '-PurgeBeforeTest' }

& $harness @argList
$harnessExit = $LASTEXITCODE

# --- Step 3: Read contract result (written by VisionStructureContract) --------
# Canonical path: .semantic-cache/contracts/structure/architecture-detection.yaml
# Legacy paths checked as fallbacks for backward compatibility.

$contractResultPath = Join-Path $targetProject '.semantic-cache\contracts\structure\architecture-detection.yaml'
$legacyPaths = @(
    (Join-Path $targetProject '.vision-ai\structure\artifacts\architecture-detection.yaml'),
    (Join-Path $targetProject '.vision-ai\contracts\results\architecture.yaml')
)

# Use first existing legacy path if canonical doesn't exist yet
if (-not (Test-Path $contractResultPath)) {
    $found = $legacyPaths | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($found) { $contractResultPath = $found }
}

$finalResult = $preScan   # default to PS1 heuristics

if (Test-Path $contractResultPath) {
    $yaml           = Get-Content $contractResultPath -Raw
    $contractStyle  = if ($yaml -match 'style:\s*(\S+)')           { $matches[1] } else { $preScan.Style }
    $contractLang   = if ($yaml -match 'primary_language:\s*(\S+)')  { $matches[1] } else { $preScan.PrimaryLanguage }
    $contractBuild  = if ($yaml -match 'build_system:\s*(\S+)')      { $matches[1] } else { $preScan.BuildSystem }
    $contractConf   = if ($yaml -match 'confidence:\s*([0-9.]+)')     { [double]$matches[1] } else { $preScan.Confidence }
    $contractTpl    = if ($yaml -match 'suggested_template:\s*(\S+)') { $matches[1] } else { $preScan.SuggestedTemplate }

    # Parse evidence lines (  - "xxx")
    $contractEvidence = [regex]::Matches($yaml, '- "([^"]+)"') | ForEach-Object { $_.Groups[1].Value }

    $finalResult = [PSCustomObject]@{
        PrimaryLanguage   = $contractLang
        PrimaryCount      = $preScan.PrimaryCount
        BuildSystem       = $contractBuild
        Style             = $contractStyle
        Frameworks        = $preScan.Frameworks
        SuggestedTemplate = $contractTpl
        Confidence        = $contractConf
        Evidence          = if ($contractEvidence) { $contractEvidence } else { $preScan.Evidence }
        Source            = 'kotlin-contract'
    }
}

# --- Step 4: Final report -----------------------------------------------------

Show-ArchitectureReport -Result $finalResult -ContractPath $contractResultPath

Write-Host ("Harness exit: $harnessExit") -ForegroundColor Gray
exit $harnessExit





