<#
Simplified Full Project Discovery Test (PowerShell 5.1 compatible)

This script performs a mandatory purge of `.semantic-cache`, runs an architecture
detection heuristic, then executes a project-wide discovery (ai_discover) and
rebuilds cross-module links via `validate-links`.

Usage:
  powershell -ExecutionPolicy Bypass -File .\scripts\tools\test-full-discovery.ps1
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
$visionAiPath = Join-Path $repoRoot '.vision-ai'

function Run-Gradle($args, $timeoutSeconds = 300) {
    $cmd = "`"$gradlePath`" $args --no-daemon --console=plain -q"
    Write-Host "Running: $cmd" -ForegroundColor DarkGray
    $out = cmd /c $cmd 2>&1
    return ,$out
}

# PHASE 0: Mandatory purge
if (Test-Path $semanticCacheRoot) {
    Write-Host "Purging .semantic-cache/ ..." -ForegroundColor Red
    Remove-Item -Path (Join-Path $semanticCacheRoot '*') -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Running project-wide discovery (ai_discover) ..." -ForegroundColor Cyan
$discoverArgs = ":configurable-agent:run --args=`"discover --module project --project `"$repoRoot`" --mode full`""
$discoverOut = Run-Gradle $discoverArgs 1200
Write-Host $discoverOut -ForegroundColor Gray

Write-Host "Running validate-links (project_wide) ..." -ForegroundColor Cyan
$validateArgs = ":configurable-agent:run --args=`"validate-links --module project --project `"$repoRoot`" --mode project_wide`""
$validateOut = Run-Gradle $validateArgs 300

$linksPath = Join-Path $semanticCacheRoot 'links.yaml'
if (Test-Path $linksPath) {
    Write-Host "\nGenerated links registry: $linksPath" -ForegroundColor Green
    Get-Content $linksPath -TotalCount 120 | ForEach-Object { Write-Host $_ }
} else {
    Write-Host "No links.yaml produced." -ForegroundColor Red
}

Write-Host "Full discovery completed." -ForegroundColor Cyan
exit 0

    if ($raw -match '(?ms)post_all_modules:\s*\r?\n(?:\s+.+\r?\n)*?\s+-\s+name:\s*mcp_validate_links\s*\r?\n(?:\s+.+\r?\n)*?\s+mode:\s*(\w+)') {
        $script:LinkPhaseMode = $Matches[1]
<#
.SYNOPSIS
Full Project Discovery Test - ANALYSIS Strategy v1.0

.DESCRIPTION
Implements strategy from .vision-ai/config/strategies/vlsfc-discovery-strategy.yaml
Category: ANALYSIS  (same taxonomy as vlsfc-precontext-strategy and orchestrator-module-analysis)

What makes this ANALYSIS strategy distinct from the others:
  - Starts from zero: mandatory purge of entire .semantic-cache before any work
  - Project-agnostic: detects build system + language before enumerating modules
  - Project-wide: covers every module, not a single pre-selected cluster
  - Direction: Code -> Vision (bottom-up, existing code reveals architecture)

Pipeline:
  Phase 0  Mandatory Full Purge       -- always wipes entire .semantic-cache; no skip flag
  Phase 1  Architecture Detection     -- Heuristic -> LLM Simulate -> Full LLM Simulate
  Phase 2  Module Enumeration         -- adapter driven by Phase 1 build_system result
  Phase 3  Per-Module Discovery       -- ai_discover (mode=full, direction=code_to_vision) per module
  Phase 4  Cross-Module Linking       -- validate layer contracts (V<->S, S<->L, L<->F, F<->C)
  Phase 5  Project-Wide Validation    -- reliability + coherence scoring
  Phase 6  Learning Recording         -- append metrics to full-discovery-history.yaml

.HOW TO RUN
  powershell -ExecutionPolicy Bypass -File .\scripts\tools\test-full-discovery.ps1
  powershell -ExecutionPolicy Bypass -File .\scripts\tools\test-full-discovery.ps1 -ProjectPath "."
  powershell -ExecutionPolicy Bypass -File .\scripts\tools\test-full-discovery.ps1 -Verbose
#>

[CmdletBinding()]
param(
    [string]$ProjectPath = ".",
    [string]$DependencyType = "uses"
)

$script:DependencyType = $DependencyType

$ErrorActionPreference = "Stop"

# ---------------------------------------------------------------------------
# Resolve paths
# ---------------------------------------------------------------------------
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path

if ($PSBoundParameters.ContainsKey('ProjectPath') -and $ProjectPath -and $ProjectPath -ne '.') {
    $repoRoot = (Resolve-Path $ProjectPath).Path
} elseif ($env:PROJECT_ROOT) {
    $repoRoot = (Resolve-Path $env:PROJECT_ROOT).Path
} else {
    $repoRoot = (Resolve-Path (Join-Path $scriptDir "..\..")).Path
}

$semanticCacheRoot = Join-Path $repoRoot ".semantic-cache"
$visionAiPath      = Join-Path $repoRoot ".vision-ai"
$strategyConfigPath = Join-Path $visionAiPath "config/strategies/vlsfc-full-discovery-strategy.yaml"

# ---------------------------------------------------------------------------
# Default runtime config (overridden from YAML if found)
# ---------------------------------------------------------------------------
$script:RuntimeConfig = @{
    Architecture = @{
        HeuristicTrustThreshold = 0.80
        LlmVerifyThreshold      = 0.50
    }
    LayerConfig = @{
        code      = @{ Required = $true;  MinQuality = 0.60 }
        flow      = @{ Required = $false; MinQuality = 0.40 }
        logic     = @{ Required = $true;  MinQuality = 0.50 }
        structure = @{ Required = $true;  MinQuality = 0.60 }
        vision    = @{ Required = $true;  MinQuality = 0.60 }
    }
    Reliability = @{
        Weights = @{
            moduleCoverage        = 0.35
            layerCompleteness     = 0.30
            linkHealth            = 0.20
            architectureConfidence= 0.15
        }
        StatusThresholds = @{ excellent = 0.90; good = 0.80; acceptable = 0.70; poor = 0.60 }
    }
    Validation = @{
        ModuleCoverageMin    = 0.90
        LayerCompletenessMin = 0.80
        Penalties = @{ high = 0.30; medium = 0.15 }
    }
}

$script:LifecycleThresholds = @{
    NO_DATA          = @{ ModulesMin = 0; Reliability = 0.0  }
    BOOTSTRAPPING    = @{ ModulesMin = 1; Reliability = 0.40 }
    DEVELOPING       = @{ ModulesMin = 3; Reliability = 0.65 }
    PRODUCTION_READY = @{ ModulesMin = 5; Reliability = 0.80 }
}

# Health snapshots -- appended at key pipeline points to track growth
$script:HealthSnapshots = [System.Collections.Generic.List[hashtable]]::new()

function Add-HealthSnapshot {
    param([string]$Phase, [double]$Reliability, [int]$Artifacts, [int]$Links)
    $script:HealthSnapshots.Add(@{
        Phase      = $Phase
        Reliability = [Math]::Round($Reliability, 3)
        Artifacts  = $Artifacts
        Links      = $Links
    })
}
    # Lesson 2: read link-tool mode from post_all_modules spec.
    # Line-by-line parse avoids (?s)+.+ catastrophic backtracking on large YAML.
if (Test-Path $strategyConfigPath) {
    $_rl_lines = $raw -split '\r?\n'; $_rl_inPAM = $false; $_rl_inTool = $false
    foreach ($_rl in $_rl_lines) {
        if ($_rl -match '^\s*post_all_modules:')                           { $_rl_inPAM  = $true;  continue }
        if ($_rl_inPAM  -and $_rl -match '^\s*-\s*name:\s*mcp_validate_links') { $_rl_inTool = $true;  continue }
        if ($_rl_inTool -and $_rl -match '^\s*mode:\s*(\w+)')             { $script:LinkPhaseMode = $Matches[1]; break }
        if ($_rl_inPAM  -and $_rl -match '^\S')                           { break }   # left the block
    if ($raw -match 'llm_verify_threshold:\s*([0-9.]+)')      { $script:RuntimeConfig.Architecture.LlmVerifyThreshold      = [double]$Matches[1] }
    Remove-Variable _rl_lines, _rl_inPAM, _rl_inTool, _rl -ErrorAction SilentlyContinue
    if ($raw -match '(?ms)module_coverage_min:\s*([0-9.]+)')  { $script:RuntimeConfig.Validation.ModuleCoverageMin         = [double]$Matches[1] }
    if ($raw -match '(?ms)layer_completeness_min:\s*([0-9.]+)') { $script:RuntimeConfig.Validation.LayerCompletenessMin    = [double]$Matches[1] }

    # Lesson 2: read link-tool mode from post_all_modules spec instead of hardcoding it.
    # Matches: name: mcp_validate_links ... mode: <value> within the post_all_modules block.
    # Matches: name: mcp_validate_links ... mode: <value> within the post_all_modules block.
    $script:LinkPhaseMode = "project_wide"  # safe fallback; project_wide is the only writer
    if ($raw -match '(?ms)post_all_modules:\s*\r?\n(?:\s+.+\r?\n)*?\s+-\s+name:\s*mcp_validate_links\s*\r?\n(?:\s+.+\r?\n)*?\s+mode:\s*(\w+)') {
        $script:LinkPhaseMode = $Matches[1]
    }

    Write-Host "Strategy config loaded from $strategyConfigPath" -ForegroundColor Green
} else {
    $script:LinkPhaseMode = "project_wide"
# ---------------------------------------------------------------------------
# HELPER: Run an arbitrary command line with a hard process-tree timeout.
# Writes the command to a temp .bat file (avoids PowerShell quoting issues),
# executes it via Start-Process, and on timeout calls `taskkill /F /T /PID`
# which kills the entire tree (cmd.exe + gradlew.bat + java.exe daemon).
# Returns the stdout lines, or $null on timeout.
# ---------------------------------------------------------------------------
function Invoke-GradleCommand {
    param([string]$CmdLine, [int]$TimeoutSeconds = 120)

    $tmpBat = [IO.Path]::GetTempFileName() -replace '\.tmp$', '.bat'
    $tmpOut = [IO.Path]::GetTempFileName()
    $tmpErr = [IO.Path]::GetTempFileName()
    try {
        Set-Content -Path $tmpBat -Value $CmdLine -Encoding ASCII
        $proc = Start-Process -FilePath "cmd.exe" `
                              -ArgumentList "/c `"$tmpBat`"" `
                              -RedirectStandardOutput $tmpOut `
                              -RedirectStandardError  $tmpErr `
                              -NoNewWindow -PassThru -ErrorAction Stop
        $finished = $proc.WaitForExit($TimeoutSeconds * 1000)
        if (-not $finished) {
            Write-Verbose "TIMEOUT ${TimeoutSeconds}s: $CmdLine"
            & taskkill /F /T /PID $proc.Id 2>&1 | Out-Null
            try { $proc.Kill() } catch { }
            return $null          # $null signals timeout to the caller
        }
        return @(Get-Content $tmpOut -ErrorAction SilentlyContinue)
    } finally {
        Remove-Item $tmpBat, $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue
    }
}
}

function Get-CachePath {
    # The cache path layout is intentionally fixed as `{projectRoot}/.semantic-cache/{cluster_id}`
    # to enforce standard VSLFC topology and prevent fragmentation.
    return Join-Path $semanticCacheRoot $clusterId
}

$McpCli = "java"

# ---------------------------------------------------------------------------
# HELPER: MCP tool invocation via gradlew CLI
# Simple, robust implementation: accept modulePath from Parameters and invoke gradlew
# ---------------------------------------------------------------------------
function Invoke-McpTool {
    param(
        [string]   $ToolName,
        [hashtable]$Parameters = @{},
        [string]   $Module = "project",
        [string]   $ModulePath = "",
        [int]      $TimeoutSeconds = 120
    )

    $cliCmd = switch ($ToolName) {
        "ai_discover"              { "discover"        }
        "mcp_enhance"             { "enhance"         }
        "mcp_confidence_scoring"  { "confidence"      }
        "mcp_semantic_consistency"{ "consistency"     }
        "mcp_validate_links"      { "validate-links"  }
        default                    { $ToolName          }
    }

    # Compatibility: allow modulePath passed in Parameters hash
        $ModulePath = $Parameters['modulePath']
    }

    # Build argument string
    $argStr = "$cliCmd --module $Module --project $repoRoot"
    if ($ModulePath -and $ModulePath -ne "") { $argStr += " --module-path $ModulePath" }
    $cmdLine = "`"$gradlePath`" :configurable-agent:run --args=`"$argStr`" --console=plain -q --no-daemon"
    if ($Parameters["direction"]) { $argStr += " --direction $($Parameters['direction'])" }
    if ($Parameters["layer"])     { $argStr += " --layer $($Parameters['layer'])" }
    $rawOutput = Invoke-GradleCommand -CmdLine $cmdLine -TimeoutSeconds $TimeoutSeconds
    if ($null -eq $rawOutput) {
        Write-Host "  [TIMEOUT] $ToolName/$Module after ${TimeoutSeconds}s" -ForegroundColor Red
        return @{
            Status    = "timeout"
            ToolName  = $ToolName
            Module    = $Module
            Error     = "Gradle timed out after ${TimeoutSeconds}s"
            Timestamp = (Get-Date -Format "o")
        }
    }
    $depType = $null
    if ($Parameters -and $Parameters.ContainsKey('dependencyType')) { $depType = $Parameters['dependencyType'] }
    elseif ($script:DependencyType) { $depType = $script:DependencyType }
    if ($depType) { $argStr += " --dependency-type $depType" }

    $gradlePath = Join-Path $repoRoot "gradlew.bat"
    $cmdLine = "`"$gradlePath`" :configurable-agent:run --args=`"$argStr`" --console=plain -q 2>&1"
    Write-Verbose "Executing: $cmdLine"

    $rawOutput = cmd /c $cmdLine
    $output = $rawOutput | Where-Object { $_ -match '^	*\{' -or $_ -match '^\s*\{' }

    $result = $null
    if ($output) {
        try { $result = $output | ConvertFrom-Json -ErrorAction SilentlyContinue } catch { $null }
    }

    if ($result) { return $result }
    return @{ 
        Status = "failed"
        ToolName = $ToolName
        Module = $Module
        Error = "No JSON output received"
        RawOutput = ($rawOutput -join "`n")
        Timestamp = (Get-Date -Format "o") 
    }
}

# ---------------------------------------------------------------------------
# PHASE 0 -- MANDATORY FULL PURGE
# ---------------------------------------------------------------------------
function Invoke-MandatoryFullPurge {
    Write-Host "`n========================================" -ForegroundColor Red
    Write-Host "PHASE 0: Mandatory Full Purge"           -ForegroundColor Red
    Write-Host "========================================" -ForegroundColor Red
    Write-Host "  [!] Purge is unconditional in FULL_DISCOVERY -- no skip flag." -ForegroundColor Yellow

    $purged = @()

    if (Test-Path $semanticCacheRoot) {
        $n = (Get-ChildItem -Path $semanticCacheRoot -Recurse -File -ErrorAction SilentlyContinue).Count
        Remove-Item (Join-Path $semanticCacheRoot "*") -Recurse -Force -ErrorAction SilentlyContinue
        $purged += ".semantic-cache/ [$n files]"
        Write-Host "  Purged: .semantic-cache/ [$n files]" -ForegroundColor Green
    } else {
        Write-Host "  .semantic-cache/ -- already absent" -ForegroundColor Gray
    }

    $archResultPath = Join-Path $visionAiPath ".vision/contracts/results/architecture.yaml"
    if (Test-Path $archResultPath) {
        Remove-Item $archResultPath -Force
        $purged += ".vision/contracts/results/architecture.yaml"
        Write-Host "  Purged: .vision-ai/.vision/contracts/results/architecture.yaml" -ForegroundColor Green
    }

    Write-Host "  Total purged targets: $($purged.Count)" -ForegroundColor Green
    Add-HealthSnapshot -Phase "post-purge" -Reliability 0.0 -Artifacts 0 -Links 0
    return @{ PurgedTargets = $purged }
}

# ---------------------------------------------------------------------------
# PHASE 1 -- ARCHITECTURE DETECTION
#   1a  Heuristic scan   (free, instant)
#   1b  LLM Simulate     (called when heuristic confidence is medium)
#   1c  LLM Full Sim     (called when heuristic confidence is low)
#
# Output: build_system label that Phase 2 will use to select an adapter.
# ---------------------------------------------------------------------------

# 1a -- Heuristic
function Invoke-HeuristicScan {
    $score      = 0.0
    $indicators = [System.Collections.Generic.List[string]]::new()
    $buildSystem = "unknown"

    # Build system probes (ordered; first match wins for build_system label)
    $bsProbes = @(
        @{ Label = "gradle_kotlin_dsl"; File = "settings.gradle.kts"; Weight = 0.30 }
        @{ Label = "gradle_groovy";     File = "settings.gradle";     Weight = 0.25 }
        @{ Label = "maven";             File = "pom.xml";             Weight = 0.25 }
        @{ Label = "npm";               File = "package.json";        Weight = 0.20 }
        @{ Label = "go_workspace";      File = "go.work";             Weight = 0.20 }
        @{ Label = "cargo";             File = "Cargo.toml";          Weight = 0.20 }
        @{ Label = "python_poetry";     File = "pyproject.toml";      Weight = 0.20 }
        @{ Label = "python_setup";      File = "setup.py";            Weight = 0.15 }
    )
    foreach ($p in $bsProbes) {
        if (Test-Path (Join-Path $repoRoot $p.File)) {
            $score += $p.Weight
            $indicators.Add($p.Label)
            if ($buildSystem -eq "unknown") { $buildSystem = $p.Label }
        }
    }

    # Language probes
    $langProbes = @(
        @{ Label = "kotlin_source";     Glob = "*.kt";  ExcDirs = @("build",".gradle");  Weight = 0.25; Limit = 100 }
        @{ Label = "java_source";       Glob = "*.java"; ExcDirs = @("build","target");  Weight = 0.15; Limit = 50  }
        @{ Label = "typescript_source"; Glob = "*.ts";   ExcDirs = @("node_modules","dist","build"); Weight = 0.15; Limit = 50 }
        @{ Label = "go_source";         Glob = "*.go";   ExcDirs = @("vendor");          Weight = 0.15; Limit = 30  }
        @{ Label = "rust_source";       Glob = "*.rs";   ExcDirs = @("target");          Weight = 0.15; Limit = 30  }
        @{ Label = "python_source";     Glob = "*.py";   ExcDirs = @("__pycache__",".venv","venv"); Weight = 0.15; Limit = 30 }
    )
    foreach ($p in $langProbes) {
        $found = @()
        # Fast scan: Avoid full recursion using robocopy or directory filtering, but for now we look at src directories where present.
        $srcDirs = Get-ChildItem -Path $repoRoot -Directory -Filter "src" -Recurse -Depth 2 -ErrorAction SilentlyContinue
        # Simple fast path for test performance
        if ($srcDirs.Count -eq 0) { $srcDirs = @((Get-Item $repoRoot)) }

        foreach ($dir in $srcDirs) {
            $files = Get-ChildItem -Path $dir.FullName -Recurse -File -Filter $p.Glob -ErrorAction SilentlyContinue | Select-Object -First $p.Limit
            if ($files) { $found += $files }
            if ($found.Count -ge $p.Limit) { break }
        }

        if ($found) {
            $score += $p.Weight
            $indicators.Add("$($p.Label) ($($found.Count) files)")
        }
    }

    # Architecture pattern probes
    $patternProbes = @(
        @{ Label = "multi_module";       Files = @("settings.gradle.kts");      Weight = 0.10 }
        @{ Label = "orchestrator_pattern"; DirName = "orchestrator";            Weight = 0.05 }
    )
    foreach ($p in $patternProbes) {
        if ($p.Files) {
            if ($p.Files | Where-Object { Test-Path (Join-Path $repoRoot $_) }) {
                $score += $p.Weight; $indicators.Add($p.Label)
            }
        }
        if ($p.DirName) {
            if (Get-ChildItem -Path $repoRoot -Recurse -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq $p.DirName } | Select-Object -First 1) {
                $score += $p.Weight; $indicators.Add($p.Label)
            }
        }
    }

    # Framework probes (scan sample .kt / .java files)
    $fwProbes = @(
        @{ Label = "ktor_framework"; Pattern = "io\.ktor|embeddedServer|routing\s*\{"; Weight = 0.10 }
        @{ Label = "spring_boot";    Pattern = "@SpringBootApplication";               Weight = 0.10 }
        @{ Label = "koog_agent";     Pattern = "ai\.koog|KoogAgent";                  Weight = 0.05 }
        @{ Label = "react";          Pattern = "from ['\`"]react['\`"]";               Weight = 0.10 }
    )
    $sampleSources = @(Get-ChildItem -Path $repoRoot -Recurse -Include "*.kt","*.java","*.ts","*.tsx" -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -notmatch '\\build\\|\\node_modules\\|\\dist\\' } | Select-Object -First 30)

    foreach ($p in $fwProbes) {
        $hit = $sampleSources | Where-Object {
            try { (Get-Content $_.FullName -Raw -ErrorAction SilentlyContinue) -match $p.Pattern } catch { $false }
        } | Select-Object -First 1
        if ($hit) { $score += $p.Weight; $indicators.Add($p.Label) }
    }

    # Architecture style inference
    $archStyle = "unknown"
    if ($indicators -contains "multi_module" -and ($indicators | Where-Object { $_ -match "kotlin_source" })) {
        $archStyle = "modular"
    } elseif ($indicators | Where-Object { $_ -match "clean_architecture" }) {
        $archStyle = "clean"
    } elseif ($indicators | Where-Object { $_ -match "layered_architecture" }) {
        $archStyle = "layered"
    }

    $primaryLang = "unknown"
    if ($indicators | Where-Object { $_ -match "kotlin_source" })     { $primaryLang = "kotlin" }
    elseif ($indicators | Where-Object { $_ -match "java_source" })   { $primaryLang = "java"   }
    elseif ($indicators | Where-Object { $_ -match "typescript_source" }) { $primaryLang = "typescript" }
    elseif ($indicators | Where-Object { $_ -match "go_source" })     { $primaryLang = "go"     }
    elseif ($indicators | Where-Object { $_ -match "rust_source" })   { $primaryLang = "rust"   }
    elseif ($indicators | Where-Object { $_ -match "python_source" }) { $primaryLang = "python" }

    $confidence = [Math]::Min($score, 1.0)

    return @{
        Confidence   = $confidence
        BuildSystem  = $buildSystem
        ArchStyle    = $archStyle
        PrimaryLang  = $primaryLang
        Indicators   = @($indicators)
    }
}

# 1b/1c -- Simulated LLM response (deterministic, based on heuristic findings)
# Matches the llm_simulation rules in the strategy YAML.
# In a real deployment this would call ai_discover / an actual LLM endpoint.
function Invoke-LlmSimulation {
    param(
        [hashtable]$HeuristicResult,
        [string]   $Mode   # "verify" | "full"
    )

    $ind = $HeuristicResult.Indicators

    # Simulation rules (ordered -- most-specific first)
    $rules = @(
        @{
            Match     = { ($ind | Where-Object { $_ -match "kotlin_source" }) -and
                          ($ind -contains "gradle_kotlin_dsl") -and
                          ($ind -contains "ktor_framework") }
            Template  = "kotlin-ktor-service"
            Boost     = 0.12
            Style     = "modular"
            Reasoning = "Kotlin + Gradle DSL + Ktor -> confirmed Ktor service"
        }
        @{
            Match     = { ($ind | Where-Object { $_ -match "kotlin_source" }) -and ($ind -contains "gradle_kotlin_dsl") }
            Template  = "kotlin-backend"
            Boost     = 0.10
            Style     = "modular"
            Reasoning = "Kotlin + Gradle DSL -> confirmed Kotlin backend, modular layout"
        }
        @{
            Match     = { ($ind | Where-Object { $_ -match "typescript_source" }) -and ($ind -contains "npm") }
            Template  = "realtime-dashboard-fullstack"
            Boost     = 0.08
            Style     = "layered"
            Reasoning = "TypeScript + npm -> confirmed frontend/fullstack project"
        }
        @{
            Match     = { ($ind | Where-Object { $_ -match "java_source" }) -and ($ind -contains "maven") }
            Template  = "java-maven-service"
            Boost     = 0.07
            Style     = "layered"
            Reasoning = "Java + Maven -> confirmed traditional Java service"
        }
        @{
            Match     = { ($ind | Where-Object { $_ -match "go_source" }) -and ($ind -contains "go_workspace") }
            Template  = "go-service"
            Boost     = 0.08
            Style     = "modular"
            Reasoning = "Go + go.work -> confirmed Go workspace"
        }
        @{
            Match     = { ($ind | Where-Object { $_ -match "rust_source" }) -and ($ind -contains "cargo") }
            Template  = "rust-library"
            Boost     = 0.08
            Style     = "modular"
            Reasoning = "Rust + Cargo -> confirmed Rust project"
        }
        @{
            Match     = { ($ind | Where-Object { $_ -match "python_source" }) }
            Template  = "python-service"
            Boost     = 0.07
            Style     = "layered"
            Reasoning = "Python files detected -> confirmed Python service"
        }
        @{   # catch-all
            Match     = { $true }
            Template  = "generic-project"
            Boost     = 0.0
            Style     = "unknown"
            Reasoning = "No dominant indicators; generic project assumed"
        }
    )

    foreach ($rule in $rules) {
        if (& $rule.Match) {
            $boosted = [Math]::Min($HeuristicResult.Confidence + $rule.Boost, 0.97)
            return @{
                Confidence    = $boosted
                Template      = $rule.Template
                ArchStyle     = $rule.Style
                Reasoning     = "[LLM-sim/$Mode] $($rule.Reasoning)"
                DetectionMethod = if ($Mode -eq "verify") { "llm_verified_sim" } else { "llm_full_sim" }
            }
        }
    }
}

# Orchestrator for Phase 1
function Invoke-ArchitectureDetection {
    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "PHASE 1: Architecture Detection"         -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  [NOTE] Nothing is assumed about the target project." -ForegroundColor Gray
    Write-Host "  Heuristic -> LLM Simulate (if needed). Output drives module enumeration." -ForegroundColor Gray

    # 1a -- Heuristic
    Write-Host "`n  [1a] Heuristic scan..." -ForegroundColor Yellow
    $h = Invoke-HeuristicScan

    $confPct = [Math]::Round($h.Confidence * 100, 1)
    $col     = if ($h.Confidence -ge 0.8) { 'Green' } elseif ($h.Confidence -ge 0.5) { 'Yellow' } else { 'Red' }
    Write-Host "    Build system : $($h.BuildSystem)"                          -ForegroundColor Gray
    Write-Host "    Language     : $($h.PrimaryLang)"                          -ForegroundColor Gray
    Write-Host "    Style        : $($h.ArchStyle)"                            -ForegroundColor Gray
    Write-Host "    Confidence   : $confPct%"                                  -ForegroundColor $col
    Write-Host "    Indicators   : $($h.Indicators -join ', ')"                -ForegroundColor Gray

    $trustThreshold  = [double]$script:RuntimeConfig.Architecture.HeuristicTrustThreshold
    $verifyThreshold = [double]$script:RuntimeConfig.Architecture.LlmVerifyThreshold

    $finalConfidence   = $h.Confidence
    $finalArchStyle    = $h.ArchStyle
    $detectionMethod   = "heuristic"
    $llmReasoning      = ""
    $suggestedTemplate = "unknown"

    if ($h.Confidence -ge $trustThreshold) {
        Write-Host "`n  [1b] Skipped -- heuristic confidence $confPct% >= $([Math]::Round($trustThreshold*100))% (trust threshold)" -ForegroundColor Green
    }
    elseif ($h.Confidence -ge $verifyThreshold) {
        Write-Host "`n  [1b] LLM Verify -- heuristic confidence $confPct% is between $([Math]::Round($verifyThreshold*100))%-$([Math]::Round($trustThreshold*100))%" -ForegroundColor Yellow
        $sim = Invoke-LlmSimulation -HeuristicResult $h -Mode "verify"
        $finalConfidence   = $sim.Confidence
        $finalArchStyle    = $sim.ArchStyle
        $detectionMethod   = $sim.DetectionMethod
        $llmReasoning      = $sim.Reasoning
        $suggestedTemplate = $sim.Template
        Write-Host "    Result: confidence $([Math]::Round($finalConfidence*100,1))% -- $llmReasoning" -ForegroundColor Cyan
    }
    else {
        Write-Host "`n  [1b] Full LLM Analysis -- heuristic confidence $confPct% below $([Math]::Round($verifyThreshold*100))%" -ForegroundColor Red
        $sim = Invoke-LlmSimulation -HeuristicResult $h -Mode "full"
        $finalConfidence   = $sim.Confidence
        $finalArchStyle    = $sim.ArchStyle
        $detectionMethod   = $sim.DetectionMethod
        $llmReasoning      = $sim.Reasoning
        $suggestedTemplate = $sim.Template
        Write-Host "    Result: confidence $([Math]::Round($finalConfidence*100,1))% -- $llmReasoning" -ForegroundColor Cyan
    }

    # Persist contract result
    $resultDir = Join-Path $visionAiPath ".vision/contracts/results"
    if (-not (Test-Path $resultDir)) { New-Item -ItemType Directory -Path $resultDir -Force | Out-Null }

    $evidenceLines = ($h.Indicators | ForEach-Object { "    - `"$_`"" }) -join "`n"
    $archYaml = @"
contract: architecture-detection
timestamp: $(Get-Date -Format "o")
direction: ANALYSIS
input:
  mode: ANALYSIS
  project_path: "$repoRoot"
output:
  architecture:
    style: $finalArchStyle
    primary_language: $($h.PrimaryLang)
    build_system: $($h.BuildSystem)
  confidence: $([Math]::Round($finalConfidence, 3))
  detection_method: $detectionMethod
  suggested_template: $suggestedTemplate
  reasoning: "$llmReasoning"
  evidence:
$evidenceLines
"@
    Set-Content -Path (Join-Path $resultDir "architecture.yaml") -Value $archYaml -Force
    Write-Host "`n  Architecture contract stored -> .vision-ai/.vision/contracts/results/architecture.yaml" -ForegroundColor Green

    # Also update project manifest
    $manifestDir = Join-Path $visionAiPath "project"
    if (-not (Test-Path $manifestDir)) { New-Item -ItemType Directory -Path $manifestDir -Force | Out-Null }
    $manifestYaml = @"
project:
  name: $(Split-Path $repoRoot -Leaf)
  primary_language: $($h.PrimaryLang)
  build_system: $($h.BuildSystem)
  architecture_style: $finalArchStyle
detected_at: $(Get-Date -Format "o")
"@
    Set-Content -Path (Join-Path $manifestDir "manifest.yaml") -Value $manifestYaml -Force
    Write-Host "  Project manifest updated   -> .vision-ai/project/manifest.yaml" -ForegroundColor Green

    return @{
        BuildSystem        = $h.BuildSystem
        PrimaryLang        = $h.PrimaryLang
        ArchStyle          = $finalArchStyle
        Confidence         = $finalConfidence
        DetectionMethod    = $detectionMethod
        SuggestedTemplate  = $suggestedTemplate
        Indicators         = $h.Indicators
    }
}

# ---------------------------------------------------------------------------
# PHASE 2 -- MODULE ENUMERATION  (adapter driven by Phase 1 build_system)
# ---------------------------------------------------------------------------
function Invoke-ModuleEnumeration {
    param([hashtable]$ArchResult)

    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "PHASE 2: Module Enumeration"             -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  Build system detected: $($ArchResult.BuildSystem)" -ForegroundColor Gray
    Write-Host "  Selecting enumeration adapter..."      -ForegroundColor Gray

    $modules     = [System.Collections.Generic.List[object]]::new()
    $adapterUsed = "fallback"

    switch ($ArchResult.BuildSystem) {

        { $_ -in @("gradle_kotlin_dsl", "gradle_groovy") } {
            $fileName = if ($_ -eq "gradle_kotlin_dsl") { "settings.gradle.kts" } else { "settings.gradle" }
            $settingsFile = Join-Path $repoRoot $fileName
            Write-Host "  Adapter: gradle ($fileName)" -ForegroundColor Cyan
            $adapterUsed = $_

            $content = Get-Content $settingsFile -Raw
            $matches_ = [regex]::Matches($content, 'include\("([^"]+)"\)')
            foreach ($m in $matches_) {
                $moduleId   = $m.Groups[1].Value
                $relPath    = $moduleId -replace ':', [IO.Path]::DirectorySeparatorChar
                $modulePath = Join-Path $repoRoot $relPath
                $clusterId  = ($moduleId -split ':')[-1]
                $hasSrc     = Test-Path (Join-Path $modulePath "src/main")
                $modules.Add([pscustomobject]@{
                    Id          = $moduleId
                    ClusterId   = $clusterId
                    Path        = $modulePath
                    HasSources  = $hasSrc
                    HasKotlin   = [bool](Get-ChildItem -Path $modulePath -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue | Select-Object -First 1)
                    AdapterUsed = $adapterUsed
                    CachePath   = Get-CachePath $clusterId
                })
            }
        }

        "maven" {
            $pomFile = Join-Path $repoRoot "pom.xml"
            Write-Host "  Adapter: maven (pom.xml)" -ForegroundColor Cyan
            $adapterUsed = "maven"

            [xml]$pom = Get-Content $pomFile
            $pom.project.modules.module | ForEach-Object {
                $moduleId   = [string]$_
                $modulePath = Join-Path $repoRoot $moduleId
                $modules.Add([pscustomobject]@{
                    Id          = $moduleId
                    ClusterId   = $moduleId
                    Path        = $modulePath
                    HasSources  = Test-Path (Join-Path $modulePath "src/main")
                    HasKotlin   = $false
                    AdapterUsed = $adapterUsed
                    CachePath   = Get-CachePath $moduleId
                })
            }
        }

        "npm" {
            $pkgFile = Join-Path $repoRoot "package.json"
            Write-Host "  Adapter: npm (package.json workspaces)" -ForegroundColor Cyan
            $adapterUsed = "npm"

            $pkg = Get-Content $pkgFile -Raw | ConvertFrom-Json
            $workspaces = if ($pkg.workspaces -is [array]) { $pkg.workspaces } `
                          elseif ($pkg.workspaces.packages) { $pkg.workspaces.packages } `
                          else { @() }
            foreach ($ws in $workspaces) {
                # Expand globs like "packages/*"
                $wsPath = Join-Path $repoRoot ($ws -replace '\*','')
                if (Test-Path $wsPath) {
                    Get-ChildItem -Path $wsPath -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                        $modules.Add([pscustomobject]@{
                            Id          = $_.Name
                            ClusterId   = $_.Name
                            Path        = $_.FullName
                            HasSources  = (Test-Path (Join-Path $_.FullName "src")) -or (Get-ChildItem -Path $_.FullName -Filter "*.ts" -ErrorAction SilentlyContinue | Select-Object -First 1)
                            HasKotlin   = $false
                            AdapterUsed = $adapterUsed
                            CachePath   = Get-CachePath $($_.Name)
                        })
                    }
                }
            }
        }

        "go_workspace" {
            $goWork = Join-Path $repoRoot "go.work"
            Write-Host "  Adapter: go_workspace (go.work)" -ForegroundColor Cyan
            $adapterUsed = "go_workspace"

            Get-Content $goWork | Where-Object { $_ -match '^\s*use\s+\.' } | ForEach-Object {
                $rel  = ($_ -replace '^\s*use\s+', '').Trim()
                $dir  = Join-Path $repoRoot $rel
                $name = Split-Path $dir -Leaf
                $modules.Add([pscustomobject]@{
                    Id          = $name
                    ClusterId   = $name
                    Path        = $dir
                    HasSources  = [bool](Get-ChildItem -Path $dir -Filter "*.go" -ErrorAction SilentlyContinue | Select-Object -First 1)
                    HasKotlin   = $false
                    AdapterUsed = $adapterUsed
                    CachePath   = Get-CachePath $name
                })
            }
        }

        default {
            Write-Host "  Adapter: fallback (directory scan, depth=2)" -ForegroundColor Yellow
            $adapterUsed = "fallback"

            $excludeDirs = @('build','target','dist','node_modules','.git','.gradle',
                             '.vision-ai','.semantic-cache','gradle','scripts','docs','buildSrc','.idea')
            Get-ChildItem -Path $repoRoot -Directory -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notin $excludeDirs } | ForEach-Object {
                $hasContent = [bool](Get-ChildItem -Path $_.FullName -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1)
                if ($hasContent) {
                    $modules.Add([pscustomobject]@{
                        Id          = $_.Name
                        ClusterId   = $_.Name
                        Path        = $_.FullName
                        HasSources  = Test-Path (Join-Path $_.FullName "src")
                        HasKotlin   = [bool](Get-ChildItem -Path $_.FullName -Recurse -Filter "*.kt" -ErrorAction SilentlyContinue | Select-Object -First 1)
                        AdapterUsed = $adapterUsed
                            CachePath   = Get-CachePath $($_.Name)
                    })
                }
            }
        }
    }

    Write-Host "`n  Modules found ($adapterUsed adapter): $($modules.Count)" -ForegroundColor Green
    foreach ($m in $modules) {
        $tag = if ($m.HasKotlin) { "[KT]" } elseif ($m.HasSources) { "[src]" } else { "[dir]" }
        Write-Host "    $tag $($m.Id) -> cluster: $($m.ClusterId)" -ForegroundColor Gray
    }

    $processable = @($modules | Where-Object { $_.HasSources -or $_.AdapterUsed -eq "fallback" })
    Write-Host "`n  Processable modules: $($processable.Count)" -ForegroundColor Cyan

    return @{
        AllModules   = @($modules)
        Processable  = $processable
        TotalCount   = $modules.Count
        SourceCount  = $processable.Count
        AdapterUsed  = $adapterUsed
    }
}

# ---------------------------------------------------------------------------
# PHASE 3 -- PER-MODULE DISCOVERY  (C->V, bottom-up)
# ---------------------------------------------------------------------------
function Invoke-PerModuleDiscovery {
    param([hashtable]$ModuleEnum)

    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "PHASE 3: Per-Module Discovery (C->V)"    -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  Order: code -> flow -> logic -> structure -> vision" -ForegroundColor Gray

    $moduleResults  = @{}
    $discoveredCount = 0
    $failedCount     = 0

    foreach ($module in $ModuleEnum.Processable) {
        Write-Host "`n  --- $($module.Id) [cluster: $($module.ClusterId)] ---" -ForegroundColor Yellow

        # Run primary discovery and capture JSON result
        $discoverResult = Invoke-McpTool -ToolName "ai_discover" `
            -Parameters @{ mode = "full"; direction = "code_to_vision"; modulePath = $module.Path; dependencyType = $script:DependencyType } `
            -Module $module.ClusterId

        $artifactsCreated = 0
        if ($discoverResult -and $discoverResult.artifactsCreated -ne $null) {
            try { $artifactsCreated = [int]$discoverResult.artifactsCreated } catch { $artifactsCreated = 0 }
        }

        # Retry logic: incremental -> link_repair if nothing produced
        if ($artifactsCreated -eq 0) {
            Write-Host "    [WARN] no artifacts created for $($module.ClusterId) in full mode. Retrying incremental..." -ForegroundColor Yellow
            Start-Sleep -Seconds 1
            $incResult = Invoke-McpTool -ToolName "ai_discover" -Parameters @{ mode = "incremental"; direction = "code_to_vision"; modulePath = $module.Path; dependencyType = $script:DependencyType } -Module $module.ClusterId
            if ($incResult -and $incResult.artifactsCreated -ne $null) {
                try { $artifactsCreated = [int]$incResult.artifactsCreated } catch { }
            }
        }

        if ($artifactsCreated -eq 0) {
            Write-Host "    [WARN] incremental also produced 0 artifacts. Attempting link_repair focus..." -ForegroundColor Yellow
            Start-Sleep -Seconds 1
            $lrResult = Invoke-McpTool -ToolName "ai_discover" -Parameters @{ mode = "link_repair"; direction = "code_to_vision"; focus = "cross_layer_links"; modulePath = $module.Path; dependencyType = $script:DependencyType } -Module $module.ClusterId
            if ($lrResult -and $lrResult.artifactsCreated -ne $null) {
                try { $artifactsCreated = [int]$lrResult.artifactsCreated } catch { }
            }
        }

        # Always attempt enhancement and scoring to populate metadata
        Invoke-McpTool -ToolName "mcp_enhance" -Parameters @{ modulePath = $module.Path } -Module $module.ClusterId | Out-Null
        Invoke-McpTool -ToolName "mcp_confidence_scoring" -Parameters @{ modulePath = $module.Path } -Module $module.ClusterId | Out-Null

        $layerResults = @{}
        foreach ($layerName in @('code','flow','logic','structure','vision')) {
            $layerPath = Join-Path $module.CachePath $layerName
            $exists    = Test-Path $layerPath
            $fileCount = if ($exists) {
                (Get-ChildItem -Path $layerPath -Recurse -File -ErrorAction SilentlyContinue).Count
            } else { 0 }
            $layerResults[$layerName] = @{ Exists = $exists; FileCount = $fileCount }
        }

        $presentLayers  = ($layerResults.Values | Where-Object { $_.Exists }).Count
        $requiredLayers = ($script:RuntimeConfig.LayerConfig.GetEnumerator() | Where-Object { $_.Value.Required }).Count

        $col = 'Red'
        if ($presentLayers -ge $requiredLayers) {
            $col = 'Green'
        } elseif ($presentLayers -gt 0) {
            $col = 'Yellow'
        } else {
            $col = 'Red'
        }
        Write-Host "    Layers present: $presentLayers / 5  ($requiredLayers required)" -ForegroundColor $col

        if ($presentLayers -eq 0) { $failedCount++ } else { $discoveredCount++ }

        $moduleResults[$module.Id] = @{
            Module        = $module
            LayerResults  = $layerResults
            PresentLayers = $presentLayers
        }
    }

    # Project-wide link validation after all modules (Lesson 2: mode from strategy YAML)
    Write-Host "`n  Running mcp_validate_links (post all modules, mode=$($script:LinkPhaseMode))..." -ForegroundColor DarkCyan
    Invoke-McpTool -ToolName "mcp_validate_links" -Parameters @{ mode = $script:LinkPhaseMode } | Out-Null

    Write-Host "`n  Discovery complete: $discoveredCount/$($ModuleEnum.Processable.Count) modules with artifacts" `
        -ForegroundColor $(if ($failedCount -eq 0) { 'Green' } else { 'Yellow' })

    return @{
        ModuleResults    = $moduleResults
        DiscoveredCount  = $discoveredCount
        FailedCount      = $failedCount
        TotalModules     = $ModuleEnum.Processable.Count
    }
}

# ---------------------------------------------------------------------------
# PHASE 4 -- CROSS-MODULE LINKING  (V<->S, S<->L, L<->F, F<->C)
# ---------------------------------------------------------------------------
function Invoke-CrossModuleLinking {
    param([hashtable]$DiscoveryResult)

    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "PHASE 4: Cross-Module Linking"           -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  Contracts: V<->S, S<->L, L<->F, F<->C"       -ForegroundColor Gray

    $contractPairs = @(
        @{ From = "vision";    To = "structure"; Contract = "architecture-detection" }
        @{ From = "structure"; To = "logic";     Contract = "component-to-rules"     }
        @{ From = "logic";     To = "flow";      Contract = "rule-to-flow"            }
        @{ From = "flow";      To = "code";      Contract = "flow-to-code"            }
    )

    $linksFound = @()
    foreach ($pair in $contractPairs) {
        $pairLinks = ($DiscoveryResult.ModuleResults.Values | Where-Object {
            $_.LayerResults[$pair.From].Exists -and $_.LayerResults[$pair.To].Exists
        }).Count

        $linksFound += @{ Pair = "$($pair.From)->$($pair.To)"; Count = $pairLinks; Contract = $pair.Contract }
        $icon = if ($pairLinks -gt 0) { "OK" } else { "!!" }
        $col  = if ($pairLinks -gt 0) { 'Green' } else { 'Red' }
        Write-Host "    [$icon] $($pair.From) -> $($pair.To) [$($pair.Contract)]: $pairLinks module(s)" -ForegroundColor $col
    }

    $linkedPairs = ($linksFound | Where-Object { $_.Count -gt 0 }).Count
    $linkHealth  = $linkedPairs / [Math]::Max($contractPairs.Count, 1)

    Write-Host "`n  Link health: $([Math]::Round($linkHealth * 100, 1))%  ($linkedPairs / $($contractPairs.Count) pairs)" `
        -ForegroundColor $(if ($linkHealth -ge 0.7) { 'Green' } elseif ($linkHealth -ge 0.5) { 'Yellow' } else { 'Red' })

    return @{
        LinksFound   = $linksFound
        LinkedPairs  = $linkedPairs
        TotalPairs   = $contractPairs.Count
        LinkHealth   = $linkHealth
    }
}

# ---------------------------------------------------------------------------
# PHASE 5 -- PROJECT-WIDE VALIDATION
# ---------------------------------------------------------------------------
function Invoke-ProjectWideValidation {
    param(
        [hashtable]$ModuleEnum,
        [hashtable]$DiscoveryResult,
        [hashtable]$LinkingResult,
        [hashtable]$ArchResult
    )

    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "PHASE 5: Project-Wide Validation"        -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan

    $issues = [System.Collections.Generic.List[hashtable]]::new()

    # Check 1 -- module coverage
    $moduleCoverage = if ($ModuleEnum.SourceCount -gt 0) {
        $DiscoveryResult.DiscoveredCount / $ModuleEnum.SourceCount
    } else { 0.0 }
    if ($moduleCoverage -lt [double]$script:RuntimeConfig.Validation.ModuleCoverageMin) {
        $issues.Add(@{ Type = "module_coverage_deficit"; Severity = "high";
            Desc = "Coverage $([Math]::Round($moduleCoverage*100,1))% below $([Math]::Round([double]$script:RuntimeConfig.Validation.ModuleCoverageMin*100))%" })
    }
    Write-Host "`n  Module coverage   : $([Math]::Round($moduleCoverage*100,1))%  ($($DiscoveryResult.DiscoveredCount)/$($ModuleEnum.SourceCount))" `
        -ForegroundColor $(if ($moduleCoverage -ge [double]$script:RuntimeConfig.Validation.ModuleCoverageMin) { 'Green' } else { 'Yellow' })

    # Check 2 -- layer completeness
    $totalSlots   = $DiscoveryResult.TotalModules * 5
    $presentSlots = ($DiscoveryResult.ModuleResults.Values |
        ForEach-Object { [int]$_.PresentLayers } | Measure-Object -Sum).Sum
    if ($null -eq $presentSlots) { $presentSlots = 0 }
    $layerCompleteness = if ($totalSlots -gt 0) { $presentSlots / $totalSlots } else { 0.0 }
    if ($layerCompleteness -lt [double]$script:RuntimeConfig.Validation.LayerCompletenessMin) {
        $issues.Add(@{ Type = "layer_completeness_deficit"; Severity = "medium";
            Desc = "Completeness $([Math]::Round($layerCompleteness*100,1))% below $([Math]::Round([double]$script:RuntimeConfig.Validation.LayerCompletenessMin*100))%" })
    }
    Write-Host "  Layer completeness: $([Math]::Round($layerCompleteness*100,1))%  ($presentSlots/$totalSlots slots)" `
        -ForegroundColor $(if ($layerCompleteness -ge 0.7) { 'Green' } elseif ($layerCompleteness -ge 0.4) { 'Yellow' } else { 'Red' })

    # Check 3 -- architecture confidence
    $archConf = $ArchResult.Confidence
    if ($archConf -lt 0.80) {
        $issues.Add(@{ Type = "low_arch_confidence"; Severity = "medium";
            Desc = "Arch confidence $([Math]::Round($archConf*100,1))% < 80%" })
    }
    Write-Host "  Arch confidence   : $([Math]::Round($archConf*100,1))%  [$($ArchResult.DetectionMethod)]" `
        -ForegroundColor $(if ($archConf -ge 0.8) { 'Green' } elseif ($archConf -ge 0.6) { 'Yellow' } else { 'Red' })

    # Check 4 -- link health
    $linkHealth = $LinkingResult.LinkHealth
    if ($linkHealth -lt 0.70) {
        $issues.Add(@{ Type = "low_link_health"; Severity = "medium";
            Desc = "Link health $([Math]::Round($linkHealth*100,1))% < 70%" })
    }
    Write-Host "  Link health       : $([Math]::Round($linkHealth*100,1))%" `
        -ForegroundColor $(if ($linkHealth -ge 0.7) { 'Green' } elseif ($linkHealth -ge 0.5) { 'Yellow' } else { 'Red' })

    # Reliability score
    $rw = $script:RuntimeConfig.Reliability.Weights
    $reliabilityScore = (
        $moduleCoverage    * [double]$rw.moduleCoverage         +
        $layerCompleteness * [double]$rw.layerCompleteness      +
        $linkHealth        * [double]$rw.linkHealth             +
        $archConf          * [double]$rw.architectureConfidence
    )

    $rt     = $script:RuntimeConfig.Reliability.StatusThresholds
    $status = if     ($reliabilityScore -ge [double]$rt.excellent)   { "excellent" }
              elseif ($reliabilityScore -ge [double]$rt.good)         { "good"      }
              elseif ($reliabilityScore -ge [double]$rt.acceptable)   { "acceptable"}
              else   { "poor" }

    $highCount   = ($issues | Where-Object { $_.Severity -eq 'high'   }).Count
    $mediumCount = ($issues | Where-Object { $_.Severity -eq 'medium' }).Count

    Write-Host "`n  Issues: HIGH=$highCount  MEDIUM=$mediumCount" `
        -ForegroundColor $(if ($highCount -eq 0) { 'Green' } else { 'Red' })
    if ($highCount -gt 0) {
        $issues | Where-Object { $_.Severity -eq 'high' } |
            ForEach-Object { Write-Host "    [HIGH] $($_.Type): $($_.Desc)" -ForegroundColor Red }
    }

    $penalties     = $script:RuntimeConfig.Validation.Penalties
    $coherenceScore = [Math]::Max(0,
        1.0 - ($highCount * [double]$penalties.high) - ($mediumCount * [double]$penalties.medium))

    Write-Host "`n  Reliability : $([Math]::Round($reliabilityScore*100,1))%  [$status]" `
        -ForegroundColor $(if ($reliabilityScore -ge [double]$rt.good) { 'Green' } elseif ($reliabilityScore -ge [double]$rt.acceptable) { 'Yellow' } else { 'Red' })
    Write-Host "  Coherence   : $([Math]::Round($coherenceScore*100,1))%" `
        -ForegroundColor $(if ($coherenceScore -ge 0.85) { 'Green' } elseif ($coherenceScore -ge 0.70) { 'Yellow' } else { 'Red' })

    return @{
        ModuleCoverage    = $moduleCoverage
        LayerCompleteness = $layerCompleteness
        ArchConfidence    = $archConf
        LinkHealth        = $linkHealth
        ReliabilityScore  = $reliabilityScore
        ReliabilityStatus = $status
        CoherenceScore    = $coherenceScore
        HighCount         = $highCount
        MediumCount       = $mediumCount
        Issues            = @($issues)
    }
}

# ---------------------------------------------------------------------------
# PHASE 6 -- LEARNING RECORDING
# ---------------------------------------------------------------------------
function Invoke-LearningRecording {
    param(
        [hashtable]$ModuleEnum,
        [hashtable]$ArchResult,
        [hashtable]$DiscoveryResult,
        [hashtable]$LinkingResult,
        [hashtable]$ValidationResult,
        [datetime] $StartTime
    )

    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "PHASE 6: Learning Recording"             -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan

    $duration = [int]((Get-Date) - $StartTime).TotalSeconds

    $metrics = @{
        timestamp               = Get-Date -Format "yyyy-MM-ddTHH:mm:ssZ"
        architecture_confidence = [Math]::Round($ArchResult.Confidence, 3)
        detection_method        = $ArchResult.DetectionMethod
        build_system            = $ArchResult.BuildSystem
        suggested_template      = $ArchResult.SuggestedTemplate
        modules_total           = $ModuleEnum.SourceCount
        modules_discovered      = $DiscoveryResult.DiscoveredCount
        module_coverage         = [Math]::Round($ValidationResult.ModuleCoverage, 3)
        layer_completeness      = [Math]::Round($ValidationResult.LayerCompleteness, 3)
        cross_module_links      = $LinkingResult.LinkedPairs
        link_health             = [Math]::Round($LinkingResult.LinkHealth, 3)
        reliability_score       = [Math]::Round($ValidationResult.ReliabilityScore, 3)
        coherence_score         = [Math]::Round($ValidationResult.CoherenceScore, 3)
        high_severity_issues    = $ValidationResult.HighCount
        medium_severity_issues  = $ValidationResult.MediumCount
        discovery_duration_sec  = $duration
    }

    Write-Host "`n  Architecture : $($metrics.architecture_confidence) [$($metrics.detection_method)] -> $($metrics.suggested_template)" -ForegroundColor Gray
    Write-Host "  Modules      : $($metrics.modules_discovered)/$($metrics.modules_total)  coverage $([Math]::Round($metrics.module_coverage*100,1))%" -ForegroundColor Gray
    Write-Host "  Layers       : $([Math]::Round($metrics.layer_completeness*100,1))%  links $($metrics.cross_module_links)/$($LinkingResult.TotalPairs)" -ForegroundColor Gray
    Write-Host "  Reliability  : $($metrics.reliability_score)  coherence $($metrics.coherence_score)" -ForegroundColor Gray
    Write-Host "  Duration     : $duration sec" -ForegroundColor Gray

    # Pattern detection
    $patterns = @()
    if ($metrics.module_coverage     -lt 0.50) { $patterns += "low_module_coverage"       }
    if ($metrics.layer_completeness  -lt 0.40) { $patterns += "sparse_layer_population"   }
    if ($metrics.architecture_confidence -ge 0.90) { $patterns += "high_confidence_architecture" }
    if ($metrics.discovery_duration_sec -gt 300)   { $patterns += "slow_discovery"        }
    if ($ArchResult.DetectionMethod -match "sim")   { $patterns += "llm_simulation_used"  }

    if ($patterns) {
        Write-Host "  Patterns     : $($patterns -join ', ')" -ForegroundColor DarkCyan
    }

    $learningDir  = Join-Path $visionAiPath "learning"
    $learningPath = Join-Path $learningDir "full-discovery-history.yaml"
    if (-not (Test-Path $learningDir)) { New-Item -ItemType Directory -Path $learningDir -Force | Out-Null }

    $patternLine = if ($patterns) { "`n  patterns: [$($patterns -join ', ')]" } else { "" }
    $entry = @(
        "- timestamp: $($metrics.timestamp)"
        "  architecture_confidence: $($metrics.architecture_confidence)"
        "  detection_method: $($metrics.detection_method)"
        "  build_system: $($metrics.build_system)"
        "  suggested_template: $($metrics.suggested_template)"
        "  modules_total: $($metrics.modules_total)"
        "  modules_discovered: $($metrics.modules_discovered)"
        "  module_coverage: $($metrics.module_coverage)"
        "  layer_completeness: $($metrics.layer_completeness)"
        "  cross_module_links: $($metrics.cross_module_links)"
        "  link_health: $($metrics.link_health)"
        "  reliability_score: $($metrics.reliability_score)"
        "  coherence_score: $($metrics.coherence_score)"
        "  high_severity_issues: $($metrics.high_severity_issues)"
        "  medium_severity_issues: $($metrics.medium_severity_issues)"
        "  discovery_duration_sec: $($metrics.discovery_duration_sec)$patternLine"
    ) -join "`n"

    if (Test-Path $learningPath) { Add-Content -Path $learningPath -Value "`n$entry" }
    else { Set-Content -Path $learningPath -Value "# Full Discovery History`n$entry" }

    # Save a discrete strategy result
    $resultsDir = Join-Path $visionAiPath "config/strategies/results"
    if (-not (Test-Path $resultsDir)) { New-Item -ItemType Directory -Path $resultsDir -Force | Out-Null }
    $resultFile = Join-Path $resultsDir "full-discovery-$((Get-Date).ToString('yyyy-MM-dd_HH-mm-ss')).yaml"
    $resultYaml = @"
strategy: vlsfc-full-discovery
timestamp: $($metrics.timestamp)
result: $(if ($ValidationResult.HighCount -eq 0) { 'success' } else { 'partial_success' })
metrics:
  modules_discovered: $($metrics.modules_discovered)
  coverage: $($metrics.module_coverage)
  link_health: $($metrics.link_health)
  reliability_score: $($metrics.reliability_score)
"@
    Set-Content -Path $resultFile -Value $resultYaml -Force

    Write-Host "`n  Learning recorded -> $learningPath" -ForegroundColor Green
    Write-Host "  Strategy result saved -> .vision-ai/config/strategies/results/$(Split-Path $resultFile -Leaf)" -ForegroundColor Green
    return $metrics
}

# ---------------------------------------------------------------------------
# PHASE 3A -- CACHE POPULATION CHECK
# Verify .semantic-cache has real artifact files and links.yaml has entries.
# Records "post-discovery" health snapshot.
# ---------------------------------------------------------------------------
function Invoke-CachePopulationCheck {
    param([hashtable]$ModuleEnum, [hashtable]$DiscoveryResult)

    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "PHASE 3A: Cache Population Check"        -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan

    $totalArtifacts = 0
    $modulesPassed  = 0
    $moduleChecks   = @{}
    $issues         = [System.Collections.Generic.List[hashtable]]::new()

    foreach ($moduleId in $DiscoveryResult.ModuleResults.Keys) {
        $mr      = $DiscoveryResult.ModuleResults[$moduleId]
        $module  = $mr.Module
        $modArts = 0
        $layerCounts = @{}

        foreach ($ln in @('code','flow','logic','structure','vision')) {
            $lp    = Join-Path $module.CachePath $ln
            $files = @(Get-ChildItem -Path $lp -Recurse -File -ErrorAction SilentlyContinue |
                       Where-Object { $_.Extension -in @('.yaml','.yml','.json','.sd') })
            $layerCounts[$ln] = $files.Count
            $modArts += $files.Count
        }
        $totalArtifacts += $modArts

        $required       = @($script:RuntimeConfig.LayerConfig.GetEnumerator() |
                            Where-Object { $_.Value.Required } | ForEach-Object { $_.Key })
        $missingReq     = @($required | Where-Object { $layerCounts[$_] -eq 0 })
        $pass           = ($missingReq.Count -eq 0) -and ($modArts -gt 0)
        if ($pass) { $modulesPassed++ }

        $icon = if ($pass) { "OK" } else { "!!" }
        $col  = if ($pass) { 'Green' } else { 'Red' }
        $lSummary = @('code','flow','logic','structure','vision') |
                    ForEach-Object { "$_=$($layerCounts[$_])" }
        Write-Host "  [$icon] $moduleId  $($lSummary -join '  ')  total=$modArts" -ForegroundColor $col

        if (-not $pass) {
            $issues.Add(@{ Type = "missing_required_layers"; Module = $moduleId
                           Severity = "high"; MissingLayers = $missingReq })
        }
        $moduleChecks[$moduleId] = @{ Artifacts = $modArts; LayerCounts = $layerCounts; Pass = $pass }
    }

    # Check links.yaml
    $linksPath    = Join-Path $semanticCacheRoot "links.yaml"
    $linksExist   = Test-Path $linksPath
    $linkCount    = 0
    if ($linksExist) {
        $lc        = Get-Content $linksPath -Raw -ErrorAction SilentlyContinue
        $linkCount = ([regex]::Matches($lc, '(?m)^\s*- id:')).Count
    }
    $linksOk = $linksExist -and ($linkCount -gt 0)

    $lCol = if ($linksOk) { 'Green' } else { 'Red' }
    $lTxt = if ($linksExist) { "$linkCount entries" } else { "MISSING" }
    Write-Host "`n  links.yaml  : $lTxt" -ForegroundColor $lCol
    if (-not $linksOk) {
        $issues.Add(@{ Type = "no_links"; Severity = "high"; Desc = "links.yaml missing or empty" })
    }

    Write-Host "  Total arts  : $totalArtifacts  |  Modules OK: $modulesPassed/$($DiscoveryResult.TotalModules)" `
        -ForegroundColor $(if ($modulesPassed -eq $DiscoveryResult.TotalModules) { 'Green' } else { 'Yellow' })

    # Post-discovery health snapshot
    $snapReliability = if ($totalArtifacts -gt 0) {
        [Math]::Min(($modulesPassed / [Math]::Max($DiscoveryResult.TotalModules, 1)) * 0.7 +
                    ([Math]::Min($linkCount / 20.0, 1.0)) * 0.3, 1.0)
    } else { 0.0 }
    Add-HealthSnapshot -Phase "post-discovery" -Reliability $snapReliability `
                       -Artifacts $totalArtifacts -Links $linkCount

    return @{
        TotalArtifacts = $totalArtifacts
        LinkCount      = $linkCount
        LinksOk        = $linksOk
        ModulesPassed  = $modulesPassed
        ModuleChecks   = $moduleChecks
        Issues         = @($issues)
        HighCount      = ($issues | Where-Object { $_.Severity -eq 'high' }).Count
    }
}

# ---------------------------------------------------------------------------
# PHASE 4A -- CONTEXT SPOT TESTS  (getContext for folders and files)
# Picks the first 2 processable module folders + 1 source file per module
# (up to 2 files) and verifies each returns non-empty, layer-tagged context.
# ---------------------------------------------------------------------------
function Invoke-ContextSpotTests {
    param([hashtable]$ModuleEnum)

    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "PHASE 4A: Context Spot Tests"            -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan
    Write-Host "  Probing getContext for folders and source files..." -ForegroundColor Gray

    $testPoints  = [System.Collections.Generic.List[hashtable]]::new()
    $gradlePath  = Join-Path $repoRoot "gradlew.bat"

    # Add up to 2 folder test points (first processable modules)
    $fAdded = 0
    foreach ($m in $ModuleEnum.Processable) {
        if ($fAdded -ge 2) { break }
        $testPoints.Add(@{ Kind = "folder"; Path = $m.Path; ClusterId = $m.ClusterId; Label = $m.Id })
        $fAdded++
    }

    # Add up to 2 source file test points (first .kt/.java/.ts/... in each module)
    $sAdded = 0
    foreach ($m in $ModuleEnum.Processable) {
        if ($sAdded -ge 2) { break }
        $f = Get-ChildItem -Path $m.Path -Recurse `
                 -Include "*.kt","*.java","*.ts","*.go","*.rs","*.py" `
                 -ErrorAction SilentlyContinue |
             Where-Object { $_.FullName -notmatch '\\build\\|\\node_modules\\' } |
             Select-Object -First 1
        if ($f) {
            $testPoints.Add(@{ Kind = "file"; Path = $f.FullName
                               ClusterId = $m.ClusterId; Label = "$($m.Id)/$($f.Name)" })
            $sAdded++
        }
    }
        $cmdLine = "`"$gradlePath`" :configurable-agent:run --args=`"$argStr`" --console=plain -q --no-daemon"
        $raw = Invoke-GradleCommand -CmdLine $cmdLine -TimeoutSeconds 120
        if ($null -eq $raw) { $raw = @() }
    $spotResults = [System.Collections.Generic.List[hashtable]]::new()

    foreach ($tp in $testPoints) {
        $relPath = ($tp.Path -replace [regex]::Escape($repoRoot), '') -replace '^[\\/]+', ''
        Write-Host "`n  [$($tp.Kind.ToUpper())] $($tp.Label)" -ForegroundColor Yellow
        Write-Host "    Path: $relPath" -ForegroundColor Gray

        $argStr  = "get-context --path $relPath --module $($tp.ClusterId) --project $repoRoot"
        $cmdLine = "`"$gradlePath`" :configurable-agent:run --args=`"$argStr`" --console=plain -q 2>&1"
        $raw     = cmd /c $cmdLine
        # The CLI outputs a JSON object; extract the "context" field for quality checks
        $jsonLine = ($raw | Where-Object { $_ -match '^\s*\{' } | Select-Object -First 1)
        $parsed   = if ($jsonLine) { try { $jsonLine | ConvertFrom-Json -ErrorAction SilentlyContinue } catch { $null } } else { $null }
        $ctx = if ($parsed -and $parsed.context) { $parsed.context } else {
            ($raw | Where-Object { $_ -notmatch '^\s*$' }) -join "`n"
        }

        $charCount  = $ctx.Length
        $hasLayers  = [bool]($ctx -match '(vision|structure|logic|flow|code)\s*:')
        $hasContent = $charCount -gt 100
        $pass       = $hasContent -and $hasLayers

        if ($pass) { $passCount++ }
        $icon = if ($pass) { "OK" } else { "!!" }
        $col  = if ($pass) { 'Green' } else { 'Red' }
        Write-Host "    [$icon] chars=$charCount  layer-tagged=$hasLayers" -ForegroundColor $col

        $spotResults.Add(@{ Kind = $tp.Kind; Label = $tp.Label
                            CharCount = $charCount; HasLayers = $hasLayers; Pass = $pass })
    }

    $total        = [Math]::Max($testPoints.Count, 1)
    $contextScore = $passCount / $total
    Write-Host "`n  Context quality: $passCount/$($testPoints.Count) test points passed ($([Math]::Round($contextScore*100,1))%)" `
        -ForegroundColor $(if ($contextScore -ge 0.75) { 'Green' } elseif ($contextScore -ge 0.5) { 'Yellow' } else { 'Red' })

    return @{
        TestPoints    = @($testPoints)
        SpotResults   = @($spotResults)
        PassCount     = $passCount
        ContextScore  = $contextScore
        TotalPoints   = $testPoints.Count
    }
}

# ---------------------------------------------------------------------------
# PHASE 4B -- CONTRACT CONTENT VALIDATION
# Reads links.yaml and verifies each contract pair has entries with required
# fields (id, source, confidence) and average confidence >= 0.70.
# ---------------------------------------------------------------------------
function Invoke-ContractContentValidation {
    param([hashtable]$LinkingResult)

    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "PHASE 4B: Contract Content Validation"  -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan

    $linksPath = Join-Path $semanticCacheRoot "links.yaml"
    $issues    = [System.Collections.Generic.List[hashtable]]::new()

    if (-not (Test-Path $linksPath)) {
        Write-Host "  [!!] links.yaml not found -- cannot validate contracts" -ForegroundColor Red
        return @{ ContractPairsValid = 0; TotalPairs = 4; ContractScore = 0.0
                  Issues = @(@{ Type = "no_links_file"; Severity = "high" }) }
    }

    $lc = Get-Content $linksPath -Raw

    # Structural health of the whole file
    # Accept '- id:' (YAML list entries) or 'id:' at line start
    $hasId         = [bool]($lc -match '(?m)^\s*-\s*id:\s*\S' -or $lc -match '(?m)^\s*id:\s*\S')
    $hasConfidence = [bool]($lc -match '(?m)\bconfidence:\s*[0-9.]')
    $hasSource     = [bool]($lc -match '(?m)\bsource:\s*\S')
    $hasNote       = [bool]($lc -match '(?m)\bnote:\s*\S')

    $confValues = [regex]::Matches($lc, 'confidence:\s*([0-9.]+)') |
                  ForEach-Object { [double]$_.Groups[1].Value }
    $avgConf    = if ($confValues.Count -gt 0) { ($confValues | Measure-Object -Average).Average } else { 0.0 }
    $confOk     = $avgConf -ge 0.70

    Write-Host "`n  Structural checks on links.yaml:" -ForegroundColor Yellow
    Write-Host "    has id fields      : $hasId"                                      -ForegroundColor Gray
    Write-Host "    has confidence     : $hasConfidence ($($confValues.Count) values)" -ForegroundColor Gray
    Write-Host "    avg confidence     : $([Math]::Round($avgConf, 3)) $(if ($confOk) { '[OK]' } else { '[LOW]' })" `
        -ForegroundColor $(if ($confOk) { 'Green' } else { 'Yellow' })
    Write-Host "    has source field   : $hasSource"                                  -ForegroundColor Gray
    Write-Host "    has note field     : $hasNote"                                    -ForegroundColor Gray

    # Per-contract-pair presence checks
    # Build dynamic TypePattern values and escape dependency token for regex safety
    $depTokenEscaped = [regex]::Escape($script:DependencyType)
    $logicFlowPattern = "implements|exercises|triggers|$depTokenEscaped"
    $contractChecks = @(
            @{ Pair = "vision->structure";  TypePattern = "implements|maps_to|references"; Contract = "architecture-detection" }
            @{ Pair = "structure->logic";   TypePattern = "implements|contains|defines";   Contract = "component-to-rules"     }
            @{ Pair = "logic->flow";        TypePattern = $logicFlowPattern;                     Contract = "rule-to-flow"            }
            @{ Pair = "flow->code";         TypePattern = "implements|executes|calls";     Contract = "flow-to-code"            }
    )

    $validPairs = 0
    Write-Host "`n  Per-contract-pair checks:" -ForegroundColor Yellow
    foreach ($ck in $contractChecks) {
        $typeHits  = ([regex]::Matches($lc, "type:\s*($($ck.TypePattern))",
                      [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)).Count
        # A pair is valid when the file is structurally sound and has matching type entries
        $pairPass  = $hasId -and $hasConfidence -and $hasSource -and $confOk -and ($typeHits -gt 0)
        if ($pairPass) { $validPairs++ } else {
            $issues.Add(@{ Pair = $ck.Pair; Severity = "medium"
                           TypeHits = $typeHits; AvgConf = $avgConf })
        }
        $icon = if ($pairPass) { "OK" } else { "!!" }
        $col  = if ($pairPass) { 'Green' } else { 'Yellow' }
        Write-Host "    [$icon] $($ck.Pair) [$($ck.Contract)]  type_hits=$typeHits" -ForegroundColor $col
    }

    $contractScore = $validPairs / [Math]::Max($contractChecks.Count, 1)
    Write-Host "`n  Contract score: $validPairs/$($contractChecks.Count) valid ($([Math]::Round($contractScore*100,1))%)" `
        -ForegroundColor $(if ($contractScore -ge 0.75) { 'Green' } elseif ($contractScore -ge 0.5) { 'Yellow' } else { 'Red' })

    return @{
        ContractPairsValid = $validPairs
        TotalPairs         = $contractChecks.Count
        ContractScore      = $contractScore
        AvgConfidence      = [Math]::Round($avgConf, 3)
        StructuralOk       = ($hasId -and $hasConfidence -and $hasSource)
        Issues             = @($issues)
    }
}

# ---------------------------------------------------------------------------
# PHASE 5A -- HEALTH GROWTH MONITORING
# Compares snapshots taken after purge, discovery and validation to confirm
# reliability and artifact counts grew from zero to an acceptable level.
# ---------------------------------------------------------------------------
function Invoke-HealthGrowthMonitoring {
    param(
        [hashtable]$ValidationResult,
        [hashtable]$CacheCheck,
        [hashtable]$ContextTest,
        [hashtable]$ContractCheck
    )

    Write-Host "`n========================================" -ForegroundColor Cyan
    Write-Host "PHASE 5A: Health Growth Monitoring"     -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor Cyan

    # Final snapshot
    Add-HealthSnapshot -Phase "post-validation" `
                       -Reliability $ValidationResult.ReliabilityScore `
                       -Artifacts   $CacheCheck.TotalArtifacts `
                       -Links       $CacheCheck.LinkCount

    # Growth table
    Write-Host "`n  Health trajectory:" -ForegroundColor Yellow
    Write-Host ("  {0,-22}  {1,8}  {2,9}  {3,6}" -f "Phase", "Reliab%", "Artifacts", "Links") -ForegroundColor DarkGray
    Write-Host ("  {0,-22}  {1,8}  {2,9}  {3,6}" -f "------", "-------", "---------", "-----") -ForegroundColor DarkGray
    foreach ($snap in $script:HealthSnapshots) {
        $bar = "#" * [int]($snap.Reliability * 15)
        Write-Host ("  {0,-22}  {1,7:P0}  {2,9}  {3,6}  [{4}]" -f `
            $snap.Phase, $snap.Reliability, $snap.Artifacts, $snap.Links, $bar.PadRight(15)) `
            -ForegroundColor Gray
    }

    # Composite health score
    $artifactScore  = [Math]::Min($CacheCheck.TotalArtifacts / 50.0, 1.0)
    $linkScore      = [Math]::Min($CacheCheck.LinkCount      / 10.0, 1.0)
    $contextScore   = $ContextTest.ContextScore
    $contractScore  = $ContractCheck.ContractScore
    $reliabilityNow = $ValidationResult.ReliabilityScore

    $healthScore = ($reliabilityNow * 0.30) + ($artifactScore * 0.25) +
                   ($linkScore      * 0.20) + ($contextScore  * 0.15) +
                   ($contractScore  * 0.10)

    $rt = $script:RuntimeConfig.Reliability.StatusThresholds
    $hs = if     ($healthScore -ge [double]$rt.excellent)   { "excellent"  }
          elseif ($healthScore -ge [double]$rt.good)         { "good"       }
          elseif ($healthScore -ge [double]$rt.acceptable)   { "acceptable" }
          else   { "poor" }

    # Growth assertions
    $snapList        = @($script:HealthSnapshots)
    $firstSnap       = $snapList | Select-Object -First 1
    $lastSnap        = $snapList | Select-Object -Last 1
    $reliabilityGrew = ($null -eq $firstSnap) -or ($lastSnap.Reliability -gt $firstSnap.Reliability)
    $artifactsGrew   = ($null -eq $firstSnap) -or ($lastSnap.Artifacts   -gt $firstSnap.Artifacts)

    Write-Host "`n  Component scores:" -ForegroundColor Yellow
    Write-Host "    Reliability : $([Math]::Round($reliabilityNow*100,1))%"       -ForegroundColor Gray
    Write-Host "    Artifacts   : $($CacheCheck.TotalArtifacts) files -> $([Math]::Round($artifactScore*100,1))%" -ForegroundColor Gray
    Write-Host "    Links       : $($CacheCheck.LinkCount) entries -> $([Math]::Round($linkScore*100,1))%"       -ForegroundColor Gray
    Write-Host "    Context     : $([Math]::Round($contextScore*100,1))%"         -ForegroundColor Gray
    Write-Host "    Contracts   : $([Math]::Round($contractScore*100,1))%"        -ForegroundColor Gray

    $hCol = if ($healthScore -ge [double]$rt.good) { 'Green' } elseif ($healthScore -ge [double]$rt.acceptable) { 'Yellow' } else { 'Red' }
    Write-Host "`n  HEALTH SCORE  : $([Math]::Round($healthScore*100,1))%  [$hs]" -ForegroundColor $hCol

    $grCol1 = if ($reliabilityGrew) { 'Green' } else { 'Red' }
    $grCol2 = if ($artifactsGrew)   { 'Green' } else { 'Red' }
    Write-Host "  Reliability grew : $reliabilityGrew" -ForegroundColor $grCol1
    Write-Host "  Artifacts grew   : $artifactsGrew"   -ForegroundColor $grCol2

    $healthPass = ($healthScore -ge [double]$rt.acceptable) -and $reliabilityGrew -and $artifactsGrew

    return @{
        HealthScore     = [Math]::Round($healthScore, 3)
        HealthStatus    = $hs
        ArtifactScore   = $artifactScore
        LinkScore       = $linkScore
        ContextScore    = $contextScore
        ContractScore   = $contractScore
        ReliabilityGrew = $reliabilityGrew
        ArtifactsGrew   = $artifactsGrew
        HealthPass      = $healthPass
    }
}

function Get-LifecycleState {
    param([hashtable]$ModuleEnum, [hashtable]$ValidationResult)
    $n = $ModuleEnum.SourceCount
    $r = $ValidationResult.ReliabilityScore

    foreach ($state in @('PRODUCTION_READY','DEVELOPING','BOOTSTRAPPING','NO_DATA')) {
        $t = $script:LifecycleThresholds[$state]
        if ($n -ge $t.ModulesMin -and $r -ge [double]$t.Reliability) { return $state }
    }
    return "NO_DATA"
}

# ---------------------------------------------------------------------------
# MAIN
# ---------------------------------------------------------------------------
Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  FULL PROJECT DISCOVERY -- ANALYSIS Strategy v1.0"         -ForegroundColor Cyan
Write-Host "  (project-wide, purge-first, build-system-agnostic C->V)"  -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Project  : $repoRoot"                 -ForegroundColor Gray
Write-Host "  Direction: Code -> Vision (bottom-up)" -ForegroundColor Gray
Write-Host "  Purge    : unconditional"              -ForegroundColor Yellow

$startTime = Get-Date

# --- Discovery pipeline ---
$purgeResult     = Invoke-MandatoryFullPurge                         # snapshot: post-purge (0,0,0)
$archResult      = Invoke-ArchitectureDetection
$moduleEnum      = Invoke-ModuleEnumeration    -ArchResult $archResult
$discoveryResult = Invoke-PerModuleDiscovery   -ModuleEnum $moduleEnum
$cacheCheck      = Invoke-CachePopulationCheck -ModuleEnum $moduleEnum `
                                               -DiscoveryResult $discoveryResult   # snapshot: post-discovery
$linkingResult   = Invoke-CrossModuleLinking   -DiscoveryResult $discoveryResult

# --- Spot tests and contract validation ---
$contextTest     = Invoke-ContextSpotTests          -ModuleEnum $moduleEnum
$contractCheck   = Invoke-ContractContentValidation -LinkingResult $linkingResult

# --- Scoring and learning ---
$validation      = Invoke-ProjectWideValidation -ModuleEnum $moduleEnum `
                                                -DiscoveryResult $discoveryResult `
                                                -LinkingResult $linkingResult `
                                                -ArchResult $archResult
$healthMonitor   = Invoke-HealthGrowthMonitoring -ValidationResult $validation `
                                                 -CacheCheck    $cacheCheck `
                                                 -ContextTest   $contextTest `
                                                 -ContractCheck $contractCheck    # snapshot: post-validation
$metrics         = Invoke-LearningRecording -ModuleEnum $moduleEnum `
                                            -ArchResult $archResult `
                                            -DiscoveryResult $discoveryResult `
                                            -LinkingResult $linkingResult `
                                            -ValidationResult $validation `
                                            -StartTime $startTime

$lifecycleState = Get-LifecycleState -ModuleEnum $moduleEnum -ValidationResult $validation

# --- Success criteria ---
# Primary: zero high-severity issues + arch detected + modules populated + cache has artifacts + health grew
$primarySuccess = ($validation.HighCount -eq 0) `
    -and ($validation.ArchConfidence   -ge 0.80) `
    -and ($validation.ModuleCoverage   -ge [double]$script:RuntimeConfig.Validation.ModuleCoverageMin) `
    -and ($cacheCheck.LinksOk) `
    -and ($healthMonitor.HealthPass)

# Secondary: overall reliability meets lifecycle threshold
$secondarySuccess = $validation.ReliabilityScore -ge [double]$script:LifecycleThresholds[$lifecycleState].Reliability

# Individual check results for the summary table
$checkCache     = ($cacheCheck.HighCount   -eq 0) -and ($cacheCheck.TotalArtifacts -gt 0) -and $cacheCheck.LinksOk
$checkHealth    = $healthMonitor.HealthPass
$checkContext   = $contextTest.ContextScore   -ge 0.50
$checkContracts = $contractCheck.ContractScore -ge 0.50
$overallSuccess = $primarySuccess -and $secondarySuccess

Write-Host "`n========================================" -ForegroundColor Cyan
Write-Host "FINAL SUMMARY"                             -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Lifecycle   : $lifecycleState"           -ForegroundColor Gray
Write-Host "  Build sys   : $($archResult.BuildSystem)  adapter=$($moduleEnum.AdapterUsed)" -ForegroundColor Gray
Write-Host "  Arch detect : $([Math]::Round($archResult.Confidence*100,1))%  [$($archResult.DetectionMethod)]  $($archResult.ArchStyle) / $($archResult.SuggestedTemplate)" -ForegroundColor Gray
Write-Host "  Modules     : $($discoveryResult.DiscoveredCount)/$($moduleEnum.SourceCount) discovered" -ForegroundColor Gray
Write-Host "  Artifacts   : $($cacheCheck.TotalArtifacts) files  links=$($cacheCheck.LinkCount)" -ForegroundColor Gray
Write-Host "  Layers      : $([Math]::Round($validation.LayerCompleteness*100,1))% complete" -ForegroundColor Gray
Write-Host "  Link health : $([Math]::Round($validation.LinkHealth*100,1))%  contracts=$($contractCheck.ContractPairsValid)/$($contractCheck.TotalPairs)" -ForegroundColor Gray
Write-Host "  Reliability : $([Math]::Round($validation.ReliabilityScore*100,1))%  [$($validation.ReliabilityStatus)]" -ForegroundColor Gray
Write-Host "  Health score: $([Math]::Round($healthMonitor.HealthScore*100,1))%  [$($healthMonitor.HealthStatus)]  grew=$($healthMonitor.ReliabilityGrew)" -ForegroundColor Gray
Write-Host "  Context     : $($contextTest.PassCount)/$($contextTest.TotalPoints) spot tests passed" -ForegroundColor Gray
Write-Host "  Duration    : $([int]((Get-Date) - $startTime).TotalSeconds) sec" -ForegroundColor Gray

Write-Host "`n  Check results:" -ForegroundColor Yellow
$ck1 = if ($checkCache)     { "[PASS]" } else { "[FAIL]" }; $cc1 = if ($checkCache)     { 'Green' } else { 'Red' }
$ck2 = if ($checkHealth)    { "[PASS]" } else { "[FAIL]" }; $cc2 = if ($checkHealth)    { 'Green' } else { 'Red' }
$ck3 = if ($checkContext)   { "[PASS]" } else { "[FAIL]" }; $cc3 = if ($checkContext)   { 'Green' } else { 'Yellow' }
$ck4 = if ($checkContracts) { "[PASS]" } else { "[FAIL]" }; $cc4 = if ($checkContracts) { 'Green' } else { 'Yellow' }
Write-Host "    Cache populated (artifacts + links)  : $ck1" -ForegroundColor $cc1
Write-Host "    Health grew to acceptable             : $ck2" -ForegroundColor $cc2
Write-Host "    getContext returns good context       : $ck3" -ForegroundColor $cc3
Write-Host "    Layer contracts valid                 : $ck4" -ForegroundColor $cc4

Write-Host ""
$r1 = "[PASS]"; $c1 = "Green"
$r2 = "[PASS]"; $c2 = "Green"
$r3 = "[ALL PASSED]"; $c3 = "Green"
if (-not $primarySuccess)   { $r1 = "[FAIL]";        $c1 = "Red"    }
if (-not $secondarySuccess) { $r2 = "[FAIL]";        $c2 = "Yellow" }
if (-not $overallSuccess)   { $r3 = "[SOME FAILED]"; $c3 = "Yellow" }
Write-Host "  Primary  : $r1" -ForegroundColor $c1
Write-Host "  Secondary: $r2" -ForegroundColor $c2
Write-Host "  Overall  : $r3" -ForegroundColor $c3

exit $(if ($overallSuccess) { 0 } else { 1 })

